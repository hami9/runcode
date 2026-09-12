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
(Android Studio writes it for you). The APK is around 50 MB because it ships CPython for
`arm64-v8a` and `x86_64`.

## What actually runs

| Capability | Status |
|---|---|
| CPython 3.12 (Chaquopy) | Real interpreter, standard library, `sqlite3`, `ssl` |
| Static web server | Raw-socket HTTP/1.1 file server |
| SQLite browser | Android platform SQLite |
| Shell | `/system/bin/sh` in the app sandbox — no root, no PTY, no job control |
| pip packages | Not enabled; needs a build-time requirements list in `chaquopy { }` |
| JavaScript / PHP | Not implemented |

Python scripts run as `__main__` with the project directory as the working directory, so
relative paths like `data/tasks.sqlite` resolve the way they would on a desktop.

## MCP bridge

**System → MCP Bridge → Start bridge.** The endpoint is JSON-RPC 2.0 over HTTP:

```
POST http://127.0.0.1:8765/mcp
Authorization: Bearer <token from the System screen>
Content-Type: application/json
```

Twelve tools: `list_projects`, `get_project`, `list_files`, `read_file`, `write_file`,
`start_service`, `stop_service`, `service_status`, `get_logs`, `run_command`, `run_python`,
`sql_query`.

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
  storage/     Project files, atomic writes, zip import/export
  database/    App metadata DB and the project SQLite browser
  security/    Keystore-backed secret vault, log redaction
  ui/          Compose screens
app/src/main/python/
  runcode_runner.py   stdout/stderr bridge, cooperative stop, snippet runner
```

## License

MIT — see [LICENSE](LICENSE).
