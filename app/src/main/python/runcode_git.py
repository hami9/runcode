"""
Git for runcode projects, on dulwich (pure Python, so it runs inside the embedded CPython; a
native git binary cannot be shipped or executed on Android).

Every public function takes plain strings and returns a JSON string, so the Kotlin side never
handles Python objects: {"ok": true, ...} on success, {"ok": false, "error": "..."} otherwise.

Credentials arrive as arguments, are handed to dulwich's HTTP client, and are scrubbed from
every error message. They are never written to the repository config or to any output.
"""

import io
import json
import re
import time

from dulwich import porcelain
from dulwich.errors import NotGitRepository
from dulwich.repo import Repo

DEFAULT_BRANCH = "main"
GITIGNORE = "__pycache__/\n*.pyc\n"
_URL_CREDENTIALS = re.compile(r"(://)[^/@\s]+@")


# ----------------------------------------------------------------------------- helpers

def _result(**fields):
    return json.dumps(dict(ok=True, **fields))


def _failure(error, secrets=()):
    text = str(error) or error.__class__.__name__
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    text = _URL_CREDENTIALS.sub(r"\1[REDACTED]@", text)
    return json.dumps({"ok": False, "error": text})


def _guarded(*secrets):
    """Turns any exception into a JSON failure with the given secrets scrubbed out."""
    def wrap(fn):
        def inner(*args):
            try:
                return fn(*args)
            except NotGitRepository:
                return _failure("Not a git repository. Initialise it first.")
            except Exception as error:  # noqa: BLE001 - reported to the UI, never raised
                found = [args[i] for i in secrets if i < len(args)]
                return _failure(error, found)
        inner.__name__ = fn.__name__
        inner.__doc__ = fn.__doc__
        return inner
    return wrap


def _text(value):
    return value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)


def _quiet():
    """dulwich writes progress to stdout/stderr by default; keep it out of the app log."""
    return {"outstream": io.BytesIO(), "errstream": io.BytesIO()}


def _credentials(username, password):
    return {"username": username, "password": password} if password else {}


def _current_branch(repo):
    try:
        return _text(porcelain.active_branch(repo))
    except (KeyError, IndexError, ValueError):
        return None


def _head(repo):
    try:
        return repo.head()
    except KeyError:
        return None


def _count(repo, include, exclude):
    if include is None:
        return 0
    return sum(1 for _ in repo.get_walker(include=[include], exclude=[exclude] if exclude else None))


# ----------------------------------------------------------------------------- local

def is_repo(repo_dir):
    try:
        Repo(repo_dir).close()
        return True
    except NotGitRepository:
        return False


@_guarded()
def init(repo_dir):
    """Creates a repository on the main branch with a small .gitignore for Python caches."""
    import os

    repo = porcelain.init(repo_dir)
    try:
        repo.refs.set_symbolic_ref(b"HEAD", ("refs/heads/" + DEFAULT_BRANCH).encode())
    finally:
        repo.close()
    ignore = os.path.join(repo_dir, ".gitignore")
    if not os.path.exists(ignore):
        with open(ignore, "w") as handle:
            handle.write(GITIGNORE)
    return _result(branch=DEFAULT_BRANCH)


@_guarded()
def status(repo_dir):
    with Repo(repo_dir) as repo:
        state = porcelain.status(repo)
        staged = {key: sorted(_text(p) for p in paths) for key, paths in state.staged.items()}
        branch = _current_branch(repo)
        head = _head(repo)
        tracking = repo.refs.as_dict().get(("refs/remotes/origin/" + branch).encode()) if branch else None
        return _result(
            branch=branch,
            head=_text(head)[:7] if head else None,
            staged={"added": staged.get("add", []), "modified": staged.get("modify", []),
                    "deleted": staged.get("delete", [])},
            unstaged=sorted(_text(p) for p in state.unstaged),
            untracked=sorted(_text(p) for p in state.untracked),
            ahead=_count(repo, head, tracking) if tracking else None,
            behind=_count(repo, tracking, head) if tracking else None,
            remote=remote_url_of(repo),
        )


@_guarded()
def add(repo_dir, paths_json):
    """Stages the given paths, or everything (including deletions) when the list is empty."""
    paths = json.loads(paths_json) if paths_json else []
    with Repo(repo_dir) as repo:
        if paths:
            import os
            porcelain.add(repo, [os.path.join(repo_dir, p) for p in paths])
        else:
            porcelain.add(repo)
            # porcelain.add does not stage removals; mirror `git add -A`.
            removed = porcelain.status(repo).unstaged
            gone = [p for p in removed if not _exists(repo_dir, p)]
            if gone:
                porcelain.remove(repo, [_abs(repo_dir, p) for p in gone], cached=True)
    return _result()


def _abs(repo_dir, path):
    import os
    return os.path.join(repo_dir, _text(path))


def _exists(repo_dir, path):
    import os
    return os.path.lexists(_abs(repo_dir, path))


@_guarded()
def commit(repo_dir, message, author_name, author_email):
    if not message.strip():
        raise ValueError("Write a commit message first.")
    if not author_name.strip() or not author_email.strip():
        raise ValueError("Set your name and email under Git settings; GitHub uses them to show the author.")
    identity = ("%s <%s>" % (author_name.strip(), author_email.strip())).encode()
    with Repo(repo_dir) as repo:
        state = porcelain.status(repo)
        if not any(state.staged.values()):
            raise ValueError("Nothing is staged. Stage changes before committing.")
        # No signing: there is no gpg or ssh-keygen on the device, and a global git config
        # asking for it must not make every commit fail.
        sha = porcelain.commit(repo, message=message.encode(), author=identity, committer=identity, sign=False)
    return _result(sha=_text(sha)[:7])


@_guarded()
def log(repo_dir, limit):
    with Repo(repo_dir) as repo:
        if _head(repo) is None:
            return _result(commits=[])
        entries = []
        for entry in repo.get_walker(max_entries=int(limit)):
            c = entry.commit
            entries.append({
                "sha": _text(c.id)[:7],
                "author": _text(c.author),
                "time": c.author_time,
                "message": _text(c.message).strip().split("\n")[0],
            })
        return _result(commits=entries)


@_guarded()
def branches(repo_dir):
    with Repo(repo_dir) as repo:
        names = sorted(_text(b) for b in porcelain.branch_list(repo))
        return _result(current=_current_branch(repo), branches=names)


@_guarded()
def checkout(repo_dir, name, create):
    """Switches branch. Refuses when uncommitted changes would be overwritten."""
    with Repo(repo_dir) as repo:
        if create:
            if _head(repo) is None:
                raise ValueError("Make a first commit before creating branches.")
            porcelain.branch_create(repo, name)
        porcelain.checkout(repo, name)
    return _result(branch=name)


def remote_url_of(repo):
    try:
        return _text(repo.get_config().get((b"remote", b"origin"), b"url"))
    except KeyError:
        return None


@_guarded()
def set_remote(repo_dir, url):
    if _URL_CREDENTIALS.search(url):
        raise ValueError("Leave credentials out of the URL; set a token under Git settings instead.")
    with Repo(repo_dir) as repo:
        config = repo.get_config()
        config.set((b"remote", b"origin"), b"url", url.encode())
        config.set((b"remote", b"origin"), b"fetch", b"+refs/heads/*:refs/remotes/origin/*")
        config.write_to_path()
    return _result(remote=url)


# ----------------------------------------------------------------------------- network
# Arguments 2 and 3 (username, password) are scrubbed from errors by @_guarded.

@_guarded(2, 3)
def clone(url, target_dir, username, password):
    started = time.time()
    repo = porcelain.clone(url, target_dir, checkout=True, errstream=io.BytesIO(),
                           **_credentials(username, password))
    try:
        if _head(repo) is None:
            _checkout_fallback_branch(repo)
        branch = _current_branch(repo)
    finally:
        repo.close()
    return _result(branch=branch, seconds=round(time.time() - started, 1))


def _checkout_fallback_branch(repo):
    """The remote's HEAD named a branch that does not exist: check out main, or the first one."""
    prefix = b"refs/remotes/origin/"
    remote = sorted(r[len(prefix):] for r in repo.refs.keys() if r.startswith(prefix) and r != prefix + b"HEAD")
    if not remote:
        return  # genuinely empty repository
    name = DEFAULT_BRANCH.encode() if DEFAULT_BRANCH.encode() in remote else remote[0]
    repo.refs[b"refs/heads/" + name] = repo.refs[prefix + name]
    repo.refs.set_symbolic_ref(b"HEAD", b"refs/heads/" + name)
    porcelain.reset(repo, "hard", b"refs/heads/" + name)


@_guarded(2, 3)
def push(repo_dir, branch, username, password):
    with Repo(repo_dir) as repo:
        url = remote_url_of(repo)
        if not url:
            raise ValueError("Set a remote URL first.")
        head = _head(repo)
        if head is None:
            raise ValueError("Nothing to push yet. Make a commit first.")
        ref = ("refs/heads/" + branch).encode()
        porcelain.push(repo, url, ref, **_credentials(username, password), **_quiet())
        # Record what the remote now has, so status can count ahead/behind.
        repo.refs[("refs/remotes/origin/" + branch).encode()] = repo.refs[ref]
    return _result(branch=branch)


@_guarded(2, 3)
def pull(repo_dir, branch, username, password):
    """Fetches and fast-forwards. A diverged branch is reported instead of merged."""
    with Repo(repo_dir) as repo:
        url = remote_url_of(repo)
        if not url:
            raise ValueError("Set a remote URL first.")
        before = _head(repo)
        ref = ("refs/heads/" + branch).encode()
        try:
            porcelain.pull(repo, url, [ref], fast_forward=True, ff_only=True,
                           **_credentials(username, password), **_quiet())
        except porcelain.DivergedBranches:
            raise ValueError("Local and remote history have diverged. runcode can only fast-forward; "
                             "push or reset from a computer to reconcile them.")
        after = _head(repo)
        if after is not None:
            repo.refs[("refs/remotes/origin/" + branch).encode()] = after
        new = _count(repo, after, before) if before else (_count(repo, after, None) if after else 0)
    return _result(branch=branch, new_commits=new)
