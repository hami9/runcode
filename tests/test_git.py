"""Host-side checks for runcode_git against local repositories and an authenticated HTTP server."""
import base64
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/src/main/python"))

try:
    import runcode_git as git
    from dulwich.repo import Repo
    from dulwich.server import DictBackend
    from dulwich.web import make_server, make_wsgi_chain, WSGIRequestHandlerLogger, WSGIServerLogger
except ImportError as missing:  # pragma: no cover
    git = None
    MISSING = missing

TOKEN = "ghp_testtoken1234567890"


def ok(raw):
    result = json.loads(raw)
    if not result["ok"]:
        raise AssertionError(result["error"])
    return result


def fail(raw):
    result = json.loads(raw)
    assert not result["ok"], result
    return result["error"]


def write(directory, name, text):
    path = Path(directory, name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


@unittest.skipIf(git is None, "dulwich is not installed")
class LocalGitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "source")
        os.makedirs(self.repo)
        write(self.repo, "main.py", "print('hi')\n")

    def tearDown(self):
        self.tmp.cleanup()

    def commit(self, message="work"):
        ok(git.add(self.repo, "[]"))
        return ok(git.commit(self.repo, message, "Ada Lovelace", "ada@example.com"))

    def test_init_status_commit_log(self):
        self.assertFalse(git.is_repo(self.repo))
        self.assertEqual("main", ok(git.init(self.repo))["branch"])
        self.assertTrue(git.is_repo(self.repo))
        self.assertTrue(Path(self.repo, ".gitignore").read_text().startswith("__pycache__/"))

        status = ok(git.status(self.repo))
        self.assertEqual([".gitignore", "main.py"], status["untracked"])

        ok(git.add(self.repo, json.dumps(["main.py"])))
        self.assertEqual(["main.py"], ok(git.status(self.repo))["staged"]["added"])

        sha = self.commit("First commit")["sha"]
        entries = ok(git.log(self.repo, "10"))["commits"]
        self.assertEqual(sha, entries[0]["sha"])
        self.assertEqual("First commit", entries[0]["message"])
        self.assertEqual("Ada Lovelace <ada@example.com>", entries[0]["author"])
        self.assertEqual([], ok(git.status(self.repo))["untracked"])

    def test_commit_needs_message_identity_and_staged_changes(self):
        ok(git.init(self.repo))
        ok(git.add(self.repo, "[]"))
        self.assertIn("commit message", fail(git.commit(self.repo, "  ", "A", "a@b.c")))
        self.assertIn("name and email", fail(git.commit(self.repo, "msg", "", "a@b.c")))
        ok(git.commit(self.repo, "msg", "A", "a@b.c"))
        self.assertIn("Nothing is staged", fail(git.commit(self.repo, "again", "A", "a@b.c")))

    def test_add_all_stages_deletions(self):
        ok(git.init(self.repo))
        self.commit()
        os.remove(os.path.join(self.repo, "main.py"))
        ok(git.add(self.repo, "[]"))
        self.assertEqual(["main.py"], ok(git.status(self.repo))["staged"]["deleted"])

    def test_branches_and_checkout(self):
        ok(git.init(self.repo))
        self.assertIn("first commit", fail(git.checkout(self.repo, "feature", True)))
        self.commit()
        ok(git.checkout(self.repo, "feature", True))
        write(self.repo, "feature.py", "x = 1\n")
        self.commit("feature work")
        self.assertEqual({"current": "feature", "branches": ["feature", "main"]},
                         {k: v for k, v in ok(git.branches(self.repo)).items() if k != "ok"})
        ok(git.checkout(self.repo, "main", False))
        self.assertFalse(Path(self.repo, "feature.py").exists())

    def test_not_a_repository_is_a_clear_error(self):
        self.assertIn("Not a git repository", fail(git.status(self.repo)))

    def test_credentials_in_remote_url_are_refused(self):
        ok(git.init(self.repo))
        error = fail(git.set_remote(self.repo, "https://user:%s@github.com/a/b.git" % TOKEN))
        self.assertNotIn(TOKEN, error)

    def test_push_clone_pull_and_divergence_with_a_bare_remote(self):
        remote = os.path.join(self.tmp.name, "remote.git")
        Repo.init_bare(remote, mkdir=True).close()

        ok(git.init(self.repo))
        self.commit("first")
        ok(git.set_remote(self.repo, remote))
        self.assertIsNone(ok(git.status(self.repo))["ahead"])
        ok(git.push(self.repo, "main", "", ""))
        status = ok(git.status(self.repo))
        self.assertEqual((0, 0), (status["ahead"], status["behind"]))
        self.assertEqual(remote, status["remote"])

        other = os.path.join(self.tmp.name, "other")
        self.assertEqual("main", ok(git.clone(remote, other, "", ""))["branch"])
        self.assertEqual("print('hi')\n", Path(other, "main.py").read_text())

        write(other, "second.py", "y = 2\n")
        ok(git.add(other, "[]"))
        ok(git.commit(other, "second", "Bob", "bob@example.com"))
        ok(git.push(other, "main", "", ""))

        self.assertEqual(1, ok(git.pull(self.repo, "main", "", ""))["new_commits"])
        self.assertTrue(Path(self.repo, "second.py").exists())

        # Both sides commit: pull must refuse rather than merge.
        write(other, "third.py", "z = 3\n")
        ok(git.add(other, "[]"))
        ok(git.commit(other, "third", "Bob", "bob@example.com"))
        ok(git.push(other, "main", "", ""))
        write(self.repo, "mine.py", "m = 1\n")
        self.commit("mine")
        self.assertIn("diverged", fail(git.pull(self.repo, "main", "", "")))


class _BasicAuth:
    """Rejects every request that does not carry the token, like GitHub does."""

    def __init__(self, app):
        self.app = app
        self.expected = "Basic " + base64.b64encode(("x-access-token:" + TOKEN).encode()).decode()

    def __call__(self, environ, start_response):
        if environ.get("HTTP_AUTHORIZATION") != self.expected:
            start_response("401 Unauthorized", [("WWW-Authenticate", 'Basic realm="git"'), ("Content-Length", "0")])
            return [b""]
        return self.app(environ, start_response)


@unittest.skipIf(git is None, "dulwich is not installed")
class HttpGitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        bare = os.path.join(self.tmp.name, "remote.git")
        self.remote = Repo.init_bare(bare, mkdir=True)
        app = _BasicAuth(make_wsgi_chain(DictBackend({"/": self.remote})))
        self.server = make_server("127.0.0.1", 0, app, handler_class=WSGIRequestHandlerLogger,
                                  server_class=WSGIServerLogger)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = "http://127.0.0.1:%d/" % self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.remote.close()
        self.tmp.cleanup()

    def test_push_and_clone_need_the_token_and_never_echo_it(self):
        repo = os.path.join(self.tmp.name, "source")
        os.makedirs(repo)
        write(repo, "app.py", "print('pushed over http')\n")
        ok(git.init(repo))
        ok(git.add(repo, "[]"))
        ok(git.commit(repo, "http", "Ada", "ada@example.com"))
        ok(git.set_remote(repo, self.url))

        wrong = "ghp_wrongwrongwrong"
        error = fail(git.push(repo, "main", "x-access-token", wrong))
        self.assertNotIn(wrong, error)
        self.assertNotIn(TOKEN, error)

        ok(git.push(repo, "main", "x-access-token", TOKEN))
        self.assertIn(b"refs/heads/main", self.remote.refs.keys())

        target = os.path.join(self.tmp.name, "cloned")
        ok(git.clone(self.url, target, "x-access-token", TOKEN))
        self.assertEqual("print('pushed over http')\n", Path(target, "app.py").read_text())
        # The token must not end up in the clone's config.
        self.assertNotIn(TOKEN, Path(target, ".git", "config").read_text())


if __name__ == "__main__":
    unittest.main()
