#!/usr/bin/env python3
"""Launcher kept at the repository root so that the documented command still works.

The script itself ships with the module (ebx-connectors/tools/upgrade_snapshot.py), so that a deployed copy and the tests
that replay in a workspace read the file of the installed module and not a copy left by hand in <workspace>/tools (audit X3-09).
"""
import pathlib
import runpy
import sys

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "ebx-connectors" / "tools" / "upgrade_snapshot.py"
if __name__ == "__main__":
    sys.argv[0] = str(SCRIPT)
    runpy.run_path(str(SCRIPT), run_name="__main__")
