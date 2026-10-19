#!/usr/bin/env python3
"""Summarize a content-free Encoder lab JSON export. Never reads audio/models."""
import argparse
import json
import math
import re
import statistics
import sys


def summarize(report):
    if report.get('schema') != 1 or not isinstance(report.get('cases'), list):
        raise ValueError('Expected Encoder lab schema 1')
    if len(report['cases']) > 64:
        raise ValueError('Too many cases')
    rows = []
    for case in report['cases']:
        model, name = case['model'], case['id']
        if model not in ('transcribepsy-q8', 'transcribepsy-fp16'):
            raise ValueError('Unknown model identity')
        if not isinstance(name, str) or len(name) > 64 or not all(c.isalnum() or c == '-' for c in name):
            raise ValueError('Invalid case identity')
        if case.get('status') not in ('FINISHED', 'RUNNING', 'FAILED', 'CANCELLED', 'WORKER_FAILED', 'SKIPPED', 'INTERRUPTED'):
            raise ValueError('Invalid case status')
        if case.get('backend_requested') not in ('CPU', 'VULKAN'):
            raise ValueError('Invalid requested backend')
        samples = case.get('samples', [])
        if not isinstance(samples, list) or len(samples) > 4:
            raise ValueError('Invalid sample count')
        for i, sample in enumerate(samples):
            if sample['repeat'] != i:
                raise ValueError('Repetition order is invalid')
            for key in ('wall_ms', 'process_cpu_ms', 'relative_rmse', 'max_abs_error'):
                value = sample[key]
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or (value < 0 and not (key in ('relative_rmse', 'max_abs_error') and value == -1 and sample.get('finite') is False)):
                    raise ValueError('Invalid numeric measurement')
        warm = [sample['wall_ms'] for sample in samples[1:]]
        numerics = bool(samples) and all(sample.get('finite') is True for sample in samples)
        placement = bool(samples) and all(
            (sample.get('backend') == 'CPU' if case.get('backend_requested') == 'CPU' else isinstance(sample.get('backend'), str) and re.fullmatch(r'Vulkan[0-9]{1,2}', sample['backend']) is not None) for sample in samples
        )
        complete = len(samples) == 4 and case.get('status') == 'FINISHED'
        variant = case.get('variant')
        if type(variant) is not int or variant not in range(11):
            raise ValueError('Unknown native variant')
        same_work = variant != 10
        profile = case.get('profiled', False) or variant == 9
        rows.append(dict(model=model, name=name, samples=samples, median=statistics.median(warm) if warm else None,
                         low=min(warm) if warm else None, high=max(warm) if warm else None,
                         eligible=complete and numerics and placement and same_work and not profile,
                         complete=complete, numerics=numerics, placement=placement, profile=profile,
                         status=case.get('status'), error=max((s['relative_rmse'] for s in samples), default=None)))
    return rows


def render(report):
    rows = summarize(report)
    lines = ['Encoder-only measurements; setup and decoder excluded.',
             'First candidate call follows an untimed CPU reference. Warm = median of repetitions 1–3.',
             'Numerical error is relative to the same-format CPU encoder, not a speech-quality score.', '',
             '| Model | Case | First ms | Warm ms (range) | Relative RMS error | State |',
             '| --- | --- | ---: | ---: | ---: | --- |']
    for row in rows:
        first = f"{row['samples'][0]['wall_ms']:.1f}" if row['samples'] else '—'
        warm = f"{row['median']:.1f} ({row['low']:.1f}–{row['high']:.1f})" if row['median'] is not None else '—'
        error = f"{row['error']:.3g}" if row['error'] is not None and row['error'] >= 0 else '—'
        state = row['status']
        if row['profile']: state += '; profiling overhead'
        if not row['numerics']: state += '; invalid/missing numerics'
        if row['samples'] and not row['placement']: state += '; wrong backend'
        if len(row['samples']) != 4: state += '; incomplete'
        lines.append(f"| {row['model']} | {row['name']} | {first} | {warm} | {error} | {state} |")
    for model in sorted({r['model'] for r in rows}):
        matching = [r for r in rows if r['model'] == model]
        lines += ['', model]
        for prefix in ('cpu-4-', 'gpu-'):
            start = next((r for r in matching if r['name'] == prefix + 'start' and r['eligible']), None)
            end = next((r for r in matching if r['name'] == prefix + 'end' and r['eligible']), None)
            if start and end and start['median']:
                lines.append(f"Baseline drift {prefix.rstrip('-')}: {end['median']/start['median']:.2f}× (end/start).")
        valid = [r for r in matching if r['eligible'] and r['error'] <= 0.01]
        if valid:
            fastest = min(valid, key=lambda r: r['median'])
            lines.append(f"Fastest completed full-encoder case with relative RMS error ≤1%: {fastest['name']} ({fastest['median']:.1f} ms). This threshold is a screen, not acceptance.")
        for row in matching:
            if row['profile'] and row['samples']:
                ops = row['samples'][-1].get('gpu_op_ns', {})
                safe_ops = [(k, v) for k, v in ops.items() if isinstance(k, str) and k.isupper() and all(c.isalpha() or c == '_' for c in k) and isinstance(v, (int, float)) and math.isfinite(v) and v >= 0]
                hottest = sorted(safe_ops, key=lambda item: item[1], reverse=True)[:8]
                lines.append('Profiled GPU timestamp intervals (serialized, last repeat): ' + ', '.join(f'{k} {v/1e6:.2f} ms' for k, v in hottest))
    lines += ['', 'Host timers overlap (synchronization and shader registration are nested inside compute).',
              'Compare profiled hotspots with unprofiled cases; timestamps include dependency effects.',
              'Read thermal_status, battery_deci_c, CPU frequency samples and process_cpu_ms in the JSON before drawing conclusions.']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=argparse.FileType('r', encoding='utf-8'))
    args = parser.parse_args()
    try:
        raw = args.report.read(8_388_609)
        if len(raw) > 8_388_608: raise ValueError('Report too large')
        print(render(json.loads(raw)))
    except (ValueError, KeyError, TypeError):
        print('Invalid or incomplete Encoder lab JSON structure.', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
