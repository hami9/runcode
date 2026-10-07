# Review notes

## v1.5.0 (versionCode 6) — in progress on `ccr-00c06d8d-qqvzlg`

Roadmap phases 3 (diagnostics), 4 (backups to a chosen folder), 5 (git) and 6 (debugger), plus
a public URL for the MCP bridge.

| Change | Where |
| --- | --- |
| LAN address prefers Wi-Fi, hotspot and Ethernet; mobile data and VPN interfaces are skipped. | `PortManager.getLanIp` |
| **Public URL**: outbound SSH reverse tunnel (Pinggy on 443, localhost.run fallback) with backoff and reconnect on network change. Works behind NAT and with a VPN on. | `mcp/McpTunnel`, `mcp/SshTunnelConnector` |
| **Diagnostics** card and `run_diagnostics`: app, network, service ports, bridge self-test. **Share logs** through a FileProvider. | `diagnostics/`, System screen |
| **Backup folder** through the Storage Access Framework, export verified by read-back hash, restore from the folder, optional daily run keeping 7 automatic copies. `backup_project` MCP tool. | `backup/`, Backups screen |
| Backup manifests are built with `JSONObject`; a quote in a project name broke restore. | `BackupManager` |
| `serverInfo.version` reports the app version instead of `1.0.0`. | `McpServer` |
| **Git** (phase 5) on dulwich: Git screen, clone into a project, token only sent to GitHub over HTTPS. `git_*` MCP tools. | `git/`, `runcode_git.py` |
| **Debugger** (phase 6) on bdb: breakpoints in the gutter, step over/into/out, variables, call stack, eval. Debug runs are never auto-restarted. `debug_*` MCP tools. | `runcode_debugger.py`, `runtime/PythonDebugger`, Editor |

Validation in this environment (no Android device or KVM available):

- 87 JVM/Robolectric tests and 5 Python tests pass; `testDebugUnitTest lintDebug assembleDebug`
  succeeds with 0 lint errors and the same 38 warnings as v1.4.0.
- The tunnel runs the real JSch client against an in-process SSH server that behaves like
  Pinggy, including an HTTP request through the forward. A JSch race in `channel.connect(timeout)`
  failed about 2% of opens; the fix took a 150-run stress loop from 3 failures to 0.
- The SAF store runs against a real `DocumentsProvider` under Robolectric.
- The diagnostics runner runs against the real application object with live DNS, HTTPS and
  Telegram probes.

Not yet verified:

- On a device: the Pinggy relay itself, the folder picker with Google Drive and OneDrive, the
  share sheet, the diagnostics card on a 360dp screen, and notification/battery checks.
- Release signing: `assembleRelease` with the release key and `apksigner verify`.

## v1.4.0

Baseline: `929cc23` on `main`. This pass covers the editor, runtime lifecycle, project storage,
secrets and MCP, then implements the project-settings step from the supplied roadmap.

## Fixes

| Problem | Change |
| --- | --- |
| Switching projects could save an old buffer to the new project or discard it. | Save before changing ownership, serialize editor transitions, clear old tabs and keep the buffer when saving fails. |
| Each restart reset the crash counter. Stop during backoff did not cancel the pending restart. | Carry the retry count across starts, cancel pending jobs and include them in Stop All. |
| Python was marked stopped after three seconds even when its thread was blocked. | Keep the handle and STOPPING state until the worker exits; refuse duplicate starts and premature deletion. |
| Python services and snippets shared working directory and environment variables. | Admit one Python workload at a time and restore environment, arguments, import paths and working directory after execution. Static web services remain concurrent. |
| Imported manifests could select an entry point outside source/ or reuse another project's vault reference. | Validate the entry point and require fresh values for imported secrets. |
| Telegram and Python HTTP templates printed simulated activity. | Ship an actual Telegram polling bot and an HTTP JSON server for new projects. Existing scripts remain unchanged. |
| Replacing an editor file deleted the old file before the replacement was ready. | Use a filesystem replacement operation without pre-deleting the target. |

## Project settings

- Projects has a settings dialog for environment variables, port, restart policy, boot startup
  and CPU/heap/idle limits. A service must be stopped before saving.
- Secret values go to the Keystore-backed vault under separate project keys. Metadata stores
  references only. Failed metadata saves roll back new vault entries; cancellation during a
  commit cannot remove referenced entries. Replacing or removing a secret cleans up its old key.
- Existing secrets are never loaded into form fields. The dialog uses Android's secure window
  flag. Known vault values are redacted from logs, crash reports and MCP text results.
- `update_project_settings` updates plain variables and settings. It preserves existing secrets
  and rejects attempts to replace them. Changes notify the app's project view.

## Validation

- Five Python regression tests pass locally and on GitHub Actions, including an actual loopback
  HTTP request, cancellation, early stop and concurrent-workload rejection.
- All 32 JVM/Robolectric regression tests pass. They cover settings and vault rollback,
  editor ownership, restart and stop behavior, archive boundaries, redaction and MCP settings.
- `testDebugUnitTest lintDebug assembleDebug` passes locally on code commit `af2da7f`.
  Lint reports zero errors and 38 warnings, mainly dependency updates and unused resources.
- The debug APK is built and its Android Debug signature is verified. Its SHA-256 is
  `79ec58edc23d6025d525ef6015dab79027e82e3c9580eb9409b091675481c44a`.
  Local output: `app/build/outputs/apk/debug/app-debug.apk`.
- API 36 UI, Android Keystore and physical-device validation: pending; no device was connected.
- These results describe the review build in [PR #1](https://github.com/hami9/runcode/pull/1).
  Release build validation is recorded separately in the GitHub release notes.

## Remaining work

- Complete the device checks before calling the settings phase released: save and replace a
  secret, restart the app, run a project, verify redacted logs, test settings through MCP and
  exercise editor switching and delayed stops.
- A real Telegram round trip needs a user-managed bot token and has not been tested.
- Multiple Python services need separate runtime processes. The embedded interpreter still
  shares imported modules and app privileges; project scripts and MCP clients are trusted code,
  not isolated tenants. Child threads started by user scripts are not isolated by the run gate.
- Exported project files are copied as written. The vault is excluded, but source/data files
  can still contain secrets placed there by user code.
- Diagnostics, SAF backups, on-device Git and the Python debugger remain later roadmap phases.
