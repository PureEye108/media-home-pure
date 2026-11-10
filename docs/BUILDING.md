# Building Media Home Pure

## Tools

- JDK 17 or newer
- Android SDK: platform 34, build-tools, **NDK 27.0.12077973**, **CMake 3.22.1** (Android Studio › SDK Manager › SDK Tools, or `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`)
- Nothing else: the Gradle wrapper downloads Gradle 8.14.3 (checksum-verified).

## Build

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk  (id ...mediahomepure.debug, installs next to the release)
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew test lint              # unit tests (app + player) and lint
```

On Windows use `gradlew.bat`. The release build is signed with your key from `signing/` (see [signing/README.md](../signing/README.md)),
or with these environment variables (used by CI):

| Variable | Value |
|---|---|
| `MHP_KEYSTORE_FILE` | path to the .jks file |
| `MHP_KEYSTORE_PASSWORD`, `MHP_KEY_ALIAS`, `MHP_KEY_PASSWORD` | its passwords and alias |

Without either, the release APK is signed with the debug key: fine for your own device, but it can't update an official release.

## Project layout

| Path | What |
|---|---|
| `app/` | Media Home: home screen, settings, set-up checklist, screen saver, weather, web remote server (`remote/`) and its page (`src/main/assets/remote.html`). Kotlin, plain Android framework. Package `app.mediahome.tablet`, application id `io.github.pureeye108.mediahomepure`. |
| `player/` | Player Pure as an Android library: library scanner, player, UI (`app.playerpure`), and the C++ audio engine (`src/main/cpp`: decoders, DSD, the USB Audio Class driver over usbfs). Its resources start with `pp_`. |
| `player/hosttest/` | Native tests on the build computer: decoders bit-exact against FFmpeg, DSD/DoP, USB packet timing. `sh make-data.sh && sh run.sh` (needs ffmpeg, gcc). |
| `i18n/` | Translation tables and tools for the 11 languages ([README](../i18n/README.md)). |
| `docs/` | Manual, guides, screenshots. |

## Tests

- `./gradlew test`: unit tests with Robolectric, including **screen renders** of every page at tablet and phone sizes into `app/build/screens` and `player/build/screens`.
- Behind a proxy or mirror, Robolectric's Android jars can come from another Maven repository: `./gradlew test -ProbolectricRepo=https://.../maven2/`.
- The web remote page (`remote.html`) is plain HTML/JS; it talks to the JSON API in `app/.../remote/RemoteApi.kt` (every call needs the `X-Pin` header).

## Your own changes

- Change the `applicationId` in `app/build.gradle.kts` if you publish your own variant, so it doesn't clash with the official app.
- A YouTube key can be built into a **private** build with `signing/youtube.properties` (`youtubeKey=AIza...`). Never publish such an APK.
