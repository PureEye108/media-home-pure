# Third-party notices

Media Home Pure is MIT-licensed (see [LICENSE](LICENSE)). It includes or uses the following third-party work.

| Component | Where | Licence |
|---|---|---|
| dr_flac, dr_wav by David Reid ([dr_libs](https://github.com/mackron/dr_libs)) | `player/src/main/cpp/third_party/dr_flac.h`, `dr_wav.h` | Public domain (Unlicense) or MIT No Attribution, your choice (text at the end of each file) |
| Apple Lossless (ALAC) decoder, Copyright (c) 2011 Apple Inc. ([alac](https://github.com/macosforge/alac)) | `player/src/main/cpp/third_party/alac/` | Apache License 2.0 ([LICENSE](player/src/main/cpp/third_party/alac/LICENSE)) |
| ZXing "core" 3.5.3 (QR codes) | Maven Central `com.google.zxing:core` | Apache License 2.0 |
| Kotlin standard library | Maven Central `org.jetbrains.kotlin:kotlin-stdlib` | Apache License 2.0 |
| Material icons (Google), redrawn as Android vector drawables | `app/src/main/res/drawable/`, `player/src/main/res/drawable/` | Apache License 2.0 |

Test-only (not in the APK): JUnit 4 (EPL 1.0), Robolectric (MIT).

## Services the app talks to

- **Open-Meteo** ([open-meteo.com](https://open-meteo.com/)): weather forecast and city search. Free for non-commercial use, no account or key.
  Weather data by Open-Meteo.com, licensed CC BY 4.0. The app sends only coordinates rounded to about 1 km.
- **YouTube Data API v3** (Google): only if you add your own API key, and only for the searches you make from the web remote.
  Use of the API is subject to the YouTube API Services Terms of Service and Google's Privacy Policy.

Android, Google Play, YouTube and other product names are trademarks of their owners. This project is not affiliated with or endorsed by them.
