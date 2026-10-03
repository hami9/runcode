"""
Python debugger for runcode projects, built on bdb (the standard library base that pdb uses).

The trace hook exists only while a script runs under debug_script(). Normal runs never pay
for it: a permanent sys.settrace made every script 25x slower (see runcode_runner).

Flow: debug_script() runs the project through runcode_runner.run_script with an executor that
wraps runpy in Bdb.runcall. When execution reaches a breakpoint or finishes a step, the script
thread publishes its state to the Kotlin listener and waits for a command from command(), which
the app calls from another thread. Evaluation runs on the paused thread itself, so expressions
see the real frame. An expression that runs past the timeout, or a stop sent meanwhile,
interrupts it with an async exception, the same way the supervisor stops a script.

Only the script's main thread is traced, and only files under the project root are stepped
into or stopped in; library code runs at full speed. Like any trace hook it acts between Python
lines, so a script blocked inside a C call (time.sleep, a socket read) pauses on the next line.

All public functions return JSON strings.
"""

import bdb
import collections
import json
import os
import reprlib
import sys
import threading
import types

import runcode_runner

_sessions = {}
_sessions_lock = threading.Lock()

_COMMANDS = ("continue", "step", "next", "return", "stop")
_NOWHERE = object()  # a stop frame no real frame is
_MAX_VARIABLES = 60
_EVAL_TIMEOUT_S = 10.0

_repr = reprlib.Repr()
_repr.maxstring = 160
_repr.maxother = 160
_repr.maxlist = _repr.maxtuple = _repr.maxset = _repr.maxdict = 12
_repr.maxlevel = 3


def _ok(**fields):
    return json.dumps(dict(ok=True, **fields))


def _fail(message):
    return json.dumps({"ok": False, "error": message})


def _show(value):
    try:
        return _repr.repr(value)
    except Exception as error:  # noqa: BLE001 - a broken __repr__ must not stop the debugger
        return "<repr failed: %s>" % error.__class__.__name__


class _EvalCancelled(BaseException):
    """Raised in the paused thread to abandon an expression that is still running."""


class _EvalRequest:
    def __init__(self, expression):
        self.expression = expression
        self.result = None       # JSON reply, set once
        self.abandoned = False   # the caller stopped waiting


def _variables(namespace, globals_only=False):
    shown = []
    for name, value in namespace.items():
        if name.startswith("__") and name.endswith("__"):
            continue
        if globals_only and isinstance(value, (types.ModuleType, types.FunctionType, type,
                                               types.BuiltinFunctionType)):
            continue
        shown.append({"name": name, "type": type(value).__name__, "value": _show(value)})
        if len(shown) >= _MAX_VARIABLES:
            break
    return shown


class _Session(bdb.Bdb):

    def __init__(self, session_id, root, listener):
        super().__init__()
        self.session_id = session_id
        self.root = os.path.abspath(root) + os.sep
        self.listener = listener
        self.cond = threading.Condition()
        self.requests = collections.deque()  # ("eval", _EvalRequest) or (command, None)
        self.evaluating = None       # the _EvalRequest the paused thread is running
        self.thread_ident = None     # the script thread, for interrupting an expression
        self.paused = False
        self.stopped_by_user = False
        self.state = {"status": "starting"}

    # -- where to stop -------------------------------------------------------------------

    def is_user_frame(self, frame):
        return self.canonic(frame.f_code.co_filename).startswith(self.root)

    def stop_here(self, frame):
        # Never stop inside the standard library or packages; stepping passes through them.
        return self.is_user_frame(frame) and super().stop_here(frame)

    # -- bdb callbacks -------------------------------------------------------------------

    def user_line(self, frame):
        if not self.is_user_frame(frame):
            return
        filename = self.canonic(frame.f_code.co_filename)
        reason = "breakpoint" if self.get_breaks(filename, frame.f_lineno) else "step"
        self._pause(frame, reason)

    # Calls, returns and exceptions do not stop on their own: stepping lands on the next line.
    def user_call(self, frame, argument_list):
        pass

    def user_return(self, frame, return_value):
        pass

    def user_exception(self, frame, exc_info):
        pass

    # -- pausing -------------------------------------------------------------------------

    def _pause(self, frame, reason):
        # Accept commands before announcing the pause: a client that reacts to the published
        # state at once must not be told the program is still running.
        with self.cond:
            self.requests.clear()
            self.thread_ident = threading.get_ident()
            self.paused = True
        self._publish(self._snapshot(frame, reason))
        try:
            command = self._serve(frame)
        finally:
            with self.cond:
                self.paused = False
                for kind, request in self.requests:
                    if kind == "eval":
                        request.result = _fail("The program resumed before the expression ran.")
                self.requests.clear()
                self.cond.notify_all()

        if command == "continue":
            self.set_continue()
        elif command == "step":
            self.set_step()
        elif command == "next":
            self.set_next(frame)
        elif command == "return":
            self.set_return(frame)
        elif command == "stop":
            self.stopped_by_user = True
            self.set_quit()
        self._publish({"status": "running"})

    def _serve(self, frame):
        """Runs expressions until a command arrives, and returns that command."""
        while True:
            # The lock is never held while an expression runs, so a caller's timeout and a
            # stop command still work while one is slow.
            try:
                with self.cond:
                    while not self.requests:
                        # Short waits, so the async stop exception from the supervisor lands.
                        self.cond.wait(0.1)
                    kind, request = self.requests.popleft()
                    if kind != "eval":
                        return kind
                    if request.abandoned:
                        continue
                    self.evaluating = request
                result = self._evaluate(frame, request.expression)
                with self.cond:
                    self.evaluating = None
                    request.result = result
                    self.cond.notify_all()
            except _EvalCancelled:
                # Only ever raised while an expression runs, and caught here even when it
                # lands just after the expression returned.
                with self.cond:
                    if self.evaluating is not None:
                        self.evaluating.result = _fail("The expression was interrupted.")
                        self.evaluating = None
                    self.cond.notify_all()

    def _interrupt_evaluation(self):
        """Call with self.cond held, while self.evaluating is set."""
        set_async_exc, ctypes = runcode_runner._set_async_exc, runcode_runner.ctypes
        if set_async_exc is None or self.thread_ident is None:
            return False
        ident = ctypes.c_ulong(self.thread_ident)
        affected = set_async_exc(ident, ctypes.py_object(_EvalCancelled))
        if affected > 1:
            set_async_exc(ident, None)
            return False
        return affected == 1

    def _evaluate(self, frame, expression):
        try:
            code = compile(expression, "<debug>", "eval")
        except SyntaxError as error:
            return _fail("SyntaxError: %s" % error.msg)
        try:
            value = eval(code, frame.f_globals, frame.f_locals)  # noqa: S307 - the user's own code
        except Exception as error:  # noqa: BLE001 - reported to the user
            return _fail("%s: %s" % (error.__class__.__name__, error))
        return _ok(type=type(value).__name__, value=_show(value))

    def _snapshot(self, frame, reason):
        stack = []
        walk = frame
        while walk is not None:
            if self.is_user_frame(walk):
                stack.append({
                    "file": self._relative(walk.f_code.co_filename),
                    "line": walk.f_lineno,
                    "function": walk.f_code.co_name,
                })
            walk = walk.f_back
        return {
            "status": "paused",
            "reason": reason,
            "file": self._relative(frame.f_code.co_filename),
            "line": frame.f_lineno,
            "function": frame.f_code.co_name,
            "stack": stack,
            "locals": _variables(frame.f_locals) if frame.f_locals is not frame.f_globals else [],
            "globals": _variables(frame.f_globals, globals_only=True),
        }

    def _relative(self, filename):
        path = self.canonic(filename)
        return path[len(self.root):] if path.startswith(self.root) else path

    def _publish(self, state):
        self.state = state
        if self.listener is not None:
            try:
                self.listener.onState(json.dumps(state))
            except Exception:  # noqa: BLE001 - a UI problem must not kill the script
                pass

    # -- commands from other threads -----------------------------------------------------

    def send(self, command):
        with self.cond:
            if not self.paused:
                return _fail("The program is running, not paused.")
            if any(kind != "eval" for kind, _ in self.requests):
                return _fail("Another command is already waiting to run.")
            self.requests.append((command, None))
            if command == "stop" and self.evaluating is not None:
                # Do not make a stop wait for a slow expression.
                self._interrupt_evaluation()
            self.cond.notify_all()
        return _ok()

    def evaluate(self, expression):
        request = _EvalRequest(expression)
        timeout = _EVAL_TIMEOUT_S
        with self.cond:
            if not self.paused:
                return _fail("Pause the program to evaluate expressions.")
            self.requests.append(("eval", request))
            self.cond.notify_all()
            if self.cond.wait_for(lambda: request.result is not None, timeout):
                return request.result
            # Late results are dropped with the request; a queued one is skipped.
            request.abandoned = True
            interrupted = self.evaluating is request and self._interrupt_evaluation()
        return _fail("The expression did not finish within %g seconds%s." % (
            timeout, " and was interrupted" if interrupted else ""))

    def run(self, script_path):
        # Bdb.runcall starts in single-step mode and would stop on line 1. Start "continue"
        # instead: a stop frame that never matches means only breakpoints stop the script.
        self.reset()
        self._set_stopinfo(_NOWHERE, None, -1)
        sys.settrace(self.trace_dispatch)
        try:
            runcode_runner._run_path(script_path)
        except bdb.BdbQuit:
            pass
        finally:
            self.quitting = True
            sys.settrace(None)
            self.paused = False
        if self.stopped_by_user:
            # bdb swallows its own quit signal; report the run as stopped, not completed.
            raise runcode_runner.ScriptInterrupt()


# ----------------------------------------------------------------------------- public API

def debug_script(session_id, script_path, working_dir, env_pairs, sink, listener, breakpoints_json):
    """
    Run a script under the debugger. Blocks until it ends and returns the same outcome
    strings as runcode_runner.run_script. [breakpoints_json] maps absolute file paths to
    line numbers.
    """
    root = os.path.dirname(os.path.abspath(script_path))
    session = _Session(session_id, root, listener)
    errors = _apply_breakpoints(session, json.loads(breakpoints_json or "{}"))
    for message in errors:
        sink.onOutput("stderr", "Breakpoint ignored: " + message)
    with _sessions_lock:
        _sessions[session_id] = session
    try:
        outcome = runcode_runner.run_script(session_id, script_path, working_dir, env_pairs, sink,
                                            executor=session.run)
    finally:
        with _sessions_lock:
            _sessions.pop(session_id, None)
        session.clear_all_breaks()
    session._publish({"status": "finished", "outcome": outcome})
    return outcome


def _apply_breakpoints(session, by_file):
    errors = []
    for filename, lines in by_file.items():
        canonical = session.canonic(filename)
        session.clear_all_file_breaks(canonical)
        for line in sorted(set(int(n) for n in lines)):
            error = session.set_break(canonical, line)
            if error:
                errors.append(error)
    return errors


def set_breakpoints(session_id, filename, lines_json):
    """Replaces one file's breakpoints in a running session."""
    session = _sessions.get(session_id)
    if session is None:
        return _fail("No debug session is running.")
    errors = _apply_breakpoints(session, {filename: json.loads(lines_json)})
    return _ok(errors=errors)


def command(session_id, name):
    """continue, step (into), next (over), return (out) or stop. Only while paused."""
    if name not in _COMMANDS:
        return _fail("Unknown command %r" % name)
    session = _sessions.get(session_id)
    if session is None:
        return _fail("No debug session is running.")
    return session.send(name)


def evaluate(session_id, expression):
    session = _sessions.get(session_id)
    if session is None:
        return _fail("No debug session is running.")
    return session.evaluate(expression)


def state(session_id):
    session = _sessions.get(session_id)
    if session is None:
        return _ok(status="idle")
    return json.dumps(dict(ok=True, **session.state))
