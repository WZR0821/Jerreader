#!/usr/bin/env python3
"""Build a deterministic, attributed offline core from the pinned ECDICT CSV."""
import argparse
import csv
import hashlib
from pathlib import Path
import re

SHA256 = '1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf'
COMMIT = 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b'


def build(source, output, license_file):
    digest = hashlib.sha256()
    with source.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != SHA256:
        raise ValueError('Source does not match the pinned ECDICT release')
    candidates = []
    with source.open(encoding='utf-8', newline='') as stream:
        for row in csv.DictReader(stream):
            word = row['word']
            if not re.fullmatch(r"[a-z]+(?:[-'][a-z]+)*", word) or not row['translation'].strip():
                continue
            ranks = [int(row[k]) for k in ('bnc', 'frq') if row[k].isdigit() and int(row[k]) > 0]
            if ranks:
                candidates.append((min(ranks), word, row))
    selected = sorted(candidates, key=lambda item: (item[0], item[1]))[:8000]
    output.mkdir(parents=True, exist_ok=True)
    lines = ['# ECDICT frequency core: 8000 English headwords; MIT; ' + COMMIT]
    for _, word, row in selected:
        forms = []
        for value in row['exchange'].split('/'):
            if ':' in value:
                kind, variants = value.split(':', 1)
                # 0/1 encode source-lemma/form metadata, not alternative words.
                if kind in {'p', 'd', 'i', '3', 's', 'r', 't'}:
                    forms.extend(form for form in variants.split(',') if re.fullmatch(r"[a-z]+(?:[-'][a-z]+)*", form))
        fields = [word, row['phonetic'], row['pos'], row['translation'], '|'.join(sorted(set(forms)))]
        lines.append('\t'.join(value.replace('\t', ' ').replace('\r', '').replace('\n', r'\n') for value in fields))
    target = output / 'en-zh-basic.tsv'
    target.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    notice = ('English basic dictionary\nSource: https://github.com/skywind3000/ECDICT/tree/' + COMMIT +
              '\nSource CSV SHA-256: ' + SHA256 +
              '\nJerreader selects 8000 ranked English headwords and preserves Chinese definitions, readings and inflections.\n\n')
    (output / 'en-zh-NOTICE.txt').write_text(notice + license_file.read_text(encoding='utf-8'), encoding='utf-8')
    print(f'{len(selected)} headwords; {target.stat().st_size} bytes; SHA-256 {hashlib.sha256(target.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=Path('.build/ecdict.csv'))
    parser.add_argument('--license', type=Path, default=Path('.build/ecdict-license.txt'))
    parser.add_argument('--output', type=Path, default=Path('androidApp/src/main/assets'))
    args = parser.parse_args()
    build(args.source, args.output, args.license)
