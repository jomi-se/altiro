#!/usr/bin/env python3
"""Embed exact pinned ACL OpenCL sources, preserving upstream code and MIT notices.

Only textual include expansion is performed; no kernel transformations. This
script never downloads. CMake verifies the archive hash before invoking it.
"""
import argparse
import hashlib
from pathlib import Path
import re

REVISION = '80bbedb606746dcc1451cffedf3931d9fec3f53c'
ARCHIVE_SHA256 = '1afeccd2c8f757cc26b130360d62b7f0a5da590d732626a94f385c5070b3fb92'
HASHES = {
    'common/gemm.cl': '6a96761738490893940ce257a128c9cd2d8bb837bdb7c2e2db7ac5646b2906e3',
    'gemm_helpers.h': '5e854e7fff8dbd12c288163b4344383174a853d27b6faefb859cc8e3298f9d6c',
    'activation_float_helpers.h': 'ee0d9eb4eec3b73b12cb50d5b76aaea1b948ef61cb8b443c1b062d84bb58fd51',
    'helpers.h': 'fac0e18050a64c4f53c1f8d62e5c4a62d0ca2130fa40dd6bc20a235e8fc835ed',
    'load_store_utility.h': 'def200273efe033e667467b9df20dd77406c586d11bdf8367dfa84f3a0ea09a7',
    'repeat.h': 'caec697d4a179e23033922c52dafbde133a882446158bea8a03d0c70bd0cb5b3',
    'common/gemm_utils.cl': '524dff0719f575d63cdd57d97fae1116a956cc11789cdb35315884bcc37c031c',
    'tile_helpers.h': 'f76873803ccbe11030c6eada6ed910b697918a21e3b625ff1261ae2832885902',
}
INCLUDE = re.compile(r'^#include "([^"]+)"', re.MULTILINE)


def sources(root):
    base = root / 'src/core/CL/cl_kernels'
    checked = {}
    for name, digest in HASHES.items():
        data = (base / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Pinned ACL source hash mismatch: ' + name)
        checked[name] = data.decode('utf-8')

    def expand(name, stack=()):
        if name not in checked or name in stack:
            raise ValueError('Unexpected ACL include')
        return INCLUDE.sub(lambda match: expand(match[1], stack + (name,)), checked[name])

    return {name: expand(path) for name, path in (
        ('gemm', 'common/gemm.cl'), ('reshape', 'common/gemm_utils.cl'))}


def prepare(root, output):
    expanded = sources(root)
    lines = ['// Generated from pinned upstream ACL; do not edit.', '#pragma once',
             'static constexpr const char* altiro_acl_revision = "' + REVISION + '";']
    for name, text in expanded.items():
        if ')ACL_SOURCE"' in text or len(text) > 2_000_000:
            raise ValueError('Unsafe source envelope')
        lines.append('static constexpr const char* altiro_acl_' + name + '_source = R"ACL_SOURCE(' + text + ')ACL_SOURCE";')
    generated = ('\n'.join(lines) + '\n').encode('utf-8')
    if not output.exists() or output.read_bytes() != generated:
        output.write_bytes(generated)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    prepare(args.source, args.output)
