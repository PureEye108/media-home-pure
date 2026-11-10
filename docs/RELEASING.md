# Releasing (maintainer notes)

## Each release

1. Set `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; add a section `## x.y.z (date)` to `CHANGELOG.md`.
2. `./gradlew test lint assembleRelease` with the release key in `signing/`.
3. Check the signer: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` must show
   SHA-256 `11663fd6a8c4fc575ce3bdac382deaeaa9997a3d57b218ac4cfaea1282f66724`.
4. Rename it `MediaHomePure-x.y.z.apk`, make `MediaHomePure-x.y.z.apk.sha256` (`sha256sum` or `certutil -hashfile ... SHA256`).
5. GitHub › Releases › **Draft a new release** › tag `vx.y.z` › attach both files › paste the CHANGELOG section › **Publish**.

Or let GitHub Actions do steps 2-5: add the secrets below once, then push a tag (`git tag v1.0.1 && git push origin v1.0.1`).
The workflow `.github/workflows/release.yml` refuses to publish an APK signed with any other key.

| Secret | Value |
|---|---|
| `MHP_KEYSTORE_BASE64` | the .jks file in base64 (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("mediahomepure-release.jks"))`) |
| `MHP_KEYSTORE_PASSWORD` | storePassword from keystore.properties |
| `MHP_KEY_ALIAS` | `mediahomepure` |
| `MHP_KEY_PASSWORD` | keyPassword from keystore.properties |

## The release key

- Keep `mediahomepure-release.jks` and its passwords private, with a backup off the computer. If it is lost, no update can install over earlier
  releases; if it leaks, someone else could publish "updates".
- Certificate SHA-256 `11:66:3F:D6:A8:C4:FC:57:5C:E3:BD:AC:38:2D:EA:EA:A9:99:7A:3D:57:B2:18:AC:4C:FA:EA:12:82:F6:67:24`,
  SHA-1 `57:37:B4:31:04:38:DB:38:EF:E2:97:A0:3C:33:07:E1:A3:35:F4:89` (users need the SHA-1 to restrict their YouTube key).

## Android developer verification

Google requires apps on certified Android devices to come from registered developers: from 30 September 2026 in Brazil, Indonesia,
Singapore and Thailand, worldwide in 2027. To let users there install without Android's "advanced flow", register in the
[Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console):
register the package name `io.github.pureeye108.mediahomepure` with the certificate SHA-256 above and prove ownership with an APK signed
with the release key. See also [open-source app registration](https://developer.android.com/developer-verification/guides/open-source-app-registration).
