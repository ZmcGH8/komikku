#!/usr/bin/env python3
"""Curate human-written release notes.

RELEASE_NOTES.md is the staging area: humans append user-facing bullets to it
during a cycle, and the release workflow renders it into the GitHub release
body in place of raw commit subjects. Raw commits stay available as a collapsed
technical list for anyone who wants them.

The lifecycle is deliberately split in two, because the release and the clear
cannot be the same commit:

  1. append bullets to RELEASE_NOTES.md as work lands
  2. bump versionName in app/build.gradle.kts   <- notes still present
  3. push; CI releases using RELEASE_NOTES.md
  4. after the release, run --cut to archive into CHANGELOG.md and clear

Clearing at step 2 would ship an empty changelog, so --check fails the release
when the file has nothing in it.

Usage:
  release_notes.py --check            # fail if empty/malformed (CI, pre-tag)
  release_notes.py --render           # markdown block for the release body
  release_notes.py --cut v1.23.0      # archive to CHANGELOG.md, reset
  release_notes.py --cut v1.23.0 --dry-run

With --check / --render the notes are read from $RELEASE_NOTES_FILE when set,
which lets CI point at a checkout path. Otherwise ./RELEASE_NOTES.md is used.
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import sys
from pathlib import Path

DEFAULT_NOTES = "RELEASE_NOTES.md"
DEFAULT_CHANGELOG = "CHANGELOG.md"

# Headings the release body has slots for. Anything else is a typo we would
# otherwise silently drop, so --check rejects it.
SECTIONS = ("New", "Improve", "Fix")

# Release body heading depth: the notes file uses h3 so it renders sanely on
# GitHub, the release body nests one level deeper under "What's Changed".
HEADING_PREFIX = "#####"

SECTION_RE = re.compile(r"^###\s+(.*?)\s*$")
BULLET_RE = re.compile(r"^[-*]\s+(.*\S)\s*$")
UNRELEASED_RE = re.compile(r"^##\s+\[Unreleased\]\s*$", re.IGNORECASE)
VERSION_HEADING_RE = re.compile(r"^##\s+\[")

# Continuation lines of a bullet are indented two spaces. Markdown needs the
# indent to keep them inside the list item; a lazy continuation (no indent)
# renders inconsistently between GitHub and Discord.
CONTINUATION_INDENT = "  "

TEMPLATE = """<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

### Improve

### Fix
"""


class NotesError(Exception):
    """Malformed or unusable notes."""


def read_notes(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise NotesError(f"{path} not found") from None


def parse_notes(text: str) -> dict[str, list[str]]:
    """Collect bullets per known section.

    Raises on an unrecognised "###" heading rather than dropping it: a typo'd
    section would otherwise vanish from the release without a trace.
    """
    found: dict[str, list[str]] = {name: [] for name in SECTIONS}
    current: str | None = None

    for raw in text.splitlines():
        line = raw.rstrip()
        heading = SECTION_RE.match(line)
        if heading:
            name = heading.group(1).strip()
            # Match case-insensitively but store canonically, so "new" works
            # and still renders under the release body's "New".
            canonical = next((s for s in SECTIONS if s.lower() == name.lower()), None)
            if canonical is None:
                raise NotesError(
                    f"unknown section '### {name}' (expected one of: {', '.join(SECTIONS)})"
                )
            current = canonical
            continue
        bullet = BULLET_RE.match(line.strip())
        if bullet and current is not None:
            found[current].append(bullet.group(1))
            continue
        # An indented line under the bullet above is a continuation of it, and
        # belongs in the same item. These used to be dropped, which truncated
        # every multi-line bullet at its first line in the release body AND in
        # the CHANGELOG archive - the notes file is hand-wrapped, so nearly
        # every bullet lost most of its text.
        if current is not None and found[current] and line[:1] in (" ", "\t"):
            found[current][-1] += "\n" + line.strip()

    return found


def bullet_lines(item: str) -> list[str]:
    """One bullet as the markdown lines it occupies.

    An item may carry embedded newlines from the notes file's own hand-wrapping.
    Those are preserved rather than re-flowed: re-wrapping would risk breaking a
    line inside a `code` span, where a soft break renders as a space and
    silently corrupts the path or command.
    """
    parts = [part.strip() for part in item.split("\n")]
    parts = [part for part in parts if part]
    if not parts:
        return []
    return [f"- {parts[0]}"] + [f"{CONTINUATION_INDENT}{part}" for part in parts[1:]]


def render(notes: dict[str, list[str]]) -> str:
    """Release-body markdown. Empty sections are dropped so the release shows
    no empty headings, and the block is never wholly blank (--check catches
    that first)."""
    blocks = []
    for name in SECTIONS:
        items = notes.get(name) or []
        if not items:
            continue
        lines = [f"{HEADING_PREFIX} {name}", ""]
        for item in items:
            lines += bullet_lines(item)
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def cmd_check(notes: dict[str, list[str]]) -> int:
    total = sum(len(v) for v in notes.values())
    if total == 0:
        raise NotesError(
            "RELEASE_NOTES.md has no bullets. Add user-facing notes before "
            "bumping versionName, or the release will ship an empty changelog."
        )
    for name in SECTIONS:
        count = len(notes.get(name) or [])
        print(f"{name}: {count}")
    print(f"total: {total}")
    return 0


def cmd_cut(notes_path: Path, changelog: Path, version: str, dry_run: bool) -> int:
    notes = parse_notes(read_notes(notes_path))
    total = sum(len(v) for v in notes.values())
    if total == 0:
        raise NotesError(
            f"{notes_path} has no bullets to archive. Run after a release that "
            "used these notes; if the file is already empty it was cut twice."
        )

    today = datetime.date.today().isoformat()
    entry = [f"## [{version}] - {today}"]
    for name in SECTIONS:
        items = notes.get(name) or []
        if not items:
            continue
        entry.append(f"### {name}")
        for item in items:
            entry += bullet_lines(item)
    entry.append("")

    try:
        existing = changelog.read_text(encoding="utf-8")
    except FileNotFoundError:
        existing = ""

    # Insert under the [Unreleased] block, which stays at the top of the file.
    # Its subsections are "###", so the first "##" after it is the next version
    # heading and marks the insertion point.
    lines = existing.splitlines()
    if any(VERSION_HEADING_RE.match(line) and f"[{version}]" in line for line in lines):
        raise NotesError(f"CHANGELOG.md already has a section for {version}")

    insert_at = None
    for index, line in enumerate(lines):
        if UNRELEASED_RE.match(line):
            for later in range(index + 1, len(lines)):
                if VERSION_HEADING_RE.match(lines[later]):
                    insert_at = later
                    break
            break
    if insert_at is None:
        # No [Unreleased] block: fall back to before the first version heading,
        # or to the end of the file when there are none.
        insert_at = next(
            (i for i, line in enumerate(lines) if VERSION_HEADING_RE.match(line)),
            len(lines),
        )

    # Keep exactly one blank line between the block above and the new section.
    while insert_at > 0 and not lines[insert_at - 1].strip():
        insert_at -= 1
    new_text = "\n".join(lines[:insert_at] + [""] + entry + lines[insert_at:]).rstrip() + "\n"

    if dry_run:
        print("--- would append to CHANGELOG.md ---")
        print("\n".join(entry))
        print("--- would reset ---")
        print(TEMPLATE)
        return 0

    changelog.write_text(new_text, encoding="utf-8")
    notes_path.write_text(TEMPLATE, encoding="utf-8")
    print(f"Archived {total} bullet(s) into {changelog} under [{version}]")
    print(f"Reset {notes_path} for the next cycle")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="validate; fail if empty")
    mode.add_argument("--render", action="store_true", help="print the release-body block")
    mode.add_argument("--cut", metavar="VERSION", help="archive to CHANGELOG.md and reset")
    parser.add_argument("--dry-run", action="store_true", help="with --cut, print instead of writing")
    parser.add_argument("--notes", type=Path, help=f"notes file (default ./{DEFAULT_NOTES})")
    parser.add_argument("--changelog", type=Path, help=f"changelog (default ./{DEFAULT_CHANGELOG})")
    args = parser.parse_args(argv)

    notes_path = args.notes or Path(os.environ.get("RELEASE_NOTES_FILE", DEFAULT_NOTES))
    changelog_path = args.changelog or Path(DEFAULT_CHANGELOG)

    try:
        if args.check:
            return cmd_check(parse_notes(read_notes(notes_path)))
        if args.render:
            print(render(parse_notes(read_notes(notes_path))))
            return 0
        return cmd_cut(notes_path, changelog_path, args.cut, args.dry_run)
    except NotesError as e:
        print(f"release_notes: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
