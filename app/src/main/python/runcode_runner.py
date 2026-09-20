"""
Bridge between the Kotlin runtime engine and the embedded CPython interpreter.

Every script runs through `run_script`, which installs a stdout/stderr tee that pushes
lines straight back into the app's log stream, points the interpreter at the project
directory, and gives the supervisor a way to interrupt a running script.

Stopping uses PyThreadState_SetAsyncExc, which raises an exception in the target thread at
its next bytecode boundary and costs nothing while the script runs normally. The obvious
alternative, a sys.settrace hook, was measured on device at 25x slower:

    WITH tracer      0.945s  ->   317,478 iter/s
    WITHOUT tracer   0.037s  -> 8,134,909 iter/s

A trace hook is still used, but only as a fallback where ctypes is unavailable.
"""

import io
import os
import runpy
import sys
import threading
import traceback

try:
    import ctypes

    _set_async_exc = ctypes.pythonapi.PyThreadState_SetAsyncExc
    _set_async_exc.argtypes = [ctypes.c_ulong, ctypes.py_object]
    _set_async_exc.restype = ctypes.c_int
except Exception:  # pragma: no cover - only on a build without ctypes
    ctypes = None
    _set_async_exc = None

_lock = threading.Lock()
_threads = {}       # service_id -> thread ident
_stop_flags = {}    # service_id -> True, only consulted by the fallback tracer


class ScriptInterrupt(BaseException):
    """Raised inside the script when the supervisor asks it to stop."""


class _StreamDispatcher(io.TextIOBase):
    """
    Routes writes to whichever service owns the calling thread.

    sys.stdout is process-global, so two concurrent scripts each replacing it meant the
    last one to start captured both — service A's prints landed in service B's log. One
    dispatcher is installed instead, and it looks the sink up per thread.
    """

    def __init__(self, level, original):
        self._level = level
        self._original = original
        self._sinks = {}     # thread ident -> sink
        self._buffers = {}   # thread ident -> partial line
        self._guard = threading.Lock()

    def register(self, ident, sink):
        with self._guard:
            self._sinks[ident] = sink
            self._buffers[ident] = ""

    def unregister(self, ident):
        with self._guard:
            leftover = self._buffers.pop(ident, "")
            sink = self._sinks.pop(ident, None)
        if leftover and sink is not None:
            sink.onOutput(self._level, leftover)

    def writable(self):
        return True

    def write(self, text):
        if not text:
            return 0
        ident = threading.get_ident()
        with self._guard:
            sink = self._sinks.get(ident)
            if sink is None:
                # Not a script thread: let it through to the platform log.
                target = self._original
            else:
                buffered = self._buffers.get(ident, "") + text
                lines = buffered.split("\n")
                self._buffers[ident] = lines.pop()
                target = None

        if target is not None:
            try:
                return target.write(text)
            except Exception:
                return len(text)

        for line in lines:
            sink.onOutput(self._level, line)
        return len(text)

    def flush(self):
        ident = threading.get_ident()
        with self._guard:
            sink = self._sinks.get(ident)
            leftover = self._buffers.get(ident, "")
            if sink is not None:
                self._buffers[ident] = ""
        if sink is not None and leftover:
            sink.onOutput(self._level, leftover)


_stdout_dispatcher = _StreamDispatcher("stdout", sys.stdout)
_stderr_dispatcher = _StreamDispatcher("stderr", sys.stderr)
sys.stdout = _stdout_dispatcher
sys.stderr = _stderr_dispatcher


def request_stop(service_id):
    """
    Ask a running script to stop.

    Returns "async", "flag" or "unknown" so the caller can tell how the request was
    delivered. A thread blocked inside a C call (a socket read, time.sleep) receives the
    exception when that call returns, not instantly.
    """
    with _lock:
        ident = _threads.get(service_id)
        _stop_flags[service_id] = True

    if ident is None:
        return "unknown"

    if _set_async_exc is not None:
        affected = _set_async_exc(ctypes.c_ulong(ident), ctypes.py_object(ScriptInterrupt))
        if affected > 1:
            # Raised in more than one thread, which must never happen; undo it.
            _set_async_exc(ctypes.c_ulong(ident), None)
            return "unknown"
        if affected == 1:
            return "async"

    return "flag"


def _fallback_tracer(service_id):
    """Only used where ctypes is missing: slow, but better than an unstoppable script."""

    def tracer(frame, event, arg):
        with _lock:
            if _stop_flags.get(service_id, False):
                raise ScriptInterrupt()
        return tracer

    return tracer


def run_script(service_id, script_path, working_dir, env_pairs, sink):
    """
    Execute `script_path` as __main__.

    Returns "completed", "stopped", "fatal:<message>" or "failed:<message>" so the caller
    does not have to interpret Python exceptions itself.
    """
    ident = threading.get_ident()
    with _lock:
        _threads[service_id] = ident
        _stop_flags.pop(service_id, None)

    previous_cwd = os.getcwd()
    previous_argv = list(sys.argv)

    # Per-thread routing, so concurrent services never steal each other's output.
    _stdout_dispatcher.register(ident, sink)
    _stderr_dispatcher.register(ident, sink)

    using_fallback = _set_async_exc is None

    try:
        for pair in env_pairs:
            key, _, value = pair.partition("=")
            if key:
                os.environ[key] = value

        if working_dir and os.path.isdir(working_dir):
            os.chdir(working_dir)
            if working_dir not in sys.path:
                sys.path.insert(0, working_dir)

        script_dir = os.path.dirname(script_path)
        if script_dir and script_dir not in sys.path:
            sys.path.insert(0, script_dir)

        sys.argv = [script_path]

        if using_fallback:
            sys.settrace(_fallback_tracer(service_id))
        try:
            runpy.run_path(script_path, run_name="__main__")
        finally:
            if using_fallback:
                sys.settrace(None)

        return "completed"

    except ScriptInterrupt:
        return "stopped"
    except KeyboardInterrupt:
        return "stopped"
    except SystemExit as exit_error:
        code = exit_error.code
        if code in (None, 0):
            return "completed"
        return "failed:SystemExit: {}".format(code)
    except (SyntaxError, ModuleNotFoundError) as load_error:
        # The script cannot be loaded at all. Restarting it would fail identically
        # every time, so report it as fatal and let the supervisor stop trying.
        for line in traceback.format_exc().rstrip().split("\n"):
            sink.onOutput("stderr", line)
        summary = traceback.format_exception_only(type(load_error), load_error)[-1].strip()
        return "fatal:{}".format(summary)
    except BaseException:
        for line in traceback.format_exc().rstrip().split("\n"):
            sink.onOutput("stderr", line)
        summary = traceback.format_exception_only(*sys.exc_info()[:2])[-1].strip()
        return "failed:{}".format(summary)
    finally:
        _stdout_dispatcher.unregister(ident)
        _stderr_dispatcher.unregister(ident)
        sys.argv = previous_argv
        try:
            os.chdir(previous_cwd)
        except Exception:
            pass
        with _lock:
            _threads.pop(service_id, None)
            _stop_flags.pop(service_id, None)


def run_snippet(code, working_dir):
    """
    Execute a one-off snippet and return everything it printed.

    Used by the MCP `run_python` tool, where the caller wants a single string back rather
    than a live stream.
    """
    buffer = io.StringIO()

    class _Collector:
        def onOutput(self, level, line):
            buffer.write(line)
            buffer.write("\n")

    ident = threading.get_ident()
    previous_cwd = os.getcwd()
    _stdout_dispatcher.register(ident, _Collector())
    _stderr_dispatcher.register(ident, _Collector())
    try:
        if working_dir and os.path.isdir(working_dir):
            os.chdir(working_dir)
        exec(compile(code, "<mcp-snippet>", "exec"), {"__name__": "__main__"})
    except BaseException:
        buffer.write(traceback.format_exc())
    finally:
        _stdout_dispatcher.unregister(ident)
        _stderr_dispatcher.unregister(ident)
        try:
            os.chdir(previous_cwd)
        except Exception:
            pass
    return buffer.getvalue()


def interpreter_info():
    """Human-readable runtime banner used by the System screen."""
    return "{} on {}".format(sys.version.split()[0], sys.platform)
