#!/usr/bin/env python3
"""Summarize a content-free Encoder lab JSON export. Never reads audio/models."""
import argparse
import json
import math
import re
import statistics
import sys


def is_kernel(variant):
    return variant in range(20, 30) or variant in range(40, 47)


def experiment_route(variant, counts, shape=None):
    if variant in (31, 41): return counts.get('blocking_final_fence', 0) > 0
    if variant in (32, 42): return counts.get('tile256_routes', 0) > 0
    if variant in (33, 34, 43, 44):
        factor = 2 if variant in (33, 43) else 4
        # Other shapes intentionally retain split=1; do not call that a failed route.
        if shape is not None and shape[::2] != (640, 2560): return counts.get('effective_split_1', 0) > 0
        return counts.get(f'effective_split_{factor}', 0) > 0
    if variant in (35, 45): return counts.get('dense_bias_routes', 0) > 0
    if variant in (36, 46): return counts.get('dense_silu_routes', 0) > 0
    return True


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
        variant = case.get('variant')
        if type(variant) is not int or variant not in (*range(17), *range(20,37), *range(40,47), *range(60,64), 70):
            raise ValueError('Unknown native variant')
        special = variant in range(60,64) or variant == 70
        packed = packed_rows(case) if variant in range(60,64) else []
        capability = capability_row(case) if variant == 70 else None
        if special and case.get('samples', []): raise ValueError('Unexpected full encoder samples')
        kernels = (focused_rows(case) if variant in range(40,47) else kernel_rows(case)) if is_kernel(variant) else []
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
        same_work = variant != 10
        profile = case.get('profiled', False) or variant == 9
        tile_counter = 'forced_small_tile_routes' if variant in (11,13) else 'forced_large_tile_routes' if variant == 12 else 'forced_rectangular_tile_routes' if variant in (15,16) else None
        routing = (tile_counter is None or any(s.get('counts',{}).get(tile_counter,0) > 0 for s in samples)) and any(experiment_route(variant, s.get('counts',{})) for s in samples)
        if variant in (15,16):
            tile = 'tile_64x32' if variant == 15 else 'tile_32x64'
            routing = routing and any(s.get('counts',{}).get(tile,0) > 0 for s in samples)
        rows.append(dict(model=model, name=name, samples=samples, median=statistics.median(warm) if warm else None,
                         low=min(warm) if warm else None, high=max(warm) if warm else None,
                         eligible=not special and complete and numerics and placement and routing and same_work and not profile,
                         complete=complete, numerics=numerics, placement=placement, routing=routing, profile=profile,
                         kernels=kernels, packed=packed, capability=capability, variant=variant,
                         status=case.get('status'), error=max((s['relative_rmse'] for s in samples), default=None)))
    return rows


def number(value, sentinel=False):
    if type(value) not in (float,int) or not math.isfinite(value) or (value < 0 and not (sentinel and value == -1)):
        raise ValueError('Invalid kernel measurement')
    return value


def kernel_rows(case):
    kernels = case.get('kernels', [])
    if not isinstance(kernels,list) or len(kernels) > 32:
        raise ValueError('Invalid kernel count')
    rows = []
    seen = set()
    variant = case['variant']
    for i,kernel in enumerate(kernels):
        shape = tuple(kernel.get(k) for k in ('m','n','k'))
        if (kernel.get('sequence') != i or any(type(v) not in (float,int) for v in shape)
                or shape[::2] not in ((2560,640),(640,2560),(1920,640),(640,640))
                or shape[1] not in (32,64,65,128,129,139,256,384) or shape in seen):
            raise ValueError('Invalid kernel identity')
        seen.add(shape)
        streams = kernel.get('streams')
        if streams != (8 if variant == 26 else 1):
            raise ValueError('Invalid stream count')
        source = 'q8_0' if case['model'] == 'transcribepsy-q8' else 'f16'
        if kernel.get('source_type') != source or kernel.get('storage_type') != ('f16' if variant == 24 else source):
            raise ValueError('Wrong kernel weight type')
        if kernel.get('activation_row_padding') != (32 if variant == 25 else 0) or kernel.get('f32_accumulation') is not (variant >= 23):
            raise ValueError('Wrong kernel layout or precision')
        placement = (kernel.get('backend') == 'CPU' if case['backend_requested'] == 'CPU' else
                     isinstance(kernel.get('backend'),str) and re.fullmatch(r'Vulkan[0-9]{1,2}',kernel['backend']) is not None)
        times = kernel.get('wall_ms')
        for key in ('wall_ms','process_cpu_ms','readback_check_ms'):
            values = kernel.get(key)
            if not isinstance(values,list) or len(values) != 6:
                raise ValueError('Incomplete kernel repetitions')
            for value in values: number(value)
        finite = kernel.get('finite')
        if type(finite) is not bool: raise ValueError('Invalid finite flag')
        repacked = kernel.get('cpu_repacked',False)
        if type(repacked) is not bool or (repacked and case['backend_requested'] != 'CPU'):
            raise ValueError('Invalid CPU packing placement')
        error = number(kernel.get('relative_rmse'),not finite)
        number(kernel.get('max_abs_error'),not finite)
        for key in ('resident_bytes','setup_ms','reference_cells'): number(kernel.get(key))
        if kernel['reference_cells'] != 64 * streams: raise ValueError('Wrong reference coverage')
        for key in ('counts','shaders'):
            values = kernel.get(key)
            if not isinstance(values,dict) or len(values) > 100: raise ValueError('Invalid kernel counters')
            for name,value in values.items():
                if not re.fullmatch(r'[A-Za-z0-9_:.-]{1,120}',name): raise ValueError('Invalid counter label')
                number(value)
        median = statistics.median(times[1:])
        route = 'forced_small_tile_routes' if variant == 21 else 'forced_large_tile_routes' if variant == 22 else 'forced_rectangular_tile_routes' if variant in (28,29) else None
        routing = route is None or kernel.get('counts',{}).get(route,0) > 0
        if variant in (28,29):
            routing = routing and kernel.get('counts',{}).get('tile_64x32' if variant == 28 else 'tile_32x64',0) > 0
        rows.append(dict(shape=shape, streams=streams, first=times[0], median=median,cpu_repacked=repacked,
                         low=min(times[1:]),high=max(times[1:]),error=error,finite=finite,
                         eligible=case.get('status') == 'FINISHED' and len(kernels) == 32 and finite and placement and routing and 0 <= error <= 0.01,
                         gflops=2 * math.prod(shape) * streams / (median * 1e6) if median else 0))
    return rows


def focused_rows(case):
    kernels = case.get('kernels', [])
    if not isinstance(kernels, list) or len(kernels) > 8: raise ValueError('Invalid focused count')
    rows = []
    variant = case['variant']
    for i, kernel in enumerate(kernels):
        frames = kernel.get('encoder_frames')
        if type(frames) is not int or not 1 <= frames <= 4096: raise ValueError('Invalid encoder length')
        expected = [(2560,frames,640),(640,frames,2560),(640,frames,1280),(640,2*frames-1,640),
                    (64,63,64),(65,65,96),(640,129,2560),(640,257,2560)]
        shape = tuple(kernel.get(key) for key in ('m','n','k'))
        if kernel.get('sequence') != i or shape != expected[i]: raise ValueError('Invalid focused shape')
        source = 'q8_0' if case['model'] == 'transcribepsy-q8' else 'f16'
        cells = shape[0] * shape[1]
        if (kernel.get('focused') is not True or kernel.get('shape_family') != ('recording' if i < 4 else 'boundary')
                or kernel.get('streams') != 1 or kernel.get('activation_row_padding') != 0
                or kernel.get('source_type') != source or kernel.get('storage_type') != source
                or kernel.get('f32_accumulation') is not True or kernel.get('cpu_repacked') is not False
                or kernel.get('reference_mode') != 'double_dot_f32_bias_silu'
                or kernel.get('full_reference') is not (cells <= 8192)
                or kernel.get('reference_cells') != (cells if cells <= 8192 else 64)
                or kernel.get('bias_requested') is not True or kernel.get('activation_requested') != 'silu'
                or kernel.get('fusion_requested') != (1 if variant == 45 else 2 if variant == 46 else 0)):
            raise ValueError('Invalid focused work or reference')
        split = (2 if variant == 43 else 4 if variant == 44 else 1) if shape[::2] == (640,2560) else 1
        if kernel.get('split_requested') != split: raise ValueError('Wrong requested split')
        for key in ('wall_ms','process_cpu_ms','readback_check_ms','enqueue_us','fence_wait_us','boundary_gpu_ns'):
            values = kernel.get(key)
            if not isinstance(values, list) or len(values) != 6: raise ValueError('Incomplete focused repetitions')
            for value in values:
                if key == 'boundary_gpu_ns' and value is None: continue
                number(value)
        finite = kernel.get('finite')
        if type(finite) is not bool: raise ValueError('Invalid finite flag')
        for key in ('relative_rmse','max_abs_error'): number(kernel.get(key), not finite)
        compared = kernel.get('unfused_compared')
        if compared is not (variant in (45,46)): raise ValueError('Wrong unfused comparison contract')
        for key in ('unfused_relative_rmse','unfused_max_abs_error'):
            if compared: number(kernel.get(key), not finite)
            elif key not in kernel or kernel[key] is not None: raise ValueError('Invented unfused error')
        for key in ('resident_bytes','setup_ms','sampled_peak_pss_kib'): number(kernel.get(key), key == 'sampled_peak_pss_kib')
        for key in ('counts','shaders','us'):
            values = kernel.get(key)
            if not isinstance(values,dict) or len(values) > 100: raise ValueError('Invalid focused counters')
            for name,value in values.items():
                if not re.fullmatch(r'[A-Za-z0-9_:.-]{1,120}',name): raise ValueError('Invalid counter label')
                number(value)
        placement = case['backend_requested'] == 'VULKAN' and isinstance(kernel.get('backend'),str) and re.fullmatch(r'Vulkan[0-9]{1,2}', kernel['backend']) is not None
        routing = experiment_route(variant,kernel['counts'],shape)
        error = kernel['relative_rmse']
        baseline_error = kernel['unfused_relative_rmse'] if compared else 0
        times = kernel['wall_ms']
        warm_gpu = kernel['boundary_gpu_ns'][1:]
        rows.append(dict(shape=shape, streams=1, first=times[0], median=statistics.median(times[1:]),
                         low=min(times[1:]), high=max(times[1:]), error=error, finite=finite,
                         eligible=case['status'] == 'FINISHED' and len(kernels) == 8 and finite and placement and routing and 0 <= error <= .01 and 0 <= baseline_error <= .01,
                         gpu_ms=statistics.median(warm_gpu)/1e6 if all(v is not None for v in warm_gpu) else None,
                         cpu_ms=statistics.median(kernel['process_cpu_ms'][1:]),
                         enqueue_ms=statistics.median(kernel['enqueue_us'][1:])/1000,
                         wait_ms=statistics.median(kernel['fence_wait_us'][1:])/1000, routing=routing))
    return rows


def render_focused(rows):
    cases = [r for r in rows if r['variant'] in range(40,47)]
    if not cases: return []
    lines = ['', 'Focused GPU tuning: every case computes the same F32-accumulated dot + bias + SiLU.',
             'Recording shapes use native encoder length; boundary shapes check ragged dimensions.',
             'Small outputs have full double-reference checks; larger outputs check 64 cells plus full finiteness. Fusion variants also check full outputs against unfused GPU work.',
             'GPU interval is a graph boundary timestamp, not a sum of serialized per-operation profiles.',
             '', '| Case | M×N×K | Wall ms | GPU ms | Process CPU ms | Enqueue ms | Fence wait ms | State |',
             '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |']
    for case in cases:
        for k in case['kernels']:
            gpu = f"{k['gpu_ms']:.3f}" if k['gpu_ms'] is not None else 'unavailable'
            state = 'screened' if k['eligible'] else 'excluded'
            if not k['routing']: state += '; route not observed'
            lines.append(f"| {case['name']} | {'×'.join(str(int(v)) for v in k['shape'])} | {k['median']:.3f} | {gpu} | {k['cpu_ms']:.3f} | {k['enqueue_ms']:.3f} | {k['wait_ms']:.3f} | {state} |")
    lines += ['Wait/enqueue timers are nested in wall time. Missing timestamps remain unavailable.',
              'Fusion, split-K and tile changes are isolated; their gains cannot be multiplied.',
              'A synthetic gain requires matching full-encoder timing, routing and numerics before a product change.']
    return lines


def bounded_number(value, maximum=1e12):
    value = number(value)
    if value > maximum: raise ValueError('Measurement exceeds bound')
    return value


def validate_environment(obj):
    bounds={'elapsed_ms':(0,1e15),'thermal_status':(0,6),'battery_deci_c':(-1000,2000),
            'battery_percent':(-1,100),'plugged':(-1,15)}
    if not isinstance(obj,dict) or set(obj)!=(set(bounds)|{'power_save','cpu_sample_khz'}): raise ValueError('Invalid environment fields')
    for key,(low,high) in bounds.items():
        if type(obj[key]) is not int or not low<=obj[key]<=high: raise ValueError('Invalid environment value')
    if type(obj['power_save']) is not bool: raise ValueError('Invalid power flag')
    values=obj['cpu_sample_khz']
    if not isinstance(values,list) or len(values)>8: raise ValueError('Invalid frequency samples')
    for value in values:
        if value is not None and (type(value) is not int or not 0<=value<=100000000): raise ValueError('Invalid frequency')


def packed_rows(case):
    records = case.get('packed', [])
    if not isinstance(records, list) or len(records) > 4 or (case['status'] == 'FINISHED' and len(records) != 4):
        raise ValueError('Incomplete packed shape set')
    rows = []
    for i, record in enumerate(records):
        if not isinstance(record, dict): raise ValueError('Invalid packed record')
        frames = record.get('encoder_frames')
        if i and frames != records[0].get('encoder_frames'): raise ValueError('Changing packed frame count')
        if type(frames) is not int or not 1 <= frames <= 1000: raise ValueError('Invalid packed frame count')
        n = 145 if case['variant'] == 61 else 201 if case['variant'] == 62 else frames
        shape = ((2560,n,640),(640,n,2560),(65,65,96),(63,63,64))[i]
        streams = 2 if case['variant'] == 63 else 1
        cells = shape[0]*shape[1]
        contract = dict(sequence=i,m=shape[0],n=shape[1],k=shape[2],streams=streams,
            complete_calls_per_repeat=streams,source_type='q8_0' if case['model']=='transcribepsy-q8' else 'f16',
            storage_type='f16',backend='Vulkan',control_backend='Vulkan',implementation='altiro_packed_register4_v1',
            weight_layout='m4_k_mlane',activation_layout='n4_k_nlane_f32',activation_input_rounding='f16_then_f32',
            f32_accumulation=True,workgroup=[8,8,1],register_tile=[4,4],dispatch=[(shape[0]+31)//32,(shape[1]+31)//32,1],
            packing_workgroup=[64,1,1],full_reference=cells<=8192,reference_cells=(cells if cells<=8192 else 64)*streams,
            reference_mode='double_dot_half_rounded_inputs',wall_includes_activation_pack=True,weight_pack_checked=True,
            activation_pack_checked=True,candidate_gpu_dispatches_per_repeat=streams*2,candidate_shared_memory_bytes=0,
            specialization_constants=[],physical_device_count=1,same_device_verified=True,
            execution_order='alternating_candidate_first_even')
        arrays = ('wall_ms','process_cpu_ms','activation_pack_wall_ms','activation_pack_gpu_ns','compute_gpu_ns',
                  'boundary_gpu_ns','readback_check_ms','control_wall_ms','control_boundary_gpu_ns')
        scalar = ('resident_bytes','scratch_bytes','control_resident_bytes','pipeline_setup_ms','setup_ms',
                  'weight_pack_wall_ms','source_rounding_max_abs','host_reference_bytes')
        errors = ('relative_rmse','max_abs_error','control_relative_rmse','control_max_abs_error','control_reference_relative_rmse')
        allowed = set(contract) | set(arrays) | set(scalar) | set(errors) | {'encoder_frames','weight_pack_gpu_ns','finite','control_counts','control_shaders','lab','event','environment',
            'candidate_device','control_device','candidate_vendor_id','candidate_device_id','candidate_driver_version',
            'timestamp_valid_bits','timestamp_period_ns','candidate_memory_flags_all','candidate_all_memory_device_local','sampled_peak_pss_kib'}
        if set(record)-allowed: raise ValueError('Unknown packed field')
        for key, expected in contract.items():
            value = record.get(key)
            if type(value) is not type(expected) or value != expected: raise ValueError('Wrong packed work contract')
            if isinstance(value,list) and any(type(v) is not int for v in value): raise ValueError('Invalid dispatch shape')
        if 'event' in record and record['event'] != 'PACKED': raise ValueError('Wrong packed event')
        if 'lab' in record and record['lab'] is not True: raise ValueError('Wrong packed event')
        if i:
            for key in ('candidate_device','control_device','candidate_vendor_id','candidate_device_id','candidate_driver_version','timestamp_valid_bits','timestamp_period_ns'):
                if record.get(key) != records[0].get(key): raise ValueError('Changing packed device identity')
        if 'sampled_peak_pss_kib' in record:
            value=number(record['sampled_peak_pss_kib'],True)
            if value>1e12: raise ValueError('Invalid PSS sample')
        for key in ('candidate_device','control_device'):
            if not isinstance(record.get(key),str) or not re.fullmatch(r'[A-Za-z0-9 ._-]{0,64}',record[key]): raise ValueError('Unsafe packed device')
        for key in ('candidate_vendor_id','candidate_device_id','candidate_driver_version','timestamp_valid_bits','candidate_memory_flags_all'):
            value=record.get(key)
            if type(value) is not int or not 0<=value<=(64 if key=='timestamp_valid_bits' else 4294967295): raise ValueError('Invalid packed device property')
        if type(record.get('candidate_all_memory_device_local')) is not bool or record['candidate_memory_flags_all'] & 6 != 6 or record['candidate_all_memory_device_local'] != bool(record['candidate_memory_flags_all'] & 1): raise ValueError('Invalid memory placement')
        bounded_number(record.get('timestamp_period_ns'),1e9)
        if 'environment' in record: validate_environment(record['environment'])
        for key in arrays:
            values = record.get(key)
            if not isinstance(values,list) or len(values)!=6: raise ValueError('Incomplete packed repetitions')
            for value in values:
                if key.endswith('_gpu_ns') and value is None: continue
                bounded_number(value,1e18 if key.endswith('_ns') else 1e9)
        for key in scalar: bounded_number(record.get(key))
        if record.get('weight_pack_gpu_ns') is not None: bounded_number(record['weight_pack_gpu_ns'],1e18)
        elif 'weight_pack_gpu_ns' not in record: raise ValueError('Missing packed GPU interval')
        if type(record.get('finite')) is not bool: raise ValueError('Invalid packed finite flag')
        for key in errors:
            value = number(record.get(key),not record['finite'])
            if value>1e12: raise ValueError('Packed error exceeds bound')
        for key in ('control_counts','control_shaders'):
            values=record.get(key)
            if not isinstance(values,dict) or len(values)>100: raise ValueError('Invalid packed counters')
            for label,value in values.items():
                if not isinstance(label,str) or not re.fullmatch(r'[A-Za-z0-9_:.-]{1,120}',label): raise ValueError('Invalid packed counter')
                bounded_number(value)
        for whole, part in zip(record['wall_ms'],record['activation_pack_wall_ms']):
            if part > whole + 0.01: raise ValueError('Packing exceeds complete wall time')
        screen = (case['status']=='FINISHED' and record['finite'] and case['backend_requested']=='VULKAN'
                  and 0<=record['relative_rmse']<=.0001 and 0<=record['control_relative_rmse']<=.002
                  and 0<=record['control_reference_relative_rmse']<=.002)
        gpu=record['boundary_gpu_ns'][1:]
        rows.append(dict(shape=shape,streams=streams,eligible=screen,first=record['wall_ms'][0],
            median=statistics.median(record['wall_ms'][1:]),control=statistics.median(record['control_wall_ms'][1:]),
            packing=statistics.median(record['activation_pack_wall_ms'][1:]),
            gpu_ms=statistics.median(gpu)/1e6 if all(v is not None for v in gpu) else None,
            weight_pack=record['weight_pack_wall_ms'],scratch=record['scratch_bytes'],error=record['relative_rmse']))
    return rows


OPENCL_STATUS = set(('cancelled library_unavailable_in_app symbols_unavailable platform_query_failed platform_limit_exceeded '
    'device_query_failed device_limit_exceeded gpu_with_compiler_unavailable context_failed queue_failed buffer_failed '
    'program_failed compile_failed kernel_failed argument_failed enqueue_failed finish_failed read_failed verification_failed verified').split())
VULKAN_STATUS = set(('queried properties2_unavailable extension_query_failed version_query_failed instance_failed '
    'device_query_failed gpu_unavailable cancelled not_built').split())
CL_BOOLS = set('verified gpuSelected extensionsKnown fp16Extension integerDotExtension matrixMultiplyExtension fp16ConfigKnown fp16Arithmetic'.split())
VK_BOOLS = set(('int8QueryAvailable dotQueryAvailable shaderInt8 shaderFloat16 shaderIntegerDotProduct '
    'integerDotProduct8BitSignedAccelerated integerDotProduct8BitUnsignedAccelerated integerDotProduct8BitMixedSignednessAccelerated '
    'integerDotProduct4x8BitPackedSignedAccelerated integerDotProduct4x8BitPackedUnsignedAccelerated '
    'integerDotProduct4x8BitPackedMixedSignednessAccelerated integerDotProductAccumulatingSaturating4x8BitPackedSignedAccelerated').split())


def capability_row(case):
    result = case.get('capabilities')
    if result is None and case['status']!='FINISHED': return None
    if not isinstance(result,dict) or set(result)!={'opencl','vulkan'}: raise ValueError('Incomplete capability object')
    for api, statuses, booleans in (('opencl',OPENCL_STATUS,CL_BOOLS),('vulkan',VULKAN_STATUS,VK_BOOLS)):
        obj=result[api]
        text={'deviceName','deviceVendor','driverVersion'} if api=='opencl' else {'deviceName'}
        numbers={'maxWorkGroupSize','localMemoryBytes','compileUs','executeUs'} if api=='opencl' else set()
        if not isinstance(obj,dict) or set(obj)-({'api','status'}|booleans|text|numbers): raise ValueError('Unknown capability field')
        if obj.get('api')!=api or obj.get('status') not in statuses: raise ValueError('Invalid capability identity')
        for key in booleans & obj.keys():
            if type(obj[key]) is not bool: raise ValueError('Invalid capability flag')
        for key in text & obj.keys():
            if not isinstance(obj[key],str) or not re.fullmatch(r'[A-Za-z0-9 ._()+:-]{0,80}',obj[key]): raise ValueError('Unsafe capability identifier')
        for key in numbers & obj.keys():
            if type(obj[key]) is not int or not 0<=obj[key]<=2147483647: raise ValueError('Invalid capability integer')
        if api=='opencl':
            if type(obj.get('verified')) is not bool or obj['verified'] != (obj['status']=='verified'): raise ValueError('Contradictory OpenCL result')
            if obj['verified'] and (obj.get('gpuSelected') is not True or not {'compileUs','executeUs'}<=obj.keys()): raise ValueError('Missing OpenCL execution evidence')
            for flag,gate in (('fp16Extension','extensionsKnown'),('integerDotExtension','extensionsKnown'),('matrixMultiplyExtension','extensionsKnown'),('fp16Arithmetic','fp16ConfigKnown')):
                if flag in obj and obj.get(gate) is not True: raise ValueError('Ungated OpenCL claim')
        else:
            if obj['status']=='queried' and not {'int8QueryAvailable','dotQueryAvailable'}<=obj.keys(): raise ValueError('Missing Vulkan query gates')
            for key in (booleans-{'int8QueryAvailable','dotQueryAvailable'}) & obj.keys():
                gate='int8QueryAvailable' if key in ('shaderInt8','shaderFloat16') else 'dotQueryAvailable'
                if obj.get(gate) is not True: raise ValueError('Ungated Vulkan claim')
    return result


def render_packed(rows):
    cases=[r for r in rows if r['variant'] in range(60,64)]
    if not cases: return []
    lines=['','Packed synthetic GEMM: same F16-rounded weights and activations; first call + five warm calls.',
        'Candidate wall time includes activation packing, GPU submissions and waits. Control uses the same inputs and call count.',
        'Weight packing/setup is separate. GPU intervals may be unavailable; synthetic results never rank as full encoder latency.',
        '', '| Model | Case | M×N×K | Calls | First ms | Warm wall ms | Control ms | Pack ms | GPU ms | Weight pack ms | Scratch bytes | State |',
        '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for case in cases:
        for row in case['packed']:
            gpu=f"{row['gpu_ms']:.3f}" if row['gpu_ms'] is not None else 'unavailable'
            lines.append(f"| {case['model']} | {case['name']} | {'×'.join(map(str,row['shape']))} | {row['streams']} | {row['first']:.3f} | {row['median']:.3f} | {row['control']:.3f} | {row['packing']:.3f} | {gpu} | {row['weight_pack']:.3f} | {row['scratch']:.0f} | {'screened' if row['eligible'] else 'excluded'} |")
    return lines


def render_capabilities(rows):
    cases=[r for r in rows if r['variant']==70]
    if not cases: return []
    lines=['','GPU capability probes: app-visible access only. OpenCL verification is one fixed FP32 kernel, not a model backend.',
        'Vulkan semantic support and advertised acceleration are separate; neither establishes measured throughput.',
        '', '| Case | API | Field | Value |','| --- | --- | --- | --- |']
    for case in cases:
        if case['capability'] is None:
            lines.append(f"| {case['name']} | — | status | incomplete |")
            continue
        for api,obj in case['capability'].items():
            for key,value in sorted(obj.items()):
                if key=='api': continue
                lines.append(f"| {case['name']} | {api} | {key} | {str(value).lower() if type(value) is bool else value} |")
    return lines


def render(report):
    rows = summarize(report)
    lines = ['Encoder-only measurements; setup and decoder excluded.',
             'First candidate call follows an untimed CPU reference. Warm = median of repetitions 1–3.',
             'Numerical error is relative to the same-format CPU encoder, not a speech-quality score.', '',
             '| Model | Case | First ms | Warm ms (range) | Relative RMS error | State |',
             '| --- | --- | ---: | ---: | ---: | --- |']
    if report.get('live_relay_enabled'):
        lines.insert(0, 'Live relay enabled: network activity can perturb benchmark timings.\n')
    for row in rows:
        if row['variant'] in range(60,64) or row['variant'] == 70:
            label = f"{len(row['packed'])}/4 packed shapes" if row['variant'] != 70 else 'capability probe'
            lines.append(f"| {row['model']} | {row['name']} | — | {label} | — | {row['status']} |")
            continue
        if is_kernel(row['variant']):
            lines.append(f"| {row['model']} | {row['name']} | — | {len(row['kernels'])}/{8 if row['variant'] in range(40,47) else 32} kernel shapes | — | {row['status']} |")
            continue
        first = f"{row['samples'][0]['wall_ms']:.1f}" if row['samples'] else '—'
        warm = f"{row['median']:.1f} ({row['low']:.1f}–{row['high']:.1f})" if row['median'] is not None else '—'
        error = f"{row['error']:.3g}" if row['error'] is not None and row['error'] >= 0 else '—'
        state = row['status']
        if row['profile']: state += '; profiling overhead'
        if not row['numerics']: state += '; invalid/missing numerics'
        if row['samples'] and not row['placement']: state += '; wrong backend'
        if not row['routing']: state += '; requested route not observed'
        if len(row['samples']) != 4: state += '; incomplete'
        lines.append(f"| {row['model']} | {row['name']} | {first} | {warm} | {error} | {state} |")
    for model in sorted({r['model'] for r in rows}):
        matching = [r for r in rows if r['model'] == model]
        lines += ['', model]
        for prefix in ('cpu-4-', 'cpu-2-', 'gpu-', 'gpu-tuning-f32-'):
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
                shapes = row['samples'][-1].get('gpu_shapes_ns',{})
                safe = [(k,v) for k,v in shapes.items() if re.fullmatch(r'[A-Za-z0-9_:.-]{1,120}',k) and type(v) in (float,int) and math.isfinite(v) and v >= 0]
                for key,value in sorted(safe,key=lambda x:x[1],reverse=True)[:12]:
                    lines.append(f'  {key}: {value/1e6:.2f} ms (shape group; includes batches).')
        lines.extend(render_kernels(matching))
        lines.extend(render_focused(matching))
        lines.extend(render_packed(matching))
        lines.extend(render_capabilities(matching))
    lines += ['', 'Host timers overlap (synchronization and shader registration are nested inside compute).',
              'Compare profiled hotspots with unprofiled cases; timestamps include dependency effects.',
              'Read thermal_status, battery_deci_c, CPU frequency samples and process_cpu_ms in the JSON before drawing conclusions.']
    return '\n'.join(lines)


def render_kernels(rows):
    cases = [r for r in rows if r['variant'] in range(20,30)]
    if not cases: return []
    lines = ['', 'Synthetic kernel isolation: deterministic data, resident inputs, 1 first + 5 warm calls.',
             'Full outputs checked for finiteness; 64 cells per stream checked against double precision.',
             '1% error screen is triage only. No decoder, speech quality or end-to-end latency claim.',
             '', '| M×N×K | Stock GPU ms | Best screened single-stream case | ms | GPU gain | CPU ms |',
             '| --- | ---: | --- | ---: | ---: | ---: |']
    indexed = {r['variant']:{k['shape']:k for k in r['kernels']} for r in cases}
    names = {r['variant']:r['name'] for r in cases}
    for shape,base in indexed.get(20,{}).items():
        if not base['eligible'] or base['median'] <= 0: continue
        candidates = [(v,s[shape]) for v,s in indexed.items() if v in (20,21,22,23,24,28,29) and shape in s and s[shape]['eligible']
                      and (v != 24 or rows[0]['model'] == 'transcribepsy-q8')]
        if not candidates: continue
        v,best = min(candidates,key=lambda x:x[1]['median'])
        cpu = indexed.get(27,{}).get(shape)
        cpu_ms = f"{cpu['median']:.3f}" if cpu and cpu['eligible'] else '—'
        label = '×'.join(str(int(x)) for x in shape)
        lines.append(f"| {label} | {base['median']:.3f} | {names[v]} | {best['median']:.3f} | {base['median']/best['median']:.2f}× | {cpu_ms} |" if best['median'] > 0 else f'| {label} | — | Unresolvable timer | — | — | — |')
    for r in cases:
        if r['variant'] == 27:
            lines.append(f"CPU_REPACK observed for {sum(k['cpu_repacked'] for k in r['kernels'])}/{len(r['kernels'])} CPU shapes; otherwise the runtime's default buffer was used.")
        valid = [k for k in r['kernels'] if k['eligible']]
        if valid:
            peak = max(valid,key=lambda k:k['gflops'])
            lines.append(f"{r['name']}: best measured effective throughput {peak['gflops']:.1f} GFLOP/s at {peak['shape']} ({peak['streams']} streams; warm range {peak['low']:.3f}–{peak['high']:.3f} ms). This is workload throughput, not hardware peak.")
        for k in r['kernels']:
            if not k['eligible']: lines.append(f"Excluded {r['name']} {k['shape']}: incomplete case, placement, finite or numerical screen.")
    lines += ['Compare kernel-f32 with kernel-strided for layout cost, and with kernel-predequant for Q8 decode cost.',
              'kernel-streams uses eight distinct weights/inputs in ONE graph: report throughput per multiplication; it is not eight concurrent queues.',
              'Pre-dequant setup and extra storage are reported separately; a warm kernel gain does not establish a full-model gain.',
              'Shader/counter labels in each kernel record establish whether the requested tile route actually changed.']
    return lines


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
