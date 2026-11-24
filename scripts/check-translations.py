#!/usr/bin/env python3
"""Fail when a Ukrainian UI string has no English/Russian translation.

Source strings are Ukrainian. Android translates through NodiText.kt
("uk" to arrayOf("en", "ru")), macOS through nodiWords in Nodi.swift
("uk": ["en", "ru"]). Every Cyrillic string literal in the app sources must
be a key of its platform table (or match a "prefix:" / "prefix " key), unless
it is listed in ALLOWED below.

Usage: python3 scripts/check-translations.py [--list]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CYRILLIC = re.compile(r"[А-Яа-яІіЇїЄєҐґ’]")
LETTERS = re.compile(r"[А-Яа-яІіЇїЄєҐґ]")
UKRAINIAN_ONLY = re.compile(r"[ІіЇїЄєҐґ]")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
INTERPOLATION = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*|\\\([^)]*\)")

# Not shown to users in this form:
# - language names are shown in their own language in the language picker;
# - "Готово —" is only compared against the (translated) receiver status prefix.
ALLOWED = {"Українська", "Русский", "Готово —"}

PLATFORMS = {
    "android": {
        "table": ROOT / "android/app/src/main/java/dev/nodi/android/NodiText.kt",
        "entry": re.compile(r'^"((?:[^"\\]|\\.)*)"\s*to\s*arrayOf\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"', re.M),
        "sources": sorted((ROOT / "android/app/src/main/java/dev/nodi/android").glob("*.kt")),
    },
    "macos": {
        "table": ROOT / "macos/Nodi.swift",
        "entry": re.compile(r'^"((?:[^"\\]|\\.)*)":\s*\[\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"', re.M),
        # The Go core's event messages are shown by the macOS app, so they use its table.
        "sources": sorted((ROOT / "macos").glob("*.swift"))
        + sorted(p for p in (ROOT / "cmd").rglob("*.go") if not p.name.endswith("_test.go"))
        + sorted(p for p in (ROOT / "internal").rglob("*.go") if not p.name.endswith("_test.go")),
    },
}


def check(name, cfg, listing):
    entries = [(m.group(1), (m.group(2), m.group(3))) for m in cfg["entry"].finditer(cfg["table"].read_text())]
    table = dict(entries)
    prefixes = [k for k in table if k.endswith((":", " "))]
    problems = []
    # A duplicate key crashes the Swift dictionary literal at launch and silently
    # overrides an entry in Kotlin, so it is always an error.
    seen = set()
    for key, _ in entries:
        if key in seen:
            problems.append(f"{name}: duplicate key {key!r}")
        seen.add(key)
    for key, (en, ru) in table.items():
        if not en.strip() or not ru.strip():
            problems.append(f"{name}: empty translation for {key!r}")
        if LETTERS.search(en):
            problems.append(f"{name}: English translation is not English for {key!r}")
        if UKRAINIAN_ONLY.search(ru):
            problems.append(f"{name}: Russian translation contains Ukrainian letters for {key!r}")

    def known(text):
        if text in table or text in ALLOWED:
            return True
        return any(text.startswith(p) for p in prefixes)

    for path in cfg["sources"]:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            if cfg["entry"].match(line):
                continue
            for match in LITERAL.finditer(line):
                text = match.group(1)
                if not CYRILLIC.search(text) or known(text):
                    continue
                # Interpolated literals: every static Cyrillic part must be translatable.
                parts = [p.strip(" \\n") for p in INTERPOLATION.split(text)]
                if INTERPOLATION.search(text) and all(not CYRILLIC.search(p) or known(p) or known(p.strip()) for p in parts):
                    continue
                problems.append(f"{name}: {path.relative_to(ROOT)}:{number}: {text}")
    if listing:
        print(f"{name}: {len(table)} translated strings")
    return problems


def main():
    listing = "--list" in sys.argv
    problems = []
    for name, cfg in PLATFORMS.items():
        problems += check(name, cfg, listing)
    for problem in problems:
        print(problem)
    if problems:
        print(f"\n{len(problems)} untranslated or invalid strings.", file=sys.stderr)
        return 1
    print("PASS: every Ukrainian UI string has English and Russian translations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
