"""
Bridge between the Kotlin runtime engine and the embedded CPython interpreter.

Every script runs through `run_script`, which installs a stdout/stderr tee that pushes
lines straight back into the app's log stream, points the interpreter at the project
directory, and exposes a cooperative stop flag so the supervisor can interrupt a loop.
"""

import io
import os
import runpy
import sys
import threading
import traceback

# Stop flags keyed by the service id the Kotlin side generated.
_stop_flags = {}
_stop_lock = threading.Lock()


class _Tee(io.TextIOBase):
    """File-like object that forwards whole lines to the Kotlin sink."""

    def __init__(self, sink, level):
        self._sink = sink
        self._level = level
        self._buffer = ""

    def writable(self):
        return True

    def write(self, text):
        if not text:
            return 0
        self._buffer += text
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            self._sink.onOutput(self._level, line)
        return len(text)

    def flush(self):
        if self._buffer:
            self._sink.onOutput(self._level, self._buffer)
            self._buffer = ""


class ScriptInterrupt(BaseException):
    """Raised inside the script when the supervisor asks it to stop."""


def request_stop(service_id):
    with _stop_lock:
        _stop_flags[service_id] = True


def _clear_stop(service_id):
    with _stop_lock:
        _stop_flags.pop(service_id, None)


def _should_stop(service_id):
    with _stop_lock:
        return _stop_flags.get(service_id, False)


def _make_tracer(service_id):
    """Trace hook that turns the stop flag into an exception at the next executed line."""

    def tracer(frame, event, arg):
        if _should_stop(service_id):
            raise ScriptInterrupt()
        return tracer

    return tracer


def run_script(service_id, script_path, working_dir, env_pairs, sink):
    """
    Execute `script_path` as __main__.

    Returns "completed", "stopped" or "failed:<message>" so the caller does not have to
    interpret Python exceptions itself.
    """
    _clear_stop(service_id)

    previous_stdout, previous_stderr = sys.stdout, sys.stderr
    previous_cwd = os.getcwd()
    previous_argv = list(sys.argv)

    sys.stdout = _Tee(sink, "stdout")
    sys.stderr = _Tee(sink, "stderr")

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

        threading.settrace(_make_tracer(service_id))
        sys.settrace(_make_tracer(service_id))
        try:
            runpy.run_path(script_path, run_name="__main__")
        finally:
            sys.settrace(None)
            threading.settrace(None)

        return "completed"

    except ScriptInterrupt:
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
        try:
            sys.stdout.flush()
            sys.stderr.flush()
        except Exception:
            pass
        sys.stdout, sys.stderr = previous_stdout, previous_stderr
        sys.argv = previous_argv
        try:
            os.chdir(previous_cwd)
        except Exception:
            pass
        _clear_stop(service_id)


def run_snippet(code, working_dir):
    """
    Execute a one-off snippet and return everything it printed.

    Used by the MCP `run_python` tool, where the caller wants a single string back rather
    than a live stream.
    """
    buffer = io.StringIO()
    previous_stdout, previous_stderr = sys.stdout, sys.stderr
    previous_cwd = os.getcwd()
    sys.stdout = buffer
    sys.stderr = buffer
    try:
        if working_dir and os.path.isdir(working_dir):
            os.chdir(working_dir)
        exec(compile(code, "<mcp-snippet>", "exec"), {"__name__": "__main__"})
    except BaseException:
        buffer.write(traceback.format_exc())
    finally:
        sys.stdout, sys.stderr = previous_stdout, previous_stderr
        try:
            os.chdir(previous_cwd)
        except Exception:
            pass
    return buffer.getvalue()


def interpreter_info():
    """Human-readable runtime banner used by the Health screen."""
    return "{} on {}".format(sys.version.split()[0], sys.platform)
