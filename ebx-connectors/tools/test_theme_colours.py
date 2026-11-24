#!/usr/bin/env python3
"""Audit C2-18: no colour literal outside a var(--token, #fallback) in the screens that follow the perspective's theme.

Every colour of these screens is written var(--ebx-<token>, <the module's colour>) or var(--ftc-<token>, <colour>), so a perspective
that sets the token (ThemeTokens, or an override of the status tokens) recolours the screen and one that does not leaves it as it
was. The scan fails on a hexadecimal or rgb() colour that is not the fallback of a var().

    python3 tools/test_theme_colours.py [module directory]
"""
import os
import re
import sys

SCREENS = ['SyncLogForm', 'ConnectorMetricsForm', 'OpenApiImportUserService', 'ConnectorOperationsUserService', 'ReadOnlyForm']
UI = os.path.join('src', 'main', 'java', 'com', 'on', 'ebx', 'connect', 'ebx', 'ui')
WRAPPED = re.compile(r'var\(--[a-z-]+,\s*(?:#[0-9a-fA-F]{3,8}|rgba?\([^)]*\))\)')
LITERAL = re.compile(r'(?<!&)#[0-9a-fA-F]{3,8}\b|rgba?\(')
THEMED = re.compile(r'var\(--ebx-')


def scan(module):
    problems = []
    for name in SCREENS:
        path = os.path.join(module, UI, name + '.java')
        text = open(path, encoding='utf-8').read()
        bare = WRAPPED.sub('', text)
        for m in LITERAL.finditer(bare):
            problems.append('%s: colour literal %r outside a var() fallback (about char %d)' % (name, m.group(0), m.start()))
        if not THEMED.search(text):
            problems.append('%s: no var(--ebx-…) token at all' % name)
    return problems


def main():
    module = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    problems = scan(module)
    for p in problems:
        print('FAIL:', p)
    if problems:
        sys.exit(1)
    print('PASS: %d screens carry no colour outside a var(--token, fallback)' % len(SCREENS))


if __name__ == '__main__':
    main()
