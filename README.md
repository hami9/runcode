# runcode

Native Android local runtime, IDE and service host.

Projects live in the app sandbox, run on an embedded CPython interpreter or a built-in HTTP
file server, and are kept alive by a supervisor with restart policies and a foreground
service. There is a shell, a SQLite browser, backups, and an MCP bridge that lets an AI
client drive the whole thing.

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
- Exports never contain secrets: a secret environment variable is exported only as its
  `${SEC_...}` reference, and the vault stays on the device.

Binary files and files over 512 KB do not open in the editor; they can still be saved out.

## MCP bridge

**System → MCP Bridge → Start bridge.** The endpoint is JSON-RPC 2.0 over HTTP:

```
POST http://127.0.0.1:8765/mcp
Authorization: Bearer <token from the System screen>
Content-Type: application/json
```

Sixteen tools: `list_projects`, `get_project`, `list_files`, `read_file`, `write_file`,
`create_directory`, `rename_path`, `delete_path`, `set_entry_point`, `start_service`,
`stop_service`, `service_status`, `get_logs`, `run_command`, `run_python`, `sql_query`.

File changes made over the bridge show up in the app straight away: the file tree refreshes,
and a file open in the editor reloads unless it has unsaved edits.

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

### Security

The bridge exposes a shell, arbitrary Python and read/write file access on the device.

- A bearer token is required on every call, including on loopback. It is generated on first
  launch and stored in an Android Keystore-backed vault; **New token** rotates it.
- The listener binds `127.0.0.1` unless you turn on **Expose on local network**, which makes
  anyone on the same Wi-Fi with the token able to run commands on the device. Prefer
  `adb forward` or a tunnel.
- Secret-valued environment variables are returned as `<secret>`, never echoed back.
- `GET /health` is the only unauthenticated route and reports nothing about the device.
- The bridge lives in the app process. It holds a foreground service while online, but it
  does not survive the process being killed — restart it from the System screen.

## Layout

```
app/src/main/java/com/runcode/app/
  runtime/     PythonEngine (Chaquopy), StaticWebEngine, engine contracts
  supervisor/  ServiceSupervisor — lifecycle, restart policy, ports, wake lock
  terminal/    TerminalSession — interactive sh plus one-shot command runner
  mcp/         McpServer (JSON-RPC over HTTP), McpTools, McpToolHost
  storage/     Path-checked project files, zip import/export, entry-point tracking
  database/    App metadata DB and the project SQLite browser
  security/    Keystore-backed secret vault, log redaction
  ui/          Compose screens
app/src/main/python/
  runcode_runner.py   stdout/stderr bridge, cooperative stop, snippet runner
```

## License

runcode itself is MIT — see [LICENSE](LICENSE).

The APK embeds CPython, OpenSSL, SQLite, Chaquopy and the AndroidX/Compose stack. Their
licences and the required notices are in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
