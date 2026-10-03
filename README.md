# runcode

Native Android local runtime, IDE and service host.

Projects live in the app sandbox, run on an embedded CPython interpreter or a built-in HTTP
file server, and are kept alive by a supervisor with restart policies and a foreground
service. There is a shell, a SQLite browser, backups, and an MCP bridge that lets an AI
client drive the whole thing.

## Screenshots

Taken on a Galaxy S9 (Android 10, arm64-v8a) running the release build.

| Home | Editor and console | File manager |
|---|---|---|
| <img src="docs/screenshots/home.png" width="240" alt="Home screen with project stats and starter profiles"> | <img src="docs/screenshots/editor.png" width="240" alt="Editor running a Python script with its output in the console"> | <img src="docs/screenshots/file-manager.png" width="240" alt="File manager showing the whole project tree"> |

| SQLite browser | Shell |
|---|---|
| <img src="docs/screenshots/database.png" width="240" alt="SQLite browser listing tables and query results"> | <img src="docs/screenshots/shell.png" width="240" alt="Shell session in the app sandbox"> |

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 21 and the Android SDK (compileSdk 36). Put your SDK path in `local.properties`
(Android Studio writes it for you).

Third-party Python packages are resolved by pip **at build time**, which needs a local
CPython 3.12 on the build machine. If it is not on `PATH`, point at it:

```bash
RUNCODE_BUILD_PYTHON=/path/to/python3.12 ./gradlew assembleDebug
```

The APK is large — CPython plus the bundled wheels, for `arm64-v8a` and `x86_64`. Trim
`abiFilters` to one ABI to roughly halve it.

## What actually runs

| Capability | Status |
|---|---|
| CPython 3.12 (Chaquopy) | Real interpreter, standard library, `sqlite3`, `ssl` |
| Static web server | Raw-socket HTTP/1.1 file server |
| SQLite browser | Android platform SQLite |
| Shell | `/system/bin/sh` in the app sandbox — no root, no PTY, no job control |
| Bundled packages | `python-telegram-bot` 21.9, `requests` — add more to the `pip` block in `app/build.gradle.kts` |
| JavaScript / PHP | Not implemented |

Python scripts run as `__main__` with the project directory as the working directory, so
relative paths like `data/tasks.sqlite` resolve the way they would on a desktop.

Only one Python workload (service or MCP snippet) runs at a time. CPython shares environment,
working directory and imports across threads; concurrent scripts previously mixed project data
and secrets. Static web services can still run alongside Python. Projects and MCP clients are
trusted app-level code, not isolated tenants.

## Files

**Editor → folder icon** opens the project's file manager. It shows the whole project, not
just `source/`, so whatever a script writes into `data/` is visible too.

- Create files and folders (`utils/helpers.py` creates the folder as well), rename, move and
  delete. `source/`, `data/`, `config/`, `logs/`, `cache/` and `backups/` themselves are
  part of the layout and cannot be renamed or deleted; their contents can.
- Renaming or moving the entry point takes the project with it. **⋮ → Set as entry point**
  picks a different file.
- **Import files here** copies files in from the phone through the system picker; an existing
  file is never overwritten. **Save a copy to phone** does the reverse, for any file,
  including databases and other binaries.
- **⋮ → Export project (.zip)** writes the code, data and settings to a zip.
  **Projects → import icon** brings a zip back as a new project. Any other zip works too — a
  GitHub "Download ZIP" becomes a Python project with its entry point detected. Imports are
  checked for paths that escape the project and capped in size; imported projects never
  start on boot until you turn that on.
- Secret environment values are excluded from the manifest: a secret variable is exported only as its
  `${SEC_...}` reference, and the vault stays on the device.

Binary files and files over 512 KB do not open in the editor; they can still be saved out.

## Debugger

**Editor → bug icon** runs the project's entry point under a Python debugger.

- Tap a line number in a `.py` file under `source/` to set or clear a breakpoint (a red dot).
  Breakpoints can be changed while the program runs.
- When execution reaches one, the line is highlighted, the file opens if needed, and the
  debug panel shows locals, user globals and the call stack.
- **Continue**, **Over** (next line), **Into** (into a call), **Out** (finish the function) and
  **Stop**. **Eval** evaluates an expression in the paused frame. An expression still running
  after 10 seconds, or when Stop is pressed, is interrupted.
- Stepping stays in the project's own files; library code runs without stopping.

It is built on `bdb`, the base of `pdb`, and its trace hook exists only during a debug run, so
normal runs keep their full speed. Only the script's main thread is debugged. Like any Python
trace hook it acts between lines: a script blocked inside a C call, such as `time.sleep` or a
socket read, pauses on its next line. A debug run is never restarted automatically, whatever
the project's restart policy.

## Git

**Git** works on the selected project's `source/` folder, so `data/`, logs and secrets never
end up in commits. It runs on [dulwich](https://www.dulwich.io), a pure-Python git bundled with
the app, because a native git binary cannot be shipped or executed on Android.

- **Initialise repository** creates one on `main` with a `.gitignore` for Python caches.
- The screen lists staged, changed and new files; commit with or without staging everything
  first, and see the last 30 commits.
- **Branch** switches or creates branches. Switching refuses to overwrite uncommitted changes.
- **Remote**, **Push** and **Pull** work against GitHub or any smart-HTTP git server. Pull only
  fast-forwards: when histories have diverged it says so instead of merging.
- **Clone** creates a new project from a URL, with the entry point detected as for a zip.
- **Settings** holds the author name and email (GitHub matches commits to accounts by email)
  and a GitHub token. Use a fine-grained token with *Contents: read and write*. It is kept in
  the Keystore-backed vault, never shown again, scrubbed from errors and never written into the
  repository's config. It is only sent to `https://github.com`; other remotes, and GitHub over
  plain HTTP, get no credentials.

Git operations that rewrite files refuse while the editor has unsaved changes. SSH remotes
are not supported; use HTTPS URLs.

## Backups

**Backups** creates checksummed archives of a project's `source/` and `data/`. Secrets, logs
and caches are never included, and the checksum is verified before every restore.

**Choose folder** keeps backups outside the app as well. The system folder picker accepts
device storage, a memory card, or a cloud app that offers folders, such as Google Drive or
OneDrive, with no account or API key in runcode. **Back up to folder** writes a fresh backup,
reads the copy back and compares its SHA-256; a copy that does not match is removed. Backups
already in the folder can be verified and restored from the same screen, including on another
device.

**Daily automatic backup** is off by default. When on, it backs up every project once a day
while runcode is running, keeps the last 7 automatic copies per project and never deletes
manual ones.

## Diagnostics

**System → Diagnostics → Run** checks the device and the network and produces a report to copy
or share:

- App: Android version and ABI, free storage and memory, notification permission, battery
  optimisation, the Python interpreter, whether the foreground service really holds running
  work, and the last crash.
- Network: connection type and validation, VPN, LAN address, reserved ports, DNS, HTTPS, and
  whether the Telegram API is reachable (bots need it; some networks block it).
- Services: whether each running static site or Python HTTP API accepts connections on its
  port. Other project types do not listen on a port and are not judged by one.
- MCP bridge: a `GET /health` self-test, and the public tunnel's state.

**Share logs** sends the latest report and every log line through the share sheet. Secrets
were already redacted when each line was logged.

## Project settings

Open **Projects → settings icon** to edit the port, restart policy, start on boot, CPU/heap/idle
limits and environment variables. Stop the service before saving. Use **Secret** for passwords
and bot tokens: values go to the Android Keystore-backed vault, while project metadata keeps
only a reference. Leave an existing secret blank to keep it, or enter a replacement.

For a new Telegram bot, set the secret `TELEGRAM_BOT_TOKEN`, save, run, and send `/start` to the
bot. New Python HTTP projects serve `/`, `/status` and `/api` on the configured port. Existing
project files are not rewritten when the app updates.

CPU limits measure the service thread. The Java heap limit measures the whole app, not a
single Python service's allocations. Idle limits apply to the static web server. Python can
remain **STOPPING** while blocked in native code; another instance cannot start until it exits.

Imported secret variables need fresh values on this device, even when reimporting an export.
User code can write sensitive data to files; mark variables correctly and inspect files before
sharing an archive. The vault cannot sanitize arbitrary project files.

## MCP bridge

**System → MCP Bridge → Start bridge.** The endpoint is JSON-RPC 2.0 over HTTP:

```
POST http://127.0.0.1:8765/mcp
Authorization: Bearer <token from the System screen>
Content-Type: application/json
```

Twenty-seven tools: `list_projects`, `get_project`, `update_project_settings`, `list_files`, `read_file`, `write_file`,
`create_directory`, `rename_path`, `delete_path`, `set_entry_point`, `start_service`,
`stop_service`, `service_status`, `get_logs`, `run_command`, `run_python`, `sql_query`,
`run_diagnostics`, `backup_project`, `git_status`, `git_commit`, `git_push`, `git_pull`,
`debug_start`, `debug_control`, `debug_status`, `debug_eval`.

`debug_start` and `debug_control` wait (up to `wait_ms`) for the program to pause or end, and
return the state: file, line, call stack and variables.

File changes made over the bridge show up in the app straight away: the file tree refreshes,
and a file open in the editor reloads unless it has unsaved edits.

`update_project_settings` accepts optional `port`, `restart_policy`, `start_on_boot`,
`max_cpu_percent`, `max_heap_mb`, `idle_timeout_minutes`, and an `environment` object of plain
string values. Providing `environment` replaces plain variables and preserves existing secrets.
Secret variables can only be edited in the app's settings form.

### Connecting from a computer

The bridge binds loopback by default. Forward the port over adb:

```bash
adb forward tcp:8765 tcp:8765
```

Then point any MCP client at `http://127.0.0.1:8765/mcp` with the bearer token. Quick check:

```bash
curl -s -X POST http://127.0.0.1:8765/mcp \
  -H "Authorization: Bearer $RUNCODE_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Connecting from anywhere

Turn on **Public URL (internet)** under the bridge. The app opens an outbound SSH reverse
tunnel and shows an `https://…/mcp` endpoint with a **Copy** button. No port forwarding,
public IP or extra app is needed, and it works behind carrier NAT and with a VPN on: the
connection starts on the phone, so Android sends it through the VPN like any other traffic.

- The relay is [Pinggy](https://pinggy.io) over SSH on port 443, with
  [localhost.run](https://localhost.run) on port 22 as a fallback.
- A dropped tunnel reconnects with backoff (2 s up to 30 s). Switching between Wi-Fi and
  mobile data, or a VPN reconnecting, reconnects it straight away.
- Free relays hand out a new URL on every connection and Pinggy's free tunnels last 60
  minutes, so copy the endpoint again after a reconnect.
- If it keeps retrying with a VPN on, check that runcode is not excluded from the VPN
  (split tunneling).

```bash
claude mcp add --transport http runcode https://<id>.run.pinggy-free.link/mcp \
  --header "Authorization: Bearer $RUNCODE_TOKEN"
```

### Security

The bridge exposes a shell, arbitrary Python and read/write file access on the device.

- A bearer token is required on every call, including on loopback. It is generated on first
  launch and stored in an Android Keystore-backed vault; **New token** rotates it.
- The listener binds `127.0.0.1` unless you turn on **Expose on local network**, which makes
  anyone on the same Wi-Fi with the token able to run commands on the device. Prefer
  `adb forward` or a tunnel.
- **Public URL** makes the bridge reachable from the whole internet for anyone with both the
  URL and the token. The relay terminates HTTPS, so it can read requests, including the
  token. Turn it off when you are done and rotate the token after sharing it.
- Secret-valued environment variables are returned as `<secret>`, never echoed back.
- Registered vault values are redacted from logs and text tool results. This is not an
  isolation boundary: MCP's arbitrary code tools and project scripts run with app privileges.
- `GET /health` is the only unauthenticated route and reports nothing about the device.
- The bridge lives in the app process. It holds a foreground service while online, but it
  does not survive the process being killed — restart it from the System screen.

## Layout

```
app/src/main/java/com/runcode/app/
  runtime/     PythonEngine (Chaquopy), StaticWebEngine, engine contracts
  supervisor/  ServiceSupervisor — lifecycle, restart policy, ports, wake lock
  git/         GitManager over runcode_git.py (dulwich); token in the vault
  backup/      Checksummed archives, backups to a user-chosen folder (SAF), daily runs
  diagnostics/ Device and network checks behind the System screen and run_diagnostics
  terminal/    TerminalSession — interactive sh plus one-shot command runner
  mcp/         McpServer (JSON-RPC over HTTP), McpTools, McpToolHost, McpTunnel (public URL)
  storage/     Path-checked project files, zip import/export, entry-point tracking
  database/    App metadata DB and the project SQLite browser
  security/    Keystore-backed secret vault, log redaction
  ui/          Compose screens
app/src/main/python/
  runcode_runner.py   stdout/stderr bridge, cooperative stop, snippet runner
  runcode_git.py      git on dulwich, JSON in and out, credentials scrubbed
  runcode_debugger.py debugger on bdb: breakpoints, stepping, eval in the paused frame
```

## Tests

```bash
python -m pip install dulwich==1.2.15
python -m unittest discover -s tests -v
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Git tests in both suites run the real `runcode_git.py`. The Kotlin ones use the Python named by
`RUNCODE_TEST_PYTHON` or `RUNCODE_BUILD_PYTHON`, or `python3`, and are skipped when none of
them can import dulwich.

The JVM tests cover settings validation, vault rollback, secret handling, archive paths,
editor ownership, supervisor restarts, diagnostics rules and probes, folder backups through a
real `DocumentsProvider`, and the public tunnel against an in-process SSH server. Robolectric tests use API 28; device testing is
still needed for Keystore, Compose interaction and foreground-service behavior on API 36.

## License

runcode itself is MIT — see [LICENSE](LICENSE).

The APK embeds CPython, OpenSSL, SQLite, Chaquopy and the AndroidX/Compose stack. Their
licences and the required notices are in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
