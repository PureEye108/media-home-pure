"""Checks a translation file out/<tag>.json against the English sources. Usage: python3 validate.py <tag> [<tag> ...]
Exit code 0 = OK. Prints every problem. Warnings (W) don't fail; errors (E) do."""
import json, re, sys, os
D = os.path.dirname(os.path.abspath(__file__))
APP = json.load(open(os.path.join(D, 'app-en-source.json'), encoding='utf-8'))['app']
REM = json.load(open(os.path.join(D, 'remote-en-source.json'), encoding='utf-8'))
KEEP = ['Media Home', 'Player Pure', 'Brave', 'YouTube', 'VLC', 'HiBy', 'Jellyfin', 'Emby', 'Kodi', 'AirPlay', 'Open-Meteo',
        'PIN', 'QR', 'DAC', 'USB', 'DSD', 'DoP', 'BIT-PERFECT', 'AIza', 'Google', 'Android', '8080',
        'YouTube Data API v3', 'Google Cloud Console', 'SHA-1', 'Smart Switch', 'ADB']

def args_android(s): return sorted(re.findall(r'%\d+\$[sd]|%[sd]', s))
def args_js(s): return sorted(re.findall(r'\{\d\}', s))
def tags(s): return sorted(re.findall(r'</?(?:strong|em|br)\s*/?>', s))
def ents(s): return sorted(re.findall(r'&[a-z]+;', s))

def check(tag):
    errs, warns = [], []
    p = os.path.join(D, 'out', tag + '.json')
    try:
        t = json.load(open(p, encoding='utf-8'))
    except Exception as e:
        return [f'E cannot read {p}: {e}'], []
    app = t.get('app', {}); rem = t.get('remote', {})
    rt, rh, re_ = rem.get('t', {}), rem.get('html', {}), rem.get('e', {})
    for name, src, dst, kind in (('app', APP, app, 'android'), ('remote.t', REM['t'], rt, 'js'),
                                 ('remote.html', REM['html'], rh, 'html'), ('remote.e', REM['e'], re_, 'js')):
        missing = [k for k in src if k not in dst]
        extra = [k for k in dst if k not in src]
        if missing: errs.append(f'E {name}: {len(missing)} missing keys, e.g. {missing[:5]}')
        if extra: errs.append(f'E {name}: {len(extra)} unknown keys, e.g. {extra[:5]}')
        for k, srcval in src.items():
            en = k if name in ('remote.t', 'remote.e') else srcval   # page strings are keyed by their English
            v = dst.get(k)
            if v is None: continue
            if not isinstance(v, str) or not v.strip(): errs.append(f'E {name}[{k!r}]: empty'); continue
            if kind == 'android' and args_android(en) != args_android(v):
                errs.append(f'E {name}[{k}]: placeholders {args_android(en)} != {args_android(v)}')
            if kind in ('js', 'html') and args_js(en) != args_js(v):
                errs.append(f'E {name}[{k!r}]: placeholders {args_js(en)} != {args_js(v)}')
            if kind == 'html':
                if tags(en) != tags(v): errs.append(f'E {name}[{k}]: HTML tags {tags(en)} != {tags(v)}')
                if '<' in re.sub(r'</?(?:strong|em|br)\s*/?>', '', v): errs.append(f'E {name}[{k}]: other HTML tags are not allowed')
            elif '<' in v or '>' in v and '>' not in en:
                errs.append(f'E {name}[{k!r}]: no HTML/angle brackets allowed here')
            if en.count('\n') != v.count('\n'): errs.append(f'E {name}[{k!r}]: keep the same line breaks (\\n) as the English')
            for w in KEEP:
                if w in en and w not in v: errs.append(f'E {name}[{k!r}]: keep "{w}" exactly as in English')
            if v.strip() == en.strip() and len(en.split()) >= 3 and kind != 'html':
                warns.append(f'W {name}[{k!r}]: identical to English')
            if len(en) <= 14 and len(v) > max(26, 2.4 * len(en)):
                warns.append(f'W {name}[{k!r}]: much longer than the English short label ({len(v)} vs {len(en)} chars) - shorten if possible')
    return errs, warns

if __name__ == '__main__':
    bad = False
    for tag in sys.argv[1:]:
        e, w = check(tag)
        print(f'== {tag}: {len(e)} errors, {len(w)} warnings')
        for x in e + w[:40]:
            try: print('  ' + x)
            except BrokenPipeError: sys.exit(1 if e else 0)
        bad = bad or bool(e)
    sys.exit(1 if bad else 0)
