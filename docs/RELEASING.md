# Releasing JezLauncher

Pushing a tag like `v1.0.0` builds the signed phone APK on GitHub Actions and attaches it to a GitHub Release with a `SHA256SUMS.txt`. Only the `:app` module is built; there is no watch APK, and the workflow works without a `wear/` directory.

One release key signs everything: the public releases and your own private build.

## 1. One-time setup

### Signing key

The keystore is `jezlauncher-release.jks`, alias `jezlauncher`, created on your own machine. Never commit it (`*.jks` is in `.gitignore`).

If you ever need to create it again:

```
keytool -genkeypair -v -keystore jezlauncher-release.jks -storetype PKCS12 ^
  -alias jezlauncher -keyalg RSA -keysize 4096 -validity 10000
```

PKCS12 keystores use one password for store and key, so `JEZ_STORE_PASSWORD` and `JEZ_KEY_PASSWORD` are the same value. For an old-style JKS keystore they can differ; put the key password in `JEZ_KEY_PASSWORD`.

### Back it up now

**GitHub secrets cannot be read back.** Once you paste them in, the only copy is the one you keep.

- Store `jezlauncher-release.jks` as an attachment in a **password manager**, together with the store password, the key password and the alias.
- Keep a second copy of the password manager database somewhere off this PC.

Android installs an update only if it is signed with the same key. If the key is lost, nobody can update in place.

### Keystore to base64 (PowerShell)

Run in the folder that holds the keystore, or use the full path. It copies one line to the clipboard:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\jezlauncher-release.jks")) | Set-Clipboard
```

Paste it straight into the GitHub secret form below, then copy something else so the key is not left on the clipboard.

### GitHub secrets

Repository → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Value |
|---|---|
| `JEZ_STORE_BASE64` | the base64 text from the clipboard (single line) |
| `JEZ_STORE_PASSWORD` | keystore password |
| `JEZ_KEY_ALIAS` | `jezlauncher` |
| `JEZ_KEY_PASSWORD` | key password (same as the store password for PKCS12) |

With the GitHub CLI instead of the web form: `gh secret set JEZ_STORE_PASSWORD` (it prompts for the value, nothing lands in your shell history). For the base64 one, `Get-Clipboard | gh secret set JEZ_STORE_BASE64`.

The names match the `JEZ_*` Gradle properties in `app/build.gradle`; the workflow passes them as `ORG_GRADLE_PROJECT_*` environment variables, so they never appear on a command line.

### Signing a release build on your own PC

For a local signed `assembleRelease` (your private build), put the same four properties in `%USERPROFILE%\.gradle\gradle.properties`. That file is outside the repo and is read by every Gradle build on this PC:

```
JEZ_STORE_FILE=<full path to jezlauncher-release.jks, forward slashes>
JEZ_STORE_PASSWORD=<your value>
JEZ_KEY_ALIAS=<your value>
JEZ_KEY_PASSWORD=<your value>
```

Fill in the values yourself; they are not written in this document. Without all four, `assembleRelease` produces an unsigned APK (that is what CI-less checks use). The same four properties sign the public build and your private build. Builds with different application IDs install side by side; an update over an installed build needs the same ID and the same key.

Optional: `JEZ_VERSION_NAME` and `JEZ_VERSION_CODE` in the same file override the version (see [Versioning rule](#versioning-rule)).

### Repository settings (recommended)

- Settings → Actions → General → Workflow permissions: **Read repository contents** (the workflows ask for what they need per job).
- Settings → Rules → Rulesets: protect tags `v*` so only you can create them; anyone who can push a tag can trigger a signed build.

## 2. Dry run (do this before the first real tag)

The dry run builds and signs exactly like a release, verifies it, and publishes nothing. It is the first proof that the secrets and the signed path work.

1. Make sure the four secrets above exist.
2. GitHub → **Actions** → **Release** (left list) → **Run workflow**.
3. Branch: the one that holds the workflow (usually `main`). Version: `v0.1.0`. Click **Run workflow**.
4. Open the run → job **Build signed APK**. All steps must be green.
   - *Restore keystore* failing with "Secret JEZ_STORE_BASE64 is not set" means the secret is missing or misnamed.
   - *Collect, verify and checksum* prints the signing certificate SHA-256 and a line `OK ... <applicationId> 0.1.0 (1000)`.
5. Compare the certificate SHA-256 with your keystore:
   ```
   keytool -list -v -keystore C:\path\to\jezlauncher-release.jks -alias jezlauncher
   ```
   The `SHA256:` fingerprint must equal the log's `Signer #1 certificate SHA-256` (the log has no colons).
6. The run's **Artifacts** section holds `release-0.1.0` (the APK, notes and checksums, kept 7 days). Optional: download it and install it on a test phone.
7. The `publish` job is skipped on a dry run. If a release or tag `v0.1.0` appeared, something is wrong.

## 3. Cutting a release

1. Optional: add a section to `CHANGELOG.md` headed `## 1.0.0` (or `## [1.0.0]`).
2. Tag and push:

   ```
   git tag -a v1.0.0 -m "First public release"
   git push origin v1.0.0
   ```

3. Actions → Release builds, then the `publish` job creates the Release `JezLauncher 1.0.0` with:
   - `jezlauncher-1.0.0.apk`
   - `SHA256SUMS.txt` (`sha256sum -c SHA256SUMS.txt`)

Release notes come from the annotated tag message; if it is empty or the tag is lightweight, from the matching `CHANGELOG.md` section; otherwise a one-line default. A source link (GPLv3) is appended.

Re-running a failed publish job is safe: an existing release gets its assets replaced.

### Package name check

The workflow reads `applicationId` from `app/build.gradle` and `verify-apk.sh` fails the build if the APK carries another package name, is unsigned, is debuggable or has the wrong version. Changing the application ID needs no workflow edit.

### Versioning rule

Tag must be exactly `vMAJOR.MINOR.PATCH`.

- `versionName` = `MAJOR.MINOR.PATCH`
- `versionCode` = `MAJOR * 1,000,000 + MINOR * 1,000 + PATCH` (MINOR, PATCH ≤ 999)

`v1.0.0` → 1000000, `v1.2.3` → 1002003. It is deterministic, always grows with the tag, and the workflow refuses a code at or below 113 (upstream Olauncher's last code), so an install can never be pushed below what upstream builds already used. Local builds without the `JEZ_VERSION_*` properties keep the upstream values (113 / `v6.9.17`).

### Rolling back

- Bad release, not yet widely installed: `gh release delete v1.0.0 --cleanup-tag --yes`, fix, tag again.
- Already installed by users: Android will not downgrade. Ship a higher tag (`v1.0.1`) with the fix.
- Never reuse a version code for different bytes.
- Leaked key: rotating means a new key and a new application ID; existing installs cannot be updated in place. Delete the four secrets first.

## Your checklist

1. Put `jezlauncher-release.jks`, passwords and alias into your password manager (before anything else).
2. Create the four GitHub secrets.
3. Run the dry run (`v0.1.0`) and compare the certificate fingerprint.
4. Protect tags `v*`.
5. Tag `v1.0.0` and push.
