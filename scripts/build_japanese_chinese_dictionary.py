#!/usr/bin/env python3
"""Build the bundled CC BY-SA 4.0 lexicon from a local Kaikki raw dump.

No networking at build or test time. Install opencc-python-reimplemented==0.1.7
in a separate build environment. Only Chinese glosses/POS/aliases are simplified;
Japanese headwords and readings are never passed through Chinese conversion.
"""
import argparse
import gzip
import hashlib
import json
import re
import unicodedata
from pathlib import Path

from opencc import OpenCC

KANA = re.compile(r"[ぁ-ゖァ-ヺー・]+\Z")
HAN = re.compile(r"[\u3400-\u9fff]{1,12}\Z")
POS = {"noun": "名词", "verb": "动词", "adj": "形容词", "adj_noun": "形容动词",
       "adv": "副词", "pron": "代词", "particle": "助词", "conj": "连词",
       "intj": "感叹词", "phrase": "短语", "prefix": "前缀", "suffix": "后缀",
       "name": "专有名词", "num": "数词", "det": "连体词", "proverb": "谚语"}
SOURCE_SHA256 = "70c62c00f4746c966fa3ad191a0553240fadc617d7d9a76aa11fca9b399fbb79"


def clean(text):
    return re.sub(r"\s+", " ", text.replace("\t", " ").replace("|", "／")).strip()


def kana_reading(form):
    text = form.get("hiragana", "")
    if text and KANA.fullmatch(text):
        return text
    text = form.get("form", "")
    for written, ruby in form.get("ruby", []):
        text = text.replace(written, ruby, 1)
    return text if KANA.fullmatch(text) else ""


def build(source, output):
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    source_hash = digest.hexdigest()
    if source_hash != SOURCE_SHA256:
        raise ValueError("Source does not match the pinned 2026-09-01 snapshot; do not label a different dump with this version.")
    converter = OpenCC("t2s")
    rows = {}
    input_count = 0
    with gzip.open(source, "rt", encoding="utf-8") as stream:
        for line in stream:
            if '"ja"' not in line:
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "ja":
                continue
            input_count += 1
            term = clean(entry.get("word", ""))
            pos = entry.get("pos", "")
            if not term or pos in {"romanization", "character", "soft-redirect", "unknown"}:
                continue
            glosses, aliases = [], []
            for sense in entry.get("senses", []):
                # Redirects and alternate-form statements are not Chinese definitions.
                if sense.get("form_of") or sense.get("alt_of") or "no-gloss" in sense.get("tags", []):
                    continue
                for gloss in sense.get("glosses", []):
                    gloss = clean(converter.convert(gloss))
                    if not gloss or any(marker in gloss for marker in ["的旧字体形式", "的另一种写法", "的罗马字", "的其他写法"]):
                        continue
                    if gloss not in glosses:
                        glosses.append(gloss)
                    # Only whole short Chinese glosses are reverse keys. Never
                    # substring-search prose or strip sense restrictions/qualifiers.
                    for alias in re.split(r"[；，、;/]", gloss):
                        alias = alias.strip()
                        if HAN.fullmatch(alias) and alias not in aliases:
                            aliases.append(alias)
            if not glosses:
                continue
            canonical = [f for f in entry.get("forms", []) if "canonical" in f.get("tags", []) and f.get("form") == term]
            readings = list(dict.fromkeys(filter(None, (kana_reading(f) for f in canonical))))
            if not readings and KANA.fullmatch(term):
                readings = [term]
            if not readings:
                # Some raw entries expose a reading in sounds but no canonical ruby.
                readings = list(dict.fromkeys(s["other"] for s in entry.get("sounds", [])
                    if KANA.fullmatch(s.get("other", ""))))[:1]
            reading = "／".join(readings)
            forms = list(dict.fromkeys(clean(f.get("form", "")) for f in entry.get("forms", [])
                if f.get("form") and f.get("form") != term and f.get("source") == "inflection table"))
            key = (term, reading)
            row = rows.setdefault(key, {"pos": [], "glosses": [], "aliases": [], "forms": []})
            for label, values in [("pos", [POS.get(pos, clean(converter.convert(entry.get("pos_title", "词语"))))]),
                                  ("glosses", glosses), ("aliases", aliases), ("forms", forms)]:
                row[label].extend(v for v in values if v not in row[label])
    lines = ["# Jerreader Japanese–Chinese dictionary · CC BY-SA 4.0",
             "# Source: Chinese Wiktionary contributors via Kaikki/Wiktextract; see ja-zh-NOTICE.txt",
             "# term\treading\tpos\tChinese definitions\texact Chinese keys\tJapanese inflected forms\tsource page"]
    for (term, reading), row in sorted(rows.items()):
        lines.append("\t".join([term, reading, "／".join(row["pos"]), "；".join(row["glosses"]),
                                 "|".join(row["aliases"]), "|".join(row["forms"]), term]))
    payload = ("\n".join(lines) + "\n").encode()
    output.write_bytes(payload)
    metadata = {"version": "2026-09-01", "license": "CC-BY-SA-4.0", "inputJapaneseEntries": input_count,
                "entries": len(rows), "headwords": len({key[0] for key in rows}),
                "entriesWithReading": sum(bool(key[1]) for key in rows),
                "chineseKeys": len({a for row in rows.values() for a in row["aliases"]}),
                "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
                "sourceSHA256": source_hash,
                "sourceURL": "https://kaikki.org/zhwiktionary/raw-wiktextract-data.jsonl.gz",
                "changes": "Japanese entries only; omit redirect-only senses and quotations; merge duplicate senses; simplify Chinese glosses and POS with OpenCC; derive exact Chinese keys and inflection index."}
    output.with_suffix(".json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.source, args.output)
