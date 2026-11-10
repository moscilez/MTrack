#!/usr/bin/env python3
"""Lists R.string / R.plurals keys used in Kotlin sources that are missing from
values/strings.xml (EN) or values-ru/strings.xml (RU), and keys defined but unused.

Usage: python3 android-mtrack/scripts/check_strings.py [--used]
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main"
REF = re.compile(r"R\.(string|plurals)\.([a-zA-Z0-9_]+)")
# Keys referenced from XML resources (manifest, themes, etc.).
XML_REF = re.compile(r"@(string|plurals)/([a-zA-Z0-9_]+)")


def used_keys():
    keys = set()
    for f in SRC.rglob("*.kt"):
        keys.update(REF.findall(f.read_text(encoding="utf-8")))
    for f in list(SRC.rglob("*.xml")):
        if "values" in f.parent.name and f.name == "strings.xml":
            continue
        keys.update(XML_REF.findall(f.read_text(encoding="utf-8")))
    return keys


def defined(locale_dir):
    path = SRC / "res" / locale_dir / "strings.xml"
    if not path.exists():
        return set()
    root = ET.parse(path).getroot()
    return {(el.tag, el.get("name")) for el in root if el.tag in ("string", "plurals")}


def main():
    used = used_keys()
    if "--used" in sys.argv:
        for kind, name in sorted(used, key=lambda k: k[1]):
            print(f"{kind}\t{name}")
        return 0
    failed = False
    base = SRC / "res" / "values" / "strings.xml"
    fixed = set()
    if base.exists():
        fixed = {(el.tag, el.get("name")) for el in ET.parse(base).getroot() if el.get("translatable") == "false"}
    for loc in ("values", "values-ru"):
        d = defined(loc) | (fixed if loc != "values" else set())
        missing = sorted(used - d, key=lambda k: k[1])
        unused = sorted(d - used, key=lambda k: k[1])
        print(f"[{loc}] defined={len(d)} used={len(used)} missing={len(missing)} unused={len(unused)}")
        for kind, name in missing:
            print(f"  MISSING {kind} {name}")
            failed = True
        for kind, name in unused:
            print(f"  unused {kind} {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
