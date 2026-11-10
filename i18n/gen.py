import json, os, re, sys
from xml.sax.saxutils import escape
D = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(D, '..', 'app', 'src', 'main', 'res'); ASSETS = os.path.join(D, '..', 'app', 'src', 'main', 'assets', 'i18n')
DIRS = {'vi': 'values-vi', 'es': 'values-es', 'zh-CN': 'values-zh-rCN', 'hi': 'values-hi', 'fr': 'values-fr', 'de': 'values-de',
        'ja': 'values-ja', 'ko': 'values-ko', 'pt-BR': 'values-pt', 'ru': 'values-ru'}
ASSET = {'zh-CN': 'remote-zh-CN.json', 'pt-BR': 'remote-pt.json'}
PLURALS = {
 'vi': [('other', '%d phút')], 'zh-CN': [('other', '%d 分钟')], 'ja': [('other', '%d 分')], 'ko': [('other', '%d분')],
 'es': [('one', '%d minuto'), ('many', '%d minutos'), ('other', '%d minutos')],
 'pt-BR': [('one', '%d minuto'), ('many', '%d minutos'), ('other', '%d minutos')],
 'fr': [('one', '%d minute'), ('many', '%d minutes'), ('other', '%d minutes')],
 'de': [('one', '%d Minute'), ('other', '%d Minuten')], 'hi': [('one', '%d मिनट'), ('other', '%d मिनट')],
 'ru': [('one', '%d минута'), ('few', '%d минуты'), ('many', '%d минут'), ('other', '%d минуты')],
}
SRC = json.load(open(os.path.join(D, 'app-en-source.json'), encoding='utf-8'))['app']
def android(v):
    v = escape(v)                                  # & < >
    v = v.replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n')
    if v[:1] in '@?': v = '\\' + v
    return v
for tag, d in DIRS.items():
    t = json.load(open(os.path.join(D, 'out', tag + '.json'), encoding='utf-8'))
    app = t['app']
    for k, en in SRC.items():   # same backslash escapes as the English (e.g. docs\\07)
        assert en.count('\\') == app[k].count('\\'), (tag, k)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- Media Home in {tag}: generated from the translation tables (Media Home Pure). -->', '<resources>']
    for k in SRC:
        lines.append(f'    <string name="{k}">{android(app[k])}</string>')
    lines.append('    <plurals name="minutes">')
    for q, txt in PLURALS[tag]: lines.append(f'        <item quantity="{q}">{android(txt)}</item>')
    lines.append('    </plurals>')
    lines.append('</resources>')
    os.makedirs(os.path.join(RES, d), exist_ok=True)
    open(os.path.join(RES, d, 'strings.xml'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    r = t['remote']; words = {'t': dict(r['t'], **r['html']), 'e': r['e']}
    os.makedirs(ASSETS, exist_ok=True)
    open(os.path.join(ASSETS, ASSET.get(tag, f'remote-{tag}.json')), 'w', encoding='utf-8').write(json.dumps(words, ensure_ascii=False, separators=(',', ':')))
print('generated', len(DIRS), 'languages')
