#!/usr/bin/env python3
"""Static Mali-G710 analysis of the pinned scalar GEMM shaders; no GPU required.

Uses an explicitly supplied Arm Mali Offline Compiler (2026.5+ with -S).
Never downloads tools, models or device data. Reports are not phone timings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

SHADERS = (
    'matmul_f16_f32_aligned_f16acc', 'matmul_f16_f32_aligned',
    'matmul_q8_0_f32_aligned_f16acc', 'matmul_q8_0_f32_aligned',
)


def specialization_ids(data):
    """Optimized SPIR-V drops unused constants; malioc rejects those locations."""
    if len(data) < 20 or len(data)%4:
        raise ValueError('Truncated SPIR-V')
    words = struct.unpack('<'+'I'*(len(data)//4),data)
    if words[0] != 0x07230203: raise ValueError('Invalid SPIR-V magic')
    ids = set()
    pos = 5
    while pos < len(words):
        length,opcode = words[pos] >> 16, words[pos] & 65535
        if not length or pos+length > len(words): raise ValueError('Malformed SPIR-V instruction')
        if opcode == 71 and length == 4 and words[pos+2] == 1: ids.add(words[pos+3])
        pos += length
    return ids


def constants(tile, subgroup, quantized):
    """Non-coopmat specialization order from the patched pinned ggml source.

    These are candidate subgroup sizes, not a claim about a connected phone.
    See ggml_vk_load_shaders and vulkan-shaders/mul_mm.comp at 364ec361.
    """
    if tile == '256-thread64':
        if subgroup != 16:
            raise ValueError('256-thread64 is supported only at subgroup16')
        return [256,64,64,32,16,16,2,2,2,1,16]
    if tile == 'small':
        return [max(subgroup,32),32,32,32 if quantized else 16,
                subgroup if subgroup in (8,16) else 32,32,2,2,2,1,max(subgroup,8)]
    if tile == 'medium':
        return [128,64,64,32 if quantized else 16,max(subgroup,8),32,2,4,2,1,max(subgroup,8)]
    if tile == 'large':
        return [128,128,128,32 if quantized else 16,max(subgroup,8)*2,64,2,4,4,1,max(subgroup,8)]
    if tile in ('64x32','32x64'):
        m,n = (64,32) if tile == '64x32' else (32,64)
        return [128,m,n,32 if quantized else 16,max(subgroup,8),16,2,4,2,1,max(subgroup,8)]
    raise ValueError('Unknown tile')


def run(args):
    if args.output.resolve().is_relative_to(Path(__file__).resolve().parents[1]):
        raise ValueError('Generated reports must be outside the repository')
    args.output.mkdir(parents=True,exist_ok=True)
    # Refuse accidental overwrite of an earlier campaign.
    manifest_path = args.output / 'manifest.json'
    if manifest_path.exists(): raise ValueError('Output already contains a campaign')
    def command(argv):
        return subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=180)
    version = command([args.malioc,'--version'])
    if version.returncode: raise ValueError('Cannot run supplied Mali Offline Compiler')
    info = command([args.malioc,'--info','--core','Mali-G710','--format','json'])
    if info.returncode: raise ValueError('Compiler does not support Mali-G710')
    (args.output/'target.json').write_text(info.stdout)
    manifest = dict(schema=1,runtime='transcribepsy-364ec361',compiler=version.stdout.strip(),
                    measurement='Static shader analysis; not GPU execution, driver profiling or phone latency',
                    caveat='Specializations are candidates until matched to phone subgroup/tile telemetry. Driver float-control patches are not applied.',
                    cases=[])
    failed = False
    for shader in SHADERS:
        source = args.shader_dir/(shader+'.spv')
        data = source.read_bytes()
        ids = specialization_ids(data)
        digest = hashlib.sha256(data).hexdigest()
        # Retain exact inputs with their hashes, never proprietary compiler binaries.
        (args.output/source.name).write_bytes(data)
        for subgroup in args.subgroups:
            for tile in args.tiles:
                specs = constants(tile,subgroup,'q8_0' in shader)
                name = f'{shader}-{tile}-sg{subgroup}'
                cmd = [args.malioc,'--vulkan','--compute','--spirv','--core','Mali-G710','--format','json']
                for i,value in enumerate(specs):
                    if i in ids: cmd += ['-S',f'{i}={value}']
                cmd += [str(source)]
                result = command(cmd)
                (args.output/(name+'.json')).write_text(result.stdout)
                # Diagnostics contain tool paths; retained outside Git only.
                if result.stderr: (args.output/(name+'.stderr.txt')).write_text(result.stderr)
                manifest['cases'].append(dict(shader=shader,sha256=digest,tile=tile,subgroup=subgroup,
                                              specialization_constants=specs,applied_locations=sorted(ids),exit_code=result.returncode,report=name+'.json'))
                failed |= result.returncode != 0
                manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
    print(f"Static analysis: {len(manifest['cases'])} cases; failures={sum(c['exit_code'] != 0 for c in manifest['cases'])}")
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--malioc',required=True,help='Compiler executable or host wrapper')
    parser.add_argument('--shader-dir',required=True,type=Path,help='Pinned build vulkan-shaders.spv directory')
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--subgroups',nargs='+',type=int,choices=(8,16,32),default=[8,16,32])
    parser.add_argument('--tiles',nargs='+',choices=('small','medium','large','64x32','32x64','256-thread64'),default=['small','medium','large'])
    args = parser.parse_args()
    try: return run(args)
    except (OSError,ValueError,subprocess.TimeoutExpired) as error:
        print(f'Static analysis stopped: {type(error).__name__}. See any partial manifest.',file=sys.stderr)
        return 2


if __name__ == '__main__': sys.exit(main())
