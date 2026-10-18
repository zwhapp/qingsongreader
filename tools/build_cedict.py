"""Generates the built-in Chinese-English / English-Chinese dictionary from CC-CEDICT.

Source: CC-CEDICT (https://cc-cedict.org / MDBG), licensed CC BY-SA 4.0,
mirrored in this run from edvardsr/cc-cedict (data/all.js).

Output: app/src/main/assets/dict/cedict.tsv  -- word<TAB>phonetic<TAB>definition
Both directions are emitted:
  * Chinese head entries (simplified form) with pinyin + English glosses
  * English head entries built by reverse-indexing the glosses
"""
import json
import os
import re
import sys
from collections import defaultdict

SRC = sys.argv[1] if len(sys.argv) > 1 else "all.js"
OUT = (
    sys.argv[2]
    if len(sys.argv) > 2
    else "../OpenReader/app/src/main/assets/dict/cedict.tsv"
)

MAX_CN_LEN = 4          # keep everyday words and short phrases
MAX_SENSES = 2
MAX_DEF = 90
MAX_EN_PER_ENTRY = 2
MAX_CN_PER_EN = 3

STOPWORDS = {
    "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
    "you", "your", "not", "but", "can", "has", "have", "had", "his", "her",
    "its", "their", "our", "one", "two", "all", "any", "out", "who", "how",
    "why", "what", "when", "where", "which", "also", "into", "over", "more",
    "most", "some", "such", "than", "then", "them", "they", "she", "him",
    "been", "being", "does", "did", "will", "would", "could", "should",
    "about", "after", "before", "because", "between", "during", "through",
    "used", "using", "very", "just", "like", "make", "made", "much", "many",
    "other", "another", "each", "per", "see", "e.g", "etc", "fig", "lit",
}

TONE_TABLE = {
    "a": "aāáǎà", "e": "eēéěè", "i": "iīíǐì",
    "o": "oōóǒò", "u": "uūúǔù", "v": "üǖǘǚǜ", "ü": "üǖǘǚǜ",
}


def tone_mark(syllable: str) -> str:
    """pa1 -> pā ;  converts numbered pinyin to diacritics."""
    s = syllable.strip()
    if not s:
        return s
    m = re.match(r"^(.*?)([1-5])$", s)
    if not m:
        return s
    body, tone = m.group(1), int(m.group(2))
    if tone == 5 or not body:
        return body
    body = body.replace("v", "ü").replace("u:", "ü")
    idx = None
    low = body.lower()
    if "a" in low:
        idx = low.index("a")
    elif "o" in low:
        idx = low.index("o")
    elif "e" in low:
        idx = low.index("e")
    elif "iu" in low:
        idx = low.index("iu") + 1
    elif "ui" in low:
        idx = low.index("ui") + 1
    else:
        for pos in range(len(body) - 1, -1, -1):
            if low[pos] in TONE_TABLE:
                idx = pos
                break
    if idx is None:
        return body
    ch = body[idx]
    table = TONE_TABLE.get(ch.lower())
    if not table:
        return body
    marked = table[tone]
    return body[:idx] + marked + body[idx + 1:]


def pinyin_pretty(raw: str) -> str:
    return " ".join(tone_mark(p) for p in raw.split())


def glosses_of(value) -> list:
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, list):
        return [v for v in value if isinstance(v, str) and v.strip()]
    return []


def clean_definition(text: str) -> str:
    text = text.replace("\n", " ").strip()
    text = re.sub(r"\s+", " ", text)
    if len(text) > MAX_DEF:
        text = text[:MAX_DEF].rstrip() + "…"
    return text


def english_words(text: str) -> list:
    out = []
    for m in re.finditer(r"[A-Za-z][A-Za-z'\-]{2,}", text):
        word = m.group(0).strip("'-").lower()
        if len(word) < 3 or word in STOPWORDS:
            continue
        if word not in out:
            out.append(word)
    return out


NOISE_PREFIX = re.compile(
    r"^(to|to be|a|an|the|abbr\.|slang|lit\.|fig\.|CL|surname|variant of|see also)\b",
    re.IGNORECASE,
)
PURE_EN = re.compile(r"^[A-Za-z][A-Za-z'\- ]{1,48}$")


def english_head(gloss: str):
    """Only accept a gloss that IS the English word/phrase (direct equivalent).

    Reverse-indexing arbitrary gloss words produced nonsense pairs
    ("apple" -> 喉结 because of "Adam's apple"), so the English head is taken
    only when the whole gloss (parentheticals and leading particles removed) is
    a short English term of at most two words.
    """
    text = re.sub(r"\([^)]*\)", "", gloss).strip()
    text = re.sub(r"\s+", " ", text)
    text = NOISE_PREFIX.sub("", text).strip()
    if not text or not PURE_EN.match(text):
        return None
    words = text.split()
    if not words or len(words) > 2:
        return None
    return text.lower()


def main() -> None:
    raw = open(SRC, encoding="utf-8").read()
    raw = raw.strip()
    prefix = "export default "
    if raw.startswith(prefix):
        raw = raw[len(prefix):]
    data = json.loads(raw)
    entries = data["all"] if isinstance(data, dict) else data
    print("raw entries:", len(entries))

    cn_rows = []
    reverse = defaultdict(list)
    seen_cn = set()

    for item in entries:
        if len(item) < 4:
            continue
        traditional, simplified, pinyin, definition = item[0], item[1], item[2], item[3]
        head = (simplified or traditional or "").strip()
        if not head or len(head) > MAX_CN_LEN:
            continue
        if not re.search(r"[一-鿿]", head):
            continue  # skip pure symbols / ASCII entries
        if head in seen_cn:
            continue
        seen_cn.add(head)

        glosses = glosses_of(definition)
        if not glosses:
            continue
        senses = [clean_definition(g) for g in glosses[:MAX_SENSES]]
        text = " / ".join(senses)
        phonetic = pinyin_pretty(pinyin or "")
        if not text or not text.strip():
            continue
        cn_rows.append((head, phonetic, text))

        head_word = english_head(glosses[0])
        if head_word:
            bucket = reverse[head_word]
            label = f"{head} {phonetic}".strip()
            if label not in bucket and len(bucket) < MAX_CN_PER_EN:
                bucket.append(label)

    print("chinese entries:", len(cn_rows))
    print("english entries:", len(reverse))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for head, phonetic, text in cn_rows:
            f.write(f"{head}\t{phonetic}\t{text}\n")
        for word in sorted(reverse):
            f.write(f"{word}\t\t{'、'.join(reverse[word])}\n")

    size = os.path.getsize(OUT)
    print("output:", OUT, size, "bytes (%.1f MB)" % (size / 1024 / 1024))


if __name__ == "__main__":
    main()
