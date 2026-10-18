"""Regenerates app/src/main/assets/dict/hanzi_basic.json from the Unicode UCD.

Usage:
    curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    python tools/build_hanzi_basic.py Unihan.zip

Output rows: word, phonetic (kMandarin), definition "[部首 N · M 画]" + kDefinition.
Source: Unicode® Unihan Database (https://www.unicode.org/ucd/), Unicode License.
"""
import json
import os
import re
import sys
import zipfile

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "dict", "hanzi_basic.json",
)

FIELDS = {"kMandarin", "kDefinition", "kTotalStrokes", "kRSUnicode", "kTGHZ2013"}


def gb2312_hanzi():
    """Every Han character in GB2312 (6763: level 1 + level 2)."""
    chars = set()
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            if "\u4e00" <= ch <= "\u9fff":
                chars.add(ch)
    return sorted(chars)


def load(z, names, fields):
    out = {}
    for name in names:
        text = z.read(name).decode("utf-8")
        for line in text.splitlines():
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3 or parts[1] not in fields:
                continue
            out.setdefault(int(parts[0][2:], 16), {}).setdefault(parts[1], parts[2])
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "Unihan.zip"
    z = zipfile.ZipFile(src)
    data = load(z, ["Unihan_Readings.txt", "Unihan_IRGSources.txt"], FIELDS)

    entries = []
    for ch in gb2312_hanzi():
        d = data.get(ord(ch))
        if not d:
            continue
        definition = d.get("kDefinition", "")
        if not definition:
            continue
        pinyin = d.get("kMandarin", "")
        if not pinyin:
            pinyin = re.split(r"[,，]", d.get("kTGHZ2013", ""))[0].strip()
        if not pinyin:
            continue
        first = re.split(r"[;；]", definition)[0].strip()
        if len(first) > 90:
            first = first[:90] + "…"
        meta = []
        if d.get("kRSUnicode", "").split(".")[0]:
            meta.append("部首 " + d["kRSUnicode"].split(".")[0])
        if d.get("kTotalStrokes"):
            meta.append("%s 画" % d["kTotalStrokes"])
        text = ("[" + " · ".join(meta) + "]\n" if meta else "") + first
        entries.append({"word": ch, "phonetic": pinyin, "definition": text})

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))
    print("entries:", len(entries), "->", os.path.realpath(OUT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
