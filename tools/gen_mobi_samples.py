#!/usr/bin/env python3
"""Generate valid MOBI (KF7) and AZW3 (KF8) sample files for the parser test.

These files exercise the *real* MobiParser in the app:
  - test_book.mobi        : classic MOBI, compression=1 (uncompressed), UTF-8 Chinese
  - test_book_palmdoc.mobi: classic MOBI, compression=2 (PalmDOC), English w/ back-ref
  - test_book.azw3        : KF8/AZW3, EXTH 121 boundary, XHTML flow records
"""
import struct
import os

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources")
os.makedirs(OUT_DIR, exist_ok=True)


def be16(v):
    return struct.pack(">H", v & 0xFFFF)


def be32(v):
    return struct.pack(">I", v & 0xFFFFFFFF)


def build_pdb(records):
    num = len(records)
    header = bytearray(78)
    name = b"OPENREADERTEST"
    header[0:len(name)] = name
    header[60:64] = b"BOOK"      # type
    header[64:68] = b"MOBI"      # creator
    header[76:78] = be16(num)
    body = bytearray()
    offsets = []
    pos = 78 + num * 8
    for data in records:
        offsets.append(pos)
        body.extend(data)
        pos += len(data)
    recinfo = bytearray()
    for i, off in enumerate(offsets):
        recinfo += be32(off)
        recinfo += bytes([0])          # attributes
        recinfo += bytes([0, 0, 0])    # uid (24 bits)
    return bytes(header) + bytes(recinfo) + bytes(body)


def palmdoc_header(compression, text_length, text_record_count):
    return (be16(compression) + be16(0) + be32(text_length) +
            be16(text_record_count) + be16(4096) + be16(0) + be16(0))


def mobi_header(file_version, text_encoding=65001, exth=True, header_len=228):
    h = bytearray(header_len)
    h[0:4] = b"MOBI"
    h[4:8] = be32(header_len)
    h[8:12] = be32(2)            # mobi type
    h[12:16] = be32(text_encoding)
    h[16:20] = be32(0)           # format
    h[20:24] = be32(file_version)
    h[84:88] = be32(0)           # fullNameOffset (unused; metadata via EXTH)
    h[88:92] = be32(0)           # fullNameLength
    if exth:
        h[112:116] = be32(0x40)  # EXTH present
    return bytes(h)


def build_exth(entries):
    """entries: list of (type:int, data:str)."""
    buf = bytearray()
    count = 0
    for t, d in entries:
        d = d.encode("utf-8").replace(b"\x00", b"")
        length = 8 + len(d)
        buf += be32(t) + be32(length) + d
        count += 1
    pad = (4 - (len(buf) % 4)) % 4
    buf += b"\x00" * pad
    total = 12 + len(buf)
    return b"EXTH" + be32(total) + be32(count) + bytes(buf)


def record0(compression, text_len, text_count, file_version, exth_entries, encoding=65001):
    return (palmdoc_header(compression, text_len, text_count) +
            mobi_header(file_version, encoding) +
            build_exth(exth_entries))


def write_mobi_old(filename, html_body, title, author, compression=1, encoding=65001):
    if compression == 2:
        encoded = palmdoc_encode(html_body)
        text_len = len(html_body)
    else:
        encoded = html_body
        text_len = len(html_body)
    rec0 = record0(compression, text_len, 1, 6,
                   [(503, title), (100, author)], encoding)
    records = [rec0, encoded]
    data = build_pdb(records)
    with open(os.path.join(OUT_DIR, filename), "wb") as f:
        f.write(data)
    print(f"wrote {filename}: {len(data)} bytes (compression={compression})")


def write_kf8(filename, parts, title, author):
    """parts: list of XHTML byte strings (flow records after the KF8 header)."""
    n = len(parts)
    rec0 = record0(1, 0, 0, 8,
                   [(121, "1"), (125, "8"), (503, title), (100, author)])
    # second (KF8) MOBI header record
    kf8_header = palmdoc_header(1, 0, n) + mobi_header(8)
    records = [rec0, kf8_header] + list(parts)
    data = build_pdb(records)
    with open(os.path.join(OUT_DIR, filename), "wb") as f:
        f.write(data)
    print(f"wrote {filename}: {len(data)} bytes (KF8, {n} flow records)")


def palmdoc_encode(text):
    """Minimal PalmDOC encoder (ASCII-safe) that the Kotlin decoder reproduces.

    Literal bytes 0x01..0x7F -> themselves; 0x00 -> 0x00.
    For a repeated run it emits a length/distance back-reference so the
    decompressor's 0x80..0xBF branch is genuinely exercised.
    """
    b = text if isinstance(text, bytes) else text.encode("latin-1")
    out = bytearray()
    i = 0
    n = len(b)
    while i < n:
        # try to find a back-reference for the next 3+ bytes
        if i >= 3:
            window = b[max(0, i - 2047):i]
            seg = b[i:i + 30]
            best_len = 0
            best_dist = 0
            for start in range(len(window) - 1, -1, -1):
                run = 0
                while (run < 30 and i + run < n and
                       window[start + (run % (len(window) - start))] == b[i + run]):
                    run += 1
                if run > best_len:
                    best_len = run
                    best_dist = len(window) - start
            if best_len >= 3 and best_dist <= 2047:
                length = min(best_len, 3 + 0x07)  # cap at encoder max
                distance = best_dist
                code = ((distance << 3) | (length - 3)) & 0x3FFF
                out.append(0x80 | ((code >> 8) & 0x3F))
                out.append(code & 0xFF)
                i += length
                continue
        v = b[i]
        if v == 0:
            out.append(0)
        elif v <= 0x7F:
            out.append(v)
        else:
            # high bytes: emit space + (v & 0x7F) like the decoder expects
            out.append(0xC0 | (v & 0x7F))
        i += 1
    return bytes(out)


# ----------------------------------------------------------------- bodies

MOBI_HTML = (
    "<html><body>"
    "<h1>第一章 开始</h1>"
    "<p>这是 MOBI 格式的测试正文。OpenReader 应该能正确解析出标题、作者与章节。</p>"
    "<h2>第二章 继续</h2>"
    "<p>第二段内容用于验证章节切分与全文搜索功能是否正常工作。</p>"
    "</body></html>"
).encode("utf-8")

PALMDOC_HTML = (
    "<html><body><h1>Chapter One</h1>"
    "<p>The quick brown fox jumps over the lazy dog. "
    "The quick brown fox jumps over the lazy dog again.</p>"
    "<h2>Chapter Two</h2>"
    "<p>PalmDOC decompression must restore this English text exactly.</p>"
    "</body></html>"
).encode("latin-1")

AZW3_P1 = (
    "<html><body><h1>第一章 开篇</h1>"
    "<p>这是 AZW3 (KF8) 格式的测试正文，正文也应当被正确解压与渲染。</p>"
    "</body></html>"
).encode("utf-8")
AZW3_P2 = (
    "<html><body><h1>第二章 余韵</h1>"
    "<p>KF8 的 XHTML 流式记录被逐条拼接后，应按标题切分为独立章节。</p>"
    "</body></html>"
).encode("utf-8")

if __name__ == "__main__":
    write_mobi_old("test_book.mobi", MOBI_HTML, "MOBI 测试用例", "OpenReader", compression=1)
    write_mobi_old("test_book_palmdoc.mobi", PALMDOC_HTML, "PalmDOC 用例", "OpenReader", compression=2, encoding=1252)
    write_kf8("test_book.azw3", [AZW3_P1, AZW3_P2], "AZW3 测试用例", "OpenReader")
    print("done")
