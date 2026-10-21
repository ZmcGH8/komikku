#!/usr/bin/env python3
"""Catches a class of mistake the Kotlin compiler is the only real authority on: a file that
*uses* a symbol from another package without importing it.

That is an "Unresolved reference" on the real build, and nothing else in this repo can see it -
`scripts/kotlinc-check` cannot compile the app module (no Android SDK, no project classpath), so a
dropped or mis-sorted import only surfaces after a full Gradle run. This is a coarse net for the
common case, not a substitute for the compiler:

  - it knows every top-level declaration in the repository, so it can ask "does this name exist at
    all, and does this file import it?"
  - it reports only names that genuinely exist elsewhere, so it stays quiet on unresolved symbols
    it knows nothing about (platform types, generated code, locals, members)
  - it models ktlint's import layout (`*`, java, javax, kotlin, aliases), so the order it suggests is
    the order ktlint wants and the fix does not trade one lint for another

Usage:  scripts/kotlinc-check/check_imports.py <file-or-dir>...
Exit non-zero if anything is reported.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# A top-level declaration: optional modifiers, then the keyword and the name. The trailing lookahead
# rejects extension functions (`fun Boolean.isBlank()`), whose receiver is not a declared name - and
# getting that wrong makes every built-in type in the repo look like an unresolved project symbol.
DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public |internal |private |protected |abstract |sealed |open |data |value |enum |annotation |inline |expect |actual |external )*"
    r"(?:class|interface|object|fun|val|var|typealias)\s+"
    r"(?:<[^>]*>\s+)?"
    r"([A-Za-z_][A-Za-z0-9_]*)"
    r"(?![A-Za-z0-9_]*\s*\.)"
)

# Names that are types of the platform or of the Kotlin stdlib. Listed because the declaration scan
# legitimately finds project code that *mentions* them, and a built-in is never a missing import.
BUILTIN = {
    "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "Char", "String", "Unit",
    "Any", "Nothing", "Throwable", "Exception", "Error", "Number", "Comparable", "Iterable",
    "Collection", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet", "Sequence",
    "Array", "Enum", "Function", "IntRange", "IntArray", "Regex", "Result", "Pair", "Triple",
    "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "StringBuilder",
}

# Source roots worth scanning for declarations. Deliberately the Kotlin ones; generated and build
# output directories are skipped so stale classes cannot vouch for a deleted symbol.
SOURCE_ROOTS = ["app/src", "core", "domain", "data", "source-api", "source-local",
                "presentation-core", "presentation-widget", "core-metadata"]


def package_of(path):
    for line in open(path, encoding="utf-8"):
        if line.startswith("package "):
            return line.split(" ", 1)[1].strip()
    return ""


_BLOCK = re.compile(r"/\*.*?\*/", re.S)
_LINE = re.compile(r"//[^\n]*")
_STRING = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def code_only(text):
    """The file with comments and string literals blanked out.

    Without this the scan reads prose: a KDoc sentence containing "Chapter" or "TOC" looks exactly
    like a reference to a type the repo happens to declare, and every one of those is noise.
    """
    text = _BLOCK.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    text = _LINE.sub("", text)
    return _STRING.sub('""', text)


def declared_names():
    """package -> top-level names, and file -> every name including nested ones."""
    by_package = {}
    by_file = {}
    for root in SOURCE_ROOTS:
        base = os.path.join(ROOT, root)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".git")]
            for name in filenames:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                try:
                    pkg = package_of(path)
                    text = open(path, encoding="utf-8").read()
                except (OSError, UnicodeDecodeError):
                    continue
                depth = 0
                # Braces counted on the comment/string-stripped source: a `{` inside a KDoc snippet or
                # a string literal would otherwise corrupt the depth and hide every nested declaration,
                # which then reads as a missing import in whichever other file refers to it.
                for line in code_only(text).split("\n"):
                    m = DECL.match(line)
                    if m:
                        # Depth 0 is a top-level type, so a file in another package has to import it.
                        # Anything deeper is nested or a member, which resolves from inside its own
                        # file - recorded so a same-file reference is not reported as missing.
                        if depth == 0:
                            by_package.setdefault(pkg, set()).add(m.group(1))
                        else:
                            by_file.setdefault(path, set()).add(m.group(1))
                    depth += line.count("{") - line.count("}")
                    if depth < 0:
                        depth = 0
    return by_package, by_file


def ktlint_group(imp):
    if imp.startswith("java."):
        return 1
    if imp.startswith("javax."):
        return 2
    if imp.startswith("kotlin."):
        return 3
    return 0


def check(path, by_package, by_file, problems):
    # Normalised: declared_names() stores absolute paths, so a relative argument (which is what
    # `git status` hands over) would miss the same-file lookup and report every nested type as
    # unresolved.
    path = os.path.abspath(path)
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    pkg = package_of(path)

    idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    imports = [lines[i][len("import "):].strip() for i in idx]

    imported = {(b.split(" as ")[0].strip() if " as " in b else b).rsplit(".", 1)[-1] for b in imports}
    same_package = by_package.get(pkg, set())
    own_file = by_file.get(path, set())

    # Comments and string literals blanked first: a KDoc sentence mentioning "Chapter" is otherwise
    # indistinguishable from a reference to a type the repo declares.
    candidates = set()
    for line in code_only(text).split("\n"):
        if line.startswith(("import ", "package ")):
            continue
        for m in re.finditer(r"(?<![\w.])([A-Z][A-Za-z0-9_]{2,})", line):
            candidates.add(m.group(1))

    for name in sorted(candidates):
        if name in imported or name in same_package or name in own_file or name in BUILTIN:
            continue
        owners = [p for p, names in by_package.items() if name in names]
        # Only names this repository actually declares: anything else is a platform type, a generated
        # binding, or a local, and guessing would be noise.
        if not owners or pkg in owners:
            continue
        problems.append((path, name, owners))

    # Import layout, so the suggested order does not trade one lint for another.
    def is_alias(b):
        return " as " in b

    plain = sorted([b for b in imports if not is_alias(b)], key=lambda b: (ktlint_group(b), b))
    aliases = sorted([b for b in imports if is_alias(b)])
    expected = plain + aliases
    actual = imports
    inner = [l for l in lines[min(idx):max(idx) + 1] if not l.startswith("import ")] if idx else []
    return inner, actual, expected


def main(argv):
    targets = []
    for arg in argv:
        if os.path.isdir(arg):
            for dirpath, dirnames, filenames in os.walk(arg):
                dirnames[:] = [d for d in dirnames if d != "build"]
                targets += [os.path.join(dirpath, f) for f in filenames if f.endswith(".kt")]
        else:
            targets.append(arg)
    if not targets:
        print("check_imports: no input files", file=sys.stderr)
        return 2

    declarations, own = declared_names()
    problems = []
    layout = []

    for path in sorted(set(targets)):
        inner, actual, expected = check(path, declarations, own, problems)
        if inner:
            layout.append((path, "non-import line inside the import block: " + inner[0].strip()))
        elif actual != expected:
            for a, e in zip(actual, expected):
                if a != e:
                    layout.append((path, "order: %s should come before %s" % (e, a)))
                    break
            else:
                layout.append((path, "order: %s is misplaced" % (expected[len(actual)],)))

    rel = lambda p: os.path.relpath(p, ROOT)
    for path, name, owners in problems:
        print("%s: unresolved '%s' - declared in %s" % (rel(path), name, ", ".join(sorted(owners))))
    for path, msg in layout:
        print("%s: %s" % (rel(path), msg))

    if problems or layout:
        print("\nFAIL — %d unresolved, %d layout" % (len(problems), len(layout)))
        return 1
    print("OK — %d file(s): every project type used is imported, and import layout matches ktlint" % len(set(targets)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))