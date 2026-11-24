#!/usr/bin/env python3
"""The module's guides as static pages, served by the module itself (About page).

Markdown of the repository -> src/main/webapp/www/common/help/*.html, so the documentation reachable from the UI is the one
of the installed version. The pages carry no script of their own beyond jscripts/connect/help-version.js (the version in the
top bar, from the ?version= of the link), a Content-Security-Policy that loads nothing from elsewhere, no screenshot (images
stay in docs/img, out of the war); links between the guides are kept, links to any other file of the repository become text.

    python3 tools/build_help.py          write the pages
    python3 tools/build_help.py --check  fail when a page is not what its Markdown gives (run by build-test.sh)

Not exposed on purpose: OPERATIONS.md, SECURITY-REVIEW-*.md and the evidence/gate documents (they describe an installation's
internals, not what a user does) and the per-connector guides (docs/*-USER-GUIDE.html, ~70 pages: the user guide lists them).
"""
import html
import os
import re
import sys

MODULE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(MODULE, 'src', 'main', 'webapp', 'www', 'common', 'help')
# (source, page, language, title, other page, label of the link to it)
PAGES = [
    ('docs/USER-GUIDE.md', 'user-guide.html', 'en', 'ON EBX Connectors — User guide', None, None),
    ('GUIDE.md', 'configuration-guide.html', 'en', 'ON EBX Connectors — Configuration guide', 'guide-configuration.html', 'Version française'),
    ('GUIDE.fr.md', 'guide-configuration.html', 'fr', 'ON EBX Connectors — Guide de configuration', 'configuration-guide.html', 'English version'),
    ('docs/REFERENCE.md', 'reference.html', 'en', 'ON EBX Connectors — Functional and data model reference', None, None),
]
# a link in a Markdown source to one of these files goes to the page generated from it
LINKS = {os.path.basename(source): page for source, page, _l, _t, _o, _x in PAGES}
STYLE = (
    ':root{color-scheme:light}body{font:15px/1.55 -apple-system,"Segoe UI",Helvetica,Arial,sans-serif;color:#222;max-width:1000px;'
    'margin:24px auto;padding:0 24px;background:#fff}h1{font-size:28px;border-bottom:2px solid #0b5394;padding-bottom:6px}'
    'h2{font-size:21px;margin-top:36px;border-bottom:1px solid #ddd;padding-bottom:4px}h3{font-size:17px;margin-top:24px}'
    'table{border-collapse:collapse;margin:12px 0;font-size:14px}th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;vertical-align:top}'
    'th{background:#f3f5f8}pre{overflow:auto;background:#f4f5f7;padding:10px;border-radius:4px}code{font:13px Menlo,Consolas,monospace;background:#f4f5f7;padding:1px 4px;'
    'border-radius:3px}pre code{padding:0}blockquote{border-left:4px solid #b9d0ea;margin:12px 0;padding:6px 14px;background:#f5f9fe}'
    'a{color:#0b5394}a:focus-visible{outline:3px solid #0b5394;outline-offset:2px}.help-top{display:flex;justify-content:'
    'space-between;gap:12px;flex-wrap:wrap;font-size:14px;color:#555;border-bottom:1px solid #eee;padding-bottom:8px}'
)
VERSION_SCRIPT = '<script src="../jscripts/connect/help-version.js?v=1"></script>'
POLICY = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; form-action 'none'"


def target(url):
    """The page a link goes to (kept), '#' anchors and http(s) links as they are, anything else None (becomes text)."""
    if url.startswith('#') or url.startswith(('http://', 'https://')):
        return url
    path, _, fragment = url.partition('#')
    page = LINKS.get(os.path.basename(path)) or (os.path.basename(path) if os.path.basename(path) in LINKS.values() else None)
    return None if page is None else page + ('#' + fragment if fragment else '')


def convert(text):
    import markdown  # python-markdown, used only here
    text = re.sub(r'(?m)^!\[[^\]]*\]\([^)]*\)\s*\n', '', text)

    def link(match):
        kept = target(match.group(2).strip())
        return match.group(0) if kept == match.group(2).strip() else ('[%s](%s)' % (match.group(1), kept) if kept else match.group(1))
    text = re.sub(r'(?<!!)\[([^\]]+)\]\(([^)\s]+)\)', link, text)
    body = markdown.markdown(text, extensions=['tables', 'toc', 'sane_lists', 'fenced_code'], output_format='html')
    return repair_anchors(body)


def repair_anchors(body):
    """The reference links to #table-conflict while the heading is "Table Conflict - Conflicts" (id table-conflict-conflicts):
    a link to a missing id goes to the one heading whose id starts with it, when there is exactly one."""
    ids = re.findall(r'id="([^"]+)"', body)
    known = set(ids)

    def fix(match):
        anchor = match.group(1)
        if anchor in known:
            return match.group(0)
        candidates = [i for i in ids if i.startswith(anchor + '-')]
        return 'href="#%s"' % candidates[0] if len(candidates) == 1 else match.group(0)
    return re.sub(r'href="#([^"]+)"', fix, body)


def page(source, lang, title, other, other_label):
    with open(os.path.join(MODULE, source), encoding='utf-8') as f:
        body = convert(f.read())
    nav = '<a href="%s" hreflang="%s">%s</a>' % (other, 'fr' if lang == 'en' else 'en', html.escape(other_label)) if other else ''
    return ('<!DOCTYPE html>\n<html lang="%s">\n<head>\n<meta charset="utf-8">\n'
            '<meta http-equiv="Content-Security-Policy" content="%s">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1">\n<title>%s</title>\n<style>%s</style>\n%s\n</head>\n<body>\n'
            '<!-- Generated by tools/build_help.py from %s: edit the Markdown, then run the script. -->\n'
            '<nav class="help-top" aria-label="help"><span>ON EBX Connectors<span data-module-version></span></span>%s</nav>\n'
            '<main>\n%s\n</main>\n</body>\n</html>\n') % (lang, POLICY, html.escape(title), STYLE, VERSION_SCRIPT, source, nav, body)


def problems(content, name):
    found = []
    if '<script' in content.replace(VERSION_SCRIPT, '') or 'src="http' in content or '<img' in content:
        found.append(name + ' (loads something)')
    ids = set(re.findall(r'id="([^"]+)"', content))
    broken = sorted(set(re.findall(r'href="#([^"]+)"', content)) - ids)
    if broken:
        found.append(name + ' (links to missing sections: ' + ', '.join(broken[:5]) + ')')
    return found


def main():
    check = '--check' in sys.argv
    try:
        import markdown  # noqa: F401
    except ImportError:
        if check:
            print('SKIP: help pages not checked (python-markdown is not installed)')
            return 0
        raise
    stale = []
    os.makedirs(OUT, exist_ok=True)
    for source, name, lang, title, other, other_label in PAGES:
        content = page(source, lang, title, other, other_label)
        path = os.path.join(OUT, name)
        current = open(path, encoding='utf-8').read() if os.path.exists(path) else None
        if check:
            if current != content:
                stale.append(name)
            stale.extend(problems(content, name))
        elif current != content:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(content)
            print('wrote', os.path.relpath(path, MODULE))
    if stale:
        print('FAIL: help pages out of date or unsafe: %s (run python3 tools/build_help.py)' % ', '.join(stale), file=sys.stderr)
        return 1
    if check:
        print('PASS: help pages match their Markdown (%d pages)' % len(PAGES))
    return 0


if __name__ == '__main__':
    sys.exit(main())
