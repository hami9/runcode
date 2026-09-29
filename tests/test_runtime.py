"""Host-side regression checks for the bridge and the shipped HTTP template."""
import importlib.util
import io
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/src/main/python"))
import runcode_runner as runner


class Sink:
    def __init__(self):
        self.lines = []

    def onOutput(self, level, line):
        self.lines.append((level, line))


class RuntimeTest(unittest.TestCase):
    def test_environment_is_restored_after_success_and_failure(self):
        key = "RUNCODE_TEST_SECRET"
        for source in ("import os\nprint(os.environ['RUNCODE_TEST_SECRET'])", "raise ValueError('test')"):
            with tempfile.TemporaryDirectory() as directory:
                script = Path(directory, "main.py")
                script.write_text(source)
                original = os.environ.get(key)
                sink = Sink()
                runner.run_script("env-test", str(script), directory, [key + "=test-only-value"], sink)
                self.assertEqual(original, os.environ.get(key))

    def test_cpu_loop_can_be_stopped(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, "main.py")
            script.write_text("print('ready')\nwhile True:\n    pass\n")
            sink = Sink()
            outcome = []
            worker = threading.Thread(target=lambda: outcome.append(runner.run_script("stop-test", str(script), directory, [], sink)), daemon=True)
            worker.start()
            deadline = time.monotonic() + 5
            while not sink.lines and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(sink.lines)
            runner.request_stop("stop-test")
            worker.join(5)
            self.assertFalse(worker.is_alive())
            self.assertEqual(["stopped"], outcome)

    def test_concurrent_scripts_and_snippets_cannot_share_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, "main.py")
            script.write_text("import time\nprint('ready')\nwhile True:\n    time.sleep(0.05)\n")
            sink, outcome = Sink(), []
            worker = threading.Thread(target=lambda: outcome.append(runner.run_script("owner", str(script), directory, ["RUNCODE_TEST_SECRET=owned-value"], sink)), daemon=True)
            worker.start()
            try:
                deadline = time.monotonic() + 5
                while not sink.lines and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(sink.lines)
                second = runner.run_script("other", str(script), directory, [], Sink())
                self.assertTrue(second.startswith("fatal:Another Python task"))
                snippet = runner.run_snippet("import os; print(os.environ.get('RUNCODE_TEST_SECRET'))", directory)
                self.assertNotIn("owned-value", snippet)
                self.assertIn("Another Python task", snippet)
            finally:
                runner.request_stop("owner")
                worker.join(5)
            self.assertEqual(["stopped"], outcome)
            self.assertNotIn("RUNCODE_TEST_SECRET", os.environ)

    def test_stop_requested_before_registration_is_not_lost(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, "main.py")
            script.write_text("raise AssertionError('should not execute')")
            runner.request_stop("early-stop")
            self.assertEqual("stopped", runner.run_script("early-stop", str(script), directory, [], Sink()))

    def test_http_template_listens_and_stops_without_new_traffic(self):
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        script = ROOT / "app/src/main/assets/templates/server.py"
        sink, outcome = Sink(), []
        worker = threading.Thread(target=lambda: outcome.append(runner.run_script("http-test", str(script), str(script.parent), [f"RUNCODE_PORT={port}", "RUNCODE_BIND_ADDRESS=127.0.0.1"], sink)), daemon=True)
        worker.start()
        try:
            deadline = time.monotonic() + 5
            while not any("listening" in line for _, line in sink.lines) and time.monotonic() < deadline:
                time.sleep(0.01)
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=2) as response:
                self.assertEqual(200, response.status)
                self.assertIn(b'"status": "ok"', response.read())
        finally:
            runner.request_stop("http-test")
            worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(["stopped"], outcome)


if __name__ == "__main__":
    unittest.main()
