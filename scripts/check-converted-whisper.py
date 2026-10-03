#!/usr/bin/env python3
"""Audit every FP16 GGML tensor against the pinned source safetensors (numpy + safetensors)."""
import argparse
import math
import struct
from pathlib import Path
import numpy as np
from safetensors.numpy import load_file


def ggml_name(name):
    if name.startswith('model.'): name = name[6:]
    parts = name.split('.')
    if len(parts) > 2 and parts[1] == 'layers':
        parts[1] = 'blocks'
        component = '.'.join(parts[3:-1])
        mapping = {'self_attn.k_proj': 'attn.key', 'self_attn.q_proj': 'attn.query',
                   'self_attn.v_proj': 'attn.value', 'self_attn.out_proj': 'attn.out',
                   'self_attn_layer_norm': 'attn_ln', 'encoder_attn.k_proj': 'cross_attn.key',
                   'encoder_attn.q_proj': 'cross_attn.query', 'encoder_attn.v_proj': 'cross_attn.value',
                   'encoder_attn.out_proj': 'cross_attn.out', 'encoder_attn_layer_norm': 'cross_attn_ln',
                   'fc1': 'mlp.0', 'fc2': 'mlp.2', 'final_layer_norm': 'mlp_ln'}
        return '.'.join(parts[:3] + [mapping[component]] + parts[-1:])
    return name.replace('.layer_norm.', '.ln_post.' if name.startswith('encoder.') else '.ln.') \
        .replace('.embed_positions.weight', '.positional_embedding').replace('.embed_tokens.weight', '.token_embedding.weight')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('converted', type=Path)
    args = parser.parse_args()
    weights = {ggml_name(name): tensor for name, tensor in load_file(args.source).items() if name != 'proj_out.weight'}
    seen = set()
    with args.converted.open('rb') as source:
        header = struct.unpack('<12i', source.read(48))
        assert header == (0x67676d6c, 51865, 1500, 768, 12, 12, 448, 768, 12, 12, 80, 1)
        rows, columns = struct.unpack('<2i', source.read(8))
        source.seek(rows * columns * 4, 1)
        vocabulary, = struct.unpack('<i', source.read(4))
        for _ in range(vocabulary):
            length, = struct.unpack('<i', source.read(4)); source.seek(length, 1)
        while raw := source.read(12):
            ndim, name_length, ftype = struct.unpack('<3i', raw)
            assert 1 <= ndim <= 3 and ftype in [0, 1]
            shape = struct.unpack('<' + 'i' * ndim, source.read(ndim * 4))[::-1]
            name = source.read(name_length).decode()
            assert name in weights and name not in seen, name
            dtype = np.dtype('<f4' if ftype == 0 else '<f2')
            actual = np.frombuffer(source.read(math.prod(shape) * dtype.itemsize), dtype=dtype).reshape(shape)
            expected = weights[name].astype(np.float16).astype(dtype).reshape(shape)
            assert np.isfinite(actual).all() and np.array_equal(actual, expected), name
            seen.add(name)
    assert seen == weights.keys(), str(weights.keys() - seen)
    print(f'Conversion audit passed: {len(seen)} tensors match source weights at the upstream conversion precision.')


if __name__ == '__main__':
    main()
