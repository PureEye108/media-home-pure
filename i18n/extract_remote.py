"""Builds the English source list for the web remote page: page text, data-i18n-html blocks, script strings, server messages."""
import re, json, sys
from html.parser import HTMLParser
SRC = sys.argv[1]; OUT = sys.argv[2]
s = open(SRC, encoding='utf-8').read()
body = s[s.index('<body>'):s.index('<script>\n(function')]
js = s[s.index('<script>\n(function'):s.rindex('</script>')]

SKIP = {'Media Home', 'YouTube', 'Jellyfin', 'Emby', 'AirPlay', 'Kodi', 'Player Pure', 'DAC', 'QR', '0:00', '0', '100', '-', '+',
        '−1 dB', '+1 dB', 'BIT-PERFECT', 'USB', 'Media Home Remote', '−', 'AIza...'}
VOID = {'br', 'img', 'input', 'hr', 'meta', 'link', 'source', 'wbr'}
def norm(t): return re.sub(r'\s+', ' ', t.strip())
texts = {}   # key -> context note
html_blocks = {}
class P(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True); self.stack = []; self.html_key = None; self.depth = 0; self.raw = None
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if self.html_key is not None:
            if tag not in VOID: self.depth += 1
            return
        if 'data-i18n-html' in a:
            self.html_key = a['data-i18n-html']; self.depth = 0; return
        if tag in VOID:
            for at in ('placeholder', 'aria-label', 'title'):
                v = a.get(at)
                if v and norm(v) not in SKIP and re.search('[A-Za-z]{2}', v): texts.setdefault(norm(v), f'{at} of <{tag} id={a.get("id","")}>')
            return
        for at in ('placeholder', 'aria-label', 'title'):
            v = a.get(at)
            if v and norm(v) not in SKIP and re.search('[A-Za-z]{2}', v): texts.setdefault(norm(v), f'{at} of <{tag} id={a.get("id","")}>')
        self.stack.append((tag, a.get('id', '')))
    def handle_endtag(self, tag):
        if self.html_key is not None:
            if self.depth == 0: self.html_key = None
            else: self.depth -= 1
            return
        if self.stack: self.stack.pop()
    def handle_data(self, d):
        if self.html_key is not None: return
        t = norm(d)
        if not t or t in SKIP or not re.search('[A-Za-z]{2}', t): return
        ctx = ' > '.join(f'{t_}#{i}' if i else t_ for t_, i in self.stack[-3:])
        texts.setdefault(t, 'page text in ' + ctx)
P().feed(body)
# data-i18n-html blocks: raw inner HTML from the source
for m in re.finditer(r'data-i18n-html="([^"]+)">(.*?)</(div|p)>', body, re.S):
    html_blocks[m.group(1)] = norm(m.group(2))

def js_unescape(lit):
    return json.loads('"' + lit.replace('"', '\\"').replace("\\'", "'") + '"')
for m in re.finditer(r"\bT\('((?:[^'\\]|\\.)*)'", js):
    texts.setdefault(js_unescape(m.group(1)), 'script text (use {0}, {1} placeholders as given)')
for m in re.finditer(r"T\(\[((?:'[^']*',?\s*)+)\]", js):
    for x in re.findall(r"'([^']*)'", m.group(1)): texts.setdefault(x, 'script label')
# messages shown through toast()/showLogin(): literals passed to toast, showLogin, post/ppPost (last arg), and "|| '...'" defaults
pats = [r"toast\('((?:[^'\\]|\\.)*)'\)", r"showLogin\('((?:[^'\\]|\\.)+)'\)", r"\|\| '((?:[^'\\]|\\.)+)'\)",
        r"post\([^;]*?, '([A-Z](?:[^'\\]|\\.)*)'\)", r"ppPost\([^;]*?, '([A-Z](?:[^'\\]|\\.)*)'\)"]
for p in pats:
    for m in re.finditer(p, js):
        t = js_unescape(m.group(1))
        if re.search('[A-Za-z]{2}', t): texts.setdefault(t, 'short message (toast) on the phone')
labels = re.search(r"var labels = \{([^}]*)\}", js).group(1)
for x in re.findall(r": '([^']*)'", labels): texts.setdefault(x, 'playback state shown under the title')
for k in list(texts):
    if k in SKIP: del texts[k]

server = [
 # RemoteApi
 "PIN required", "too many wrong PINs - wait 10 minutes", "Android did not allow this", "Player Pure is not installed",
 "could not reach YouTube - check the tablet's internet", "file not found", "files are not available", "link expired - tap Download again",
 "no video found for that", "the tablet could not do that - see the hints on the page", "the tablet could not do that",
 "the tablet could not open Brave - see the hints on the page", "the tablet has no Wi-Fi address yet",
 "add your free YouTube key first (see the box below)",
 "YouTube refused the key - check it in Google Cloud (YouTube Data API v3 enabled, restrictions)",
 "today's free YouTube searches are used up - try again tomorrow",
 "allow All files access for Media Home on the tablet (see the tip on this page)",
 "turn on Mouse & keyboard for Media Home on the tablet (see the tip on this page)", "tap a text field on the tablet first",
 "only http, https, rtsp, rtmp or smb links can be opened", "that is not a YouTube API key (it starts with AIza and has 39 characters)",
 "search text too long", "text too long", "not a YouTube video", "no picture", "no cover", "no artwork",
 # FileStore
 "drive not found (unplugged?)", "outside the drive", "folder not found", "this folder can't be read", '"{0}" already exists',
 "could not create the folder", "the drive itself can't be renamed", "not found", "could not rename", "this can't be deleted",
 "could not create the trash folder", "could not move to the trash", "this file can't be read", "not enough free space on this drive",
 # Player Pure (control provider and DAC display)
 "album not found", "track not found", "nothing to add",
 "Set the volume on the DAC or amplifier (or turn on Digital volume in Player Pure)", "This DAC has no volume control over USB",
 "This DAC has no mute over USB (use its remote or front button)",
 "on the DAC", "Waiting for music", "Native DSD", "DSD over PCM (DoP)", "NO DAC", "IDLE", "NOT BIT-PERFECT",
 "Player Pure holds the DAC; nothing is streaming yet.", "Paused: the DAC receives silence at this rate",
]
out = {"t": {k: texts[k] for k in sorted(texts)}, "html": html_blocks, "e": {k: "message from the tablet (shown on the phone)" for k in server}}
json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(out['t']), 'texts,', len(html_blocks), 'html blocks,', len(server), 'server messages')
