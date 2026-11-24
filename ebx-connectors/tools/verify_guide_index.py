#!/usr/bin/env python3
"""Fail when a connector-specific guide is orphaned from the original guides."""

from pathlib import Path
import sys


def main() -> int:
    module = Path(__file__).resolve().parents[1]
    docs = module / "docs"
    markdown = (docs / "USER-GUIDE.md").read_text(encoding="utf-8")
    html = (docs / "USER-GUIDE.html").read_text(encoding="utf-8")
    failures = []
    guides = sorted(docs.glob("*-USER-GUIDE.html"))

    for guide in guides:
        if guide.name not in markdown:
            failures.append(f"{guide.name}: missing from USER-GUIDE.md")
        if guide.name not in html:
            failures.append(f"{guide.name}: missing from USER-GUIDE.html")
        body = guide.read_text(encoding="utf-8")
        if 'href="USER-GUIDE.html"' not in body:
            failures.append(f"{guide.name}: missing link back to USER-GUIDE.html")

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {len(guides)} connector guides are indexed by both original guides and link back")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
