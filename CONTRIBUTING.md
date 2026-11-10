# Contributing

Thank you! Bug reports, translations and pull requests are welcome.

- **Bugs**: open an issue with your device model, Android version, app version (Settings › About) and the steps. For USB DAC problems,
  attach Player Pure's DAC report (Player Pure › DAC › Save DAC report; it is saved in Download/PlayerPure).
- **Translations**: the texts live in `i18n/out/<language>.json` (see [i18n/README.md](i18n/README.md)). Run
  `python3 i18n/validate.py <language>` and `python3 i18n/gen.py`, then build. Native-speaker corrections are especially welcome.
- **Code**: Kotlin, plain Android framework (no AndroidX), C++ for the audio engine. Keep it dependency-free where you can.
  Run `./gradlew test lint` before a pull request. Screen renders land in `app/build/screens` and `player/build/screens`.
- **Never** commit a signing key or an API key (`signing/` is ignored by git for that reason).

By contributing you agree that your contribution is licensed under the MIT licence of this project.
