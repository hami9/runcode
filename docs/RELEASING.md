# Publishing runcode Android releases

`.github/workflows/release.yml` automatically publishes a **signed** APK when
`app/build.gradle.kts` (versionName) or the release workflow changes on `main`.
You can also use **Actions -> Android Release -> Run workflow**.

## Signing secrets

Open **Settings -> Secrets and variables -> Actions -> New repository secret**.
Set these values:

- `RUNCODE_KEYSTORE_BASE64`: base64 encoding of the **same release keystore** used for v1.4.0.
- `RUNCODE_KEYSTORE_PASSWORD`: keystore password.
- `RUNCODE_KEY_ALIAS`: alias of its signing key.
- `RUNCODE_KEY_PASSWORD` (optional): signing key password; defaults to keystore password.

For example, on Linux/macOS:

```sh
base64 -w 0 path/to/release.keystore
```

On macOS, which does not support `-w`, use
`base64 < path/to/release.keystore | tr -d '\n'`.
On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\\path\\to\\release.keystore"))
```

**Never commit the keystore, its base64 encoding, or any signing password to Git.**
Store an offline backup of the signing key. GitHub Actions does not make private
keys available for retrieval once entered as secrets.

## Validation and downloads

Before publishing, CI runs Python tests and Android JVM/lint checks,
builds the signed release, and verifies the APK using `apksigner`.
For v1.5.0, it additionally compares the signing certificate SHA-256 digest with
the existing v1.4.0 release, to prevent incompatible upgrades.

Successful runs provide:
- `runcode-vX.Y.Z.apk` and `SHA256SUMS.txt` on the GitHub Release page.
- The same files as GitHub Actions artifacts (30-day retention).

If signing secrets are missing, the workflow deliberately fails instead
of publishing an unsigned or debug APK as a production release. The normal
`Check` workflow still provides a debug APK artifact for testing. A debug
APK signed with a different key may not install over a previous release.

For each new version, bump `versionName` and `versionCode` together before
merging to main. Existing version tags/releases are never overwritten.
