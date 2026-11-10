# Translations

Media Home Pure and its phone remote in English + 10 languages: vi, es, zh-CN, hi, fr, de, ja, ko, pt-BR, ru.
The translations were written with AI help (Claude) and checked by `validate.py` (placeholders, tags, names), not yet by native speakers: corrections are welcome. Player Pure's own screens are English only for now.

| File | What |
|---|---|
| `app-en-source.json` | the English Android strings (from `app/src/main/res/values/strings.xml`) with context notes |
| `remote-en-source.json` | the English words of the phone remote (`assets/remote.html`): page text, tips (HTML), and messages from the tablet |
| `out/<lang>.json` | the translations: `{"app": {...}, "remote": {"t": {...}, "html": {...}, "e": {...}}}` |
| `validate.py` | checks a translation: every key, placeholders `%1$s` / `{0}`, HTML tags, product names and file names kept |
| `gen.py` | writes `res/values-<lang>/strings.xml` and `assets/i18n/remote-<lang>.json` from `out/` |
| `extract_remote.py` | rebuilds `remote-en-source.json` from `remote.html` after the page changes |

To change a text: edit the English, re-run `extract_remote.py` if the page changed, update `out/<lang>.json`,
then `python3 validate.py vi es zh-CN hi fr de ja ko pt-BR ru` and `python3 gen.py`, and rebuild.
