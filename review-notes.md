# Review notes

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
- Release-signed build: not created. This change remains [draft PR #1](https://github.com/hami9/runcode/pull/1).

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
