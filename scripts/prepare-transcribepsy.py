#!/usr/bin/env python3
"""Reproduce the allowlisted FP16 and Q8 GGUFs from the original, pinned weights.

Run inside a conversion venv with torch==2.9.1, gguf==0.17.1,
sentencepiece==0.2.1, numpy==2.5.1 and PyYAML==6.0.1. Output stays outside Git.
Downloads are public and explicit. No credentials, conversion from Q8, or upload.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
from urllib.request import urlopen

REPO = Path(__file__).resolve().parent.parent
META = json.loads((REPO / 'inference-transcribepsy/src/main/assets/transcribepsy-model-preparation.json').read_text())


def verified(path, digest, size=None):
    if not path.is_file() or (size is not None and path.stat().st_size != size):
        return False
    with path.open('rb') as data:
        return hashlib.file_digest(data, 'sha256').hexdigest() == digest


def acquire(url, path, digest, size=None):
    if verified(path, digest, size):
        return
    part = path.with_suffix(path.suffix + '.part')
    try:
        with urlopen(url, timeout=120) as response, part.open('wb') as output:
            shutil.copyfileobj(response, output)
        if not verified(part, digest, size):
            raise ValueError('Pinned download verification failed')
        part.replace(path)
    finally:
        part.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--quant', choices=['both', 'f16', 'q8_0'], default='both')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(REPO):
        parser.error('Choose an output directory outside the repository')
    output.mkdir(parents=True, exist_ok=True)
    revision = META['runtimeRevision']
    source = output / ('qvac-transcribepsy-' + revision)
    archive = output / 'runtime-source.tar.gz'
    acquire('https://codeload.github.com/tether-ai-research/qvac-transcribepsy/tar.gz/' + revision,
            archive, META['runtimeArchiveSha256'])
    # Re-extract verified source rather than trusting a previously modified converter.
    if source.exists():
        shutil.rmtree(source)
    with tarfile.open(archive) as content:
        content.extractall(output, filter='data')
    checkpoint = output / 'TranscribePsy-250M-EU-sub.nemo'
    acquire('https://huggingface.co/qvac/TranscribePsy-250M-EU-sub/resolve/' +
            META['checkpointRevision'] + '/' + checkpoint.name,
            checkpoint, META['checkpointSha256'], META['checkpointBytes'])
    for quant, index in [('q8_0', 0), ('f16', 1)]:
        if args.quant not in ('both', quant):
            continue
        spec = META['outputs'][index]
        destination = output / spec['filename']
        subprocess.run([sys.executable, str(source / 'scripts/convert-nemo-to-gguf.py'),
                        '--ckpt', str(checkpoint), '--out', str(destination), '--quant', quant,
                        '--description', META['conversion']['description']], check=True)
        if not verified(destination, spec['sha256'], spec['bytes']):
            raise ValueError('Converted model differs from the reviewed catalog')
        print(spec['filename'] + ': verified')


if __name__ == '__main__':
    main()
