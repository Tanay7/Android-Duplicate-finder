#!/usr/bin/env python3
"""Finds something on the emulator's screen for the on-device test.

    ui.py REGEX     prints "x y", the centre of the first view whose text or description matches
                    (case-insensitive); exits 1 when nothing matches.
    ui.py --dump    prints every text and description on screen, to see why nothing matched.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def nodes():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    found = nodes()
    if sys.argv[1] == "--dump":
        for n in found:
            label = n.get("text") or n.get("content-desc")
            if label:
                print(f"{n.get('bounds')} {label!r}")
        return
    pat = re.compile(sys.argv[1], re.IGNORECASE)
    for n in found:
        for label in (n.get("text") or "", n.get("content-desc") or ""):
            if label and pat.search(label):
                (x1, y1), (x2, y2) = [tuple(map(int, p)) for p in re.findall(r"\[(\d+),(\d+)\]", n.get("bounds"))]
                print((x1 + x2) // 2, (y1 + y2) // 2)
                return
    sys.exit(1)


if __name__ == "__main__":
    main()
