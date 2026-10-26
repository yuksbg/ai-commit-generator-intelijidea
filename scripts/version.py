#!/usr/bin/env python3
"""Generate a reproducible release version from the Git history."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


if git('rev-parse', '--is-shallow-repository') == 'true':
    raise SystemExit('Version generation requires full Git history. Run git fetch --unshallow.')

# Each new commit increases the patch number; rebuilding a commit keeps its version.
print(f"1.0.{int(git('rev-list', '--count', 'HEAD'))}")
