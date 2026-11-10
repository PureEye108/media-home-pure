# signing/ (private files, ignored by git)

Everything in this folder except this README is ignored by git (see `.gitignore`). Never commit or upload it.

| File | What it is |
|---|---|
| `keystore.properties` | `storeFile=<name>.jks`, `storePassword=...`, `keyAlias=...`, `keyPassword=...` |
| `<name>.jks` | the release signing key. Keep a backup: without it, updates can't be installed over your earlier release. |
| `youtube.properties` | optional, **private builds only**: `youtubeKey=AIza...` builds your own YouTube Data API key into the APK. Never publish an APK that contains your key. |

Without `keystore.properties`, release builds are signed with the Android debug key (fine for testing).
CI can sign instead with environment variables, see [docs/BUILDING.md](../docs/BUILDING.md).

Make your own key once:

```
keytool -genkeypair -keystore signing/release.jks -storetype PKCS12 -alias release -keyalg RSA -keysize 4096 -validity 11000
```
