"""Host-side checks for runcode_debugger: real scripts, real trace hook, commands from another thread."""
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/src/main/python"))
import runcode_debugger as debugger  # noqa: E402
import runcode_runner as runner  # noqa: E402

SCRIPT = """\
import helper

total = 0
for n in range(3):
    total = helper.double(n) + total
message = "done %d" % total
print(message)
"""

HELPER = """\
def double(x):
    y = x * 2
    return y
"""


class Sink:
    def __init__(self):
        self.lines = []

    def onOutput(self, level, line):
        self.lines.append((level, line))


class Listener:
    def __init__(self):
        self.states = []
        self.cond = threading.Condition()

    def onState(self, raw):
        with self.cond:
            self.states.append(json.loads(raw))
            self.cond.notify_all()

    def mark(self):
        with self.cond:
            return len(self.states)

    def wait_for(self, predicate, timeout=10, after=0):
        """First state published at index >= after that matches."""
        found = []

        def match():
            for state in self.states[after:]:
                if predicate(state):
                    found.append(state)
                    return True
            return False

        with self.cond:
            ok = self.cond.wait_for(match, timeout)
        assert ok, "timed out; states %r" % (self.states[after:] or None)
        return found[0]

    def paused(self, after=0):
        return self.wait_for(lambda s: s["status"] == "paused", after=after)


class DebuggerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.source = os.path.join(self.tmp.name, "source")
        os.makedirs(self.source)
        Path(self.source, "main.py").write_text(SCRIPT)
        Path(self.source, "helper.py").write_text(HELPER)
        self.script = os.path.join(self.source, "main.py")
        self.helper = os.path.join(self.source, "helper.py")
        self.sink = Sink()
        self.listener = Listener()
        self.outcome = []
        self.session = "svc_py_test_%d" % time.monotonic_ns()

    def tearDown(self):
        # A failed test must not leave a paused script holding the interpreter.
        if json.loads(debugger.state(self.session))["status"] != "idle":
            runner.request_stop(self.session)
            deadline = time.monotonic() + 5
            while json.loads(debugger.state(self.session))["status"] != "idle" and time.monotonic() < deadline:
                time.sleep(0.05)
        self.tmp.cleanup()
        sys.modules.pop("helper", None)

    def start(self, breakpoints):
        thread = threading.Thread(target=lambda: self.outcome.append(debugger.debug_script(
            self.session, self.script, self.tmp.name, [], self.sink, self.listener, json.dumps(breakpoints))),
            daemon=True)
        thread.start()
        return thread

    def send(self, name):
        """Sends a command; returns the index after which its effects are published."""
        mark = self.listener.mark()
        reply = json.loads(debugger.command(self.session, name))
        self.assertTrue(reply["ok"], reply)
        return mark

    def finish(self, thread):
        thread.join(10)
        self.assertFalse(thread.is_alive())
        return self.listener.wait_for(lambda s: s["status"] == "finished")

    def test_breakpoint_variables_and_continue(self):
        thread = self.start({self.script: [6]})
        state = self.listener.paused()
        self.assertEqual(("main.py", 6, "breakpoint"), (state["file"], state["line"], state["reason"]))
        names = {v["name"]: v["value"] for v in state["globals"]}
        self.assertEqual("6", names["total"])
        self.assertNotIn("helper", names, "modules are not shown as variables")
        self.send("continue")
        self.assertEqual("completed", self.finish(thread)["outcome"])
        self.assertIn(("stdout", "done 6"), self.sink.lines)

    def test_breakpoint_in_another_file_shows_the_call_stack(self):
        thread = self.start({self.helper: [3]})
        state = self.listener.paused()
        self.assertEqual(("helper.py", 3, "double"), (state["file"], state["line"], state["function"]))
        self.assertEqual(["double", "<module>"], [f["function"] for f in state["stack"]])
        self.assertEqual({"x": "0", "y": "0"}, {v["name"]: v["value"] for v in state["locals"]})
        # Hit again on the next loop iteration.
        mark = self.send("continue")
        state = self.listener.paused(after=mark)
        self.assertEqual("1", {v["name"]: v["value"] for v in state["locals"]}["x"])
        mark = self.send("continue")
        self.listener.paused(after=mark)
        self.send("continue")
        self.assertEqual("completed", self.finish(thread)["outcome"])

    def test_step_into_over_and_out(self):
        thread = self.start({self.script: [5]})
        self.assertEqual(5, self.listener.paused()["line"])

        state = self.listener.paused(after=self.send("step"))  # into helper.double
        self.assertEqual(("helper.py", 2), (state["file"], state["line"]))

        state = self.listener.paused(after=self.send("next"))
        self.assertEqual(("helper.py", 3), (state["file"], state["line"]))

        state = self.listener.paused(after=self.send("return"))  # back in the caller
        self.assertEqual("main.py", state["file"])
        self.assertIn(state["line"], (4, 5))

        state = self.listener.paused(after=self.send("next"))
        self.assertEqual("main.py", state["file"], "next must not descend into helper.double")
        # The breakpoint sits in a loop: every remaining stop is on it, until the end.
        while True:
            state = self.listener.wait_for(lambda s: s["status"] in ("paused", "finished"),
                                           after=self.send("continue"))
            if state["status"] == "finished":
                break
            self.assertEqual((5, "breakpoint"), (state["line"], state["reason"]))
        self.assertEqual("completed", state["outcome"])

    def test_stepping_never_enters_library_code(self):
        Path(self.script).write_text("import json\ndata = json.dumps({'a': 1})\nprint(data)\n")
        thread = self.start({self.script: [2]})
        self.listener.paused()
        state = self.listener.paused(after=self.send("step"))
        self.assertEqual(("main.py", 3), (state["file"], state["line"]))
        self.send("continue")
        self.finish(thread)

    def test_evaluate_in_the_paused_frame(self):
        thread = self.start({self.helper: [3]})
        self.listener.paused()
        self.assertEqual({"ok": True, "type": "int", "value": "10"},
                         json.loads(debugger.evaluate(self.session, "y + 10")))
        self.assertIn("NameError", json.loads(debugger.evaluate(self.session, "nope"))["error"])
        self.assertIn("SyntaxError", json.loads(debugger.evaluate(self.session, "1 +"))["error"])
        for _ in range(2):
            self.listener.paused(after=self.send("continue"))
        self.send("continue")
        self.finish(thread)

    def test_commands_are_refused_while_running(self):
        self.assertFalse(json.loads(debugger.command("nobody", "continue"))["ok"])
        self.assertFalse(json.loads(debugger.command(self.session, "jump"))["ok"])

    def test_stop_command_reports_stopped(self):
        thread = self.start({self.script: [3]})
        self.listener.paused()
        self.send("stop")
        self.assertEqual("stopped", self.finish(thread)["outcome"])
        self.assertNotIn(("stdout", "done 6"), self.sink.lines)

    def test_supervisor_stop_interrupts_a_paused_script(self):
        thread = self.start({self.script: [3]})
        self.listener.paused()
        runner.request_stop(self.session)
        self.assertEqual("stopped", self.finish(thread)["outcome"])

    def test_breakpoints_can_change_while_running(self):
        thread = self.start({self.script: [3]})
        self.listener.paused()
        reply = json.loads(debugger.set_breakpoints(self.session, self.script, json.dumps([7])))
        self.assertTrue(reply["ok"], reply)
        self.assertEqual(7, self.listener.paused(after=self.send("continue"))["line"])
        self.send("continue")
        self.finish(thread)

    def test_invalid_breakpoint_is_reported_not_fatal(self):
        thread = self.start({self.script: [999]})
        self.assertEqual("completed", self.finish(thread)["outcome"])
        self.assertTrue(any("Breakpoint ignored" in line for _, line in self.sink.lines))

    def test_without_breakpoints_nothing_pauses(self):
        thread = self.start({})
        self.assertEqual("completed", self.finish(thread)["outcome"])
        self.assertFalse(any(s["status"] == "paused" for s in self.listener.states))

    def test_lines_are_not_traced_in_files_without_breakpoints(self):
        # bdb only installs a per-line hook in frames whose file has a breakpoint, so a loop
        # in other code runs without per-line callbacks.
        Path(self.helper).write_text("def double(x):\n    import sys\n    return 0 if sys._getframe().f_trace is None else 1\n")
        Path(self.script).write_text("import helper\nprint('lines traced: %d' % helper.double(1))\nx = 1\n")
        thread = self.start({self.script: [3]})
        self.listener.paused()
        self.send("continue")
        self.finish(thread)
        self.assertIn(("stdout", "lines traced: 0"), self.sink.lines)

    def test_normal_runs_are_unaffected(self):
        outcome = runner.run_script("plain_%d" % time.monotonic_ns(), self.script, self.tmp.name, [], self.sink)
        self.assertEqual("completed", outcome)
        self.assertEqual(json.loads(debugger.state("plain"))["status"], "idle")


if __name__ == "__main__":
    unittest.main()
