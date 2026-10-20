import importlib.util
import json
import re
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('analysis', Path(__file__).parents[1] / 'analyze-encoder-lab.py')
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def case(name='cpu-4-start', backend='CPU', variant=0):
    return dict(model='transcribepsy-q8', id=name, backend_requested=backend, variant=variant, profiled=False, status='FINISHED',
                samples=[dict(repeat=i, wall_ms=1000 if i == 0 else 10 + i, process_cpu_ms=20,
                              relative_rmse=0, max_abs_error=0, finite=True, backend='CPU') for i in range(4)])


class AnalysisTest(unittest.TestCase):
    def report(self, value):
        return dict(schema=1, cases=[value])

    def test_first_call_is_excluded_from_warm_median(self):
        row = analysis.summarize(self.report(case()))[0]
        self.assertEqual(12, row['median'])
        self.assertTrue(row['eligible'])

    def test_partial_profiled_wrong_backend_and_short_cases_cannot_win(self):
        partial = case(); partial['samples'].pop()
        profiled = case(); profiled['profiled'] = True
        intrinsic_profile = case(variant=9)
        wrong = case(backend='VULKAN')
        invalid_gpu = case(backend='VULKAN')
        for sample in invalid_gpu['samples']: sample['backend'] = 'NOT_A_GPU'
        short = case(variant=10)
        nonfinite = case(); nonfinite['samples'][2]['finite'] = False
        for value in (partial, profiled, intrinsic_profile, wrong, invalid_gpu, short, nonfinite):
            self.assertFalse(analysis.summarize(self.report(value))[0]['eligible'])

    def test_invalid_measurements_do_not_become_a_speedup(self):
        for invalid in (float('nan'), float('inf'), -1, 'fast'):
            value = case(); value['samples'][1]['wall_ms'] = invalid
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))

    def test_native_invalid_feature_sentinel_preserves_the_rest_of_the_suite(self):
        invalid = case('gpu-bad', 'VULKAN')
        invalid['status'] = 'FAILED'
        invalid['samples'] = invalid['samples'][:1]
        invalid['samples'][0].update(finite=False, relative_rmse=-1, max_abs_error=-1)
        report = dict(schema=1, cases=[invalid, case()])
        rows = analysis.summarize(report)
        self.assertFalse(rows[0]['eligible'])
        self.assertTrue(rows[1]['eligible'])
        self.assertIn('invalid/missing numerics', analysis.render(report))

    def test_reordered_repetitions_are_rejected(self):
        value = case(); value['samples'][1]['repeat'] = 3
        with self.assertRaises(ValueError): analysis.summarize(self.report(value))

    def test_unknown_fields_are_never_rendered(self):
        value = case(); value['transcript'] = 'PRIVATE_TEXT_MARKER'
        report = self.report(value); report['audio_path'] = 'PRIVATE_PATH_MARKER'
        output = analysis.render(report)
        self.assertNotIn('PRIVATE_TEXT_MARKER', output)
        self.assertNotIn('PRIVATE_PATH_MARKER', output)

    def test_material_numerical_error_does_not_win(self):
        value = case()
        for sample in value['samples']: sample['relative_rmse'] = 0.5
        self.assertNotIn('Fastest completed', analysis.render(self.report(value)))

    def test_kernel_suite_is_not_mistaken_for_full_encoder_latency(self):
        value = kernel_case()
        row = analysis.summarize(self.report(value))[0]
        self.assertFalse(row['eligible'])
        self.assertEqual(32,len(row['kernels']))
        self.assertTrue(all(k['eligible'] for k in row['kernels']))
        output = analysis.render(self.report(value))
        self.assertIn('Synthetic kernel isolation',output)
        self.assertNotIn('Fastest completed',output)

    def test_kernel_numerics_placement_precision_and_repetitions_are_checked(self):
        for mutate in (
            lambda v:v.update(status='FAILED'),
            lambda v:v['kernels'][0].update(finite=False,relative_rmse=-1,max_abs_error=-1),
            lambda v:v['kernels'][0].update(backend='CPU'),
            lambda v:v['kernels'][0].update(relative_rmse=0.5),
        ):
            value = kernel_case(); mutate(value)
            self.assertFalse(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])
        for mutate in (
            lambda v:v['kernels'][0]['wall_ms'].pop(),
            lambda v:v['kernels'][0].update(f32_accumulation=True),
            lambda v:v['kernels'][0].update(streams=8),
            lambda v:v['kernels'][0].update(n=999),
            lambda v:v['kernels'][0].update(storage_type='f16'),
            lambda v:v['kernels'][0].update(relative_rmse=float('nan')),
        ):
            value = kernel_case(); mutate(value)
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))

    def test_rectangular_selection_requires_actual_dispatch_geometry(self):
        value = kernel_case()
        value.update(variant=28,id='kernel-64x32-f32')
        for k in value['kernels']:
            k.update(f32_accumulation=True,counts={'forced_rectangular_tile_routes':1,'tile_64x64':1})
        self.assertFalse(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])
        for k in value['kernels']: k['counts']['tile_64x32'] = 1
        self.assertTrue(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])


    def test_tuning_full_cases_remain_full_encoder_and_require_routes(self):
        for variant, counter in ((31,'blocking_final_fence'),(32,'tile256_routes'),(33,'effective_split_2'),
                                 (34,'effective_split_4'),(35,'dense_bias_routes'),(36,'dense_silu_routes')):
            value = case('gpu-tuning-test','VULKAN',variant)
            for sample in value['samples']: sample.update(backend='Vulkan0',counts={})
            self.assertFalse(analysis.summarize(self.report(value))[0]['eligible'])
            for sample in value['samples']: sample['counts'][counter] = 1
            row = analysis.summarize(self.report(value))[0]
            self.assertTrue(row['eligible'])
            self.assertFalse(row['kernels'])

    def test_focused_campaign_keeps_like_for_like_work_and_missing_timestamps(self):
        value = focused_case()
        row = analysis.summarize(self.report(value))[0]
        self.assertFalse(row['eligible'])
        self.assertEqual(8,len(row['kernels']))
        self.assertTrue(all(k['eligible'] for k in row['kernels']))
        output = analysis.render(self.report(value))
        self.assertIn('same F32-accumulated dot + bias + SiLU',output)
        self.assertNotIn('Fastest completed',output)
        value['kernels'][0]['boundary_gpu_ns'] = [None] * 6
        self.assertIsNone(analysis.summarize(self.report(value))[0]['kernels'][0]['gpu_ms'])
        self.assertIn('unavailable',analysis.render(self.report(value)))

    def test_focused_rejects_wrong_work_reference_shape_or_timing(self):
        for mutate in (
            lambda k:k.update(encoder_frames=144), lambda k:k.update(shape_family='other'),
            lambda k:k.update(reference_mode='double_dot'), lambda k:k.update(f32_accumulation=False),
            lambda k:k.update(fusion_requested=2), lambda k:k.update(split_requested=4),
            lambda k:k.update(activation_requested='none'), lambda k:k.update(reference_cells=999),
            lambda k:k.update(full_reference=True), lambda k:k['enqueue_us'].pop(),
            lambda k:k.update(boundary_gpu_ns=[-1]*6), lambda k:k.update(unfused_relative_rmse=float('nan')),
        ):
            value = focused_case(); mutate(value['kernels'][0])
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))

    def test_focused_unsupported_fusion_and_unfused_error_cannot_win(self):
        value = focused_case(46)
        self.assertFalse(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])
        for k in value['kernels']: k['counts']['dense_silu_routes'] = 1
        self.assertTrue(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])
        value['kernels'][0]['unfused_relative_rmse'] = .5
        self.assertFalse(analysis.summarize(self.report(value))[0]['kernels'][0]['eligible'])


def focused_case(variant=40):
    value = case('focused-test','VULKAN',variant)
    value.update(samples=[],kernels=[])
    frames = 145
    for m,n,k in ((2560,frames,640),(640,frames,2560),(640,frames,1280),(640,2*frames-1,640),
                  (64,63,64),(65,65,96),(640,129,2560),(640,257,2560)):
        i = len(value['kernels'])
        cells = m*n
        value['kernels'].append(dict(sequence=i,m=m,n=n,k=k,encoder_frames=frames,
            shape_family='recording' if i<4 else 'boundary',focused=True,streams=1,
            activation_row_padding=0,source_type='q8_0',storage_type='q8_0',f32_accumulation=True,cpu_repacked=False,
            reference_mode='double_dot_f32_bias_silu',full_reference=cells<=8192,reference_cells=cells if cells<=8192 else 64,
            bias_requested=True,activation_requested='silu',fusion_requested=1 if variant==45 else 2 if variant==46 else 0,
            split_requested=(2 if variant==43 else 4 if variant==44 else 1) if (m,k)==(640,2560) else 1,
            backend='Vulkan0',finite=True,relative_rmse=0,max_abs_error=0,unfused_compared=variant in (45,46),unfused_relative_rmse=0 if variant in (45,46) else None,unfused_max_abs_error=0 if variant in (45,46) else None,
            resident_bytes=10000,setup_ms=10,sampled_peak_pss_kib=1000,
            wall_ms=[1000,2,3,4,5,6],process_cpu_ms=[1]*6,readback_check_ms=[1]*6,
            enqueue_us=[100]*6,fence_wait_us=[1000]*6,boundary_gpu_ns=[1500000]*6,
            counts={},shaders={},us={}))
    return value


def kernel_case():
    value = case('kernel-stock','VULKAN',20)
    value['samples'] = []
    value['kernels'] = []
    for m,k in ((2560,640),(640,2560),(1920,640),(640,640)):
        for n in (32,64,65,128,129,139,256,384):
            value['kernels'].append(dict(sequence=len(value['kernels']),m=m,n=n,k=k,streams=1,
                source_type='q8_0',storage_type='q8_0',activation_row_padding=0,f32_accumulation=False,
                backend='Vulkan0',finite=True,relative_rmse=0,max_abs_error=0,
                resident_bytes=1000,setup_ms=10,reference_cells=64,
                wall_ms=[1000,2,3,4,5,6],process_cpu_ms=[1]*6,readback_check_ms=[1]*6,
                counts={},shaders={}))
    return value




def packed_case(variant=60):
    value=case('packed-test','VULKAN',variant)
    value.update(samples=[],packed=[])
    frames=145
    n=201 if variant==62 else frames
    streams=2 if variant==63 else 1
    for m,n,k in ((2560,n,640),(640,n,2560),(65,65,96),(63,63,64)):
        cells=m*n
        record=dict(sequence=len(value['packed']),m=m,n=n,k=k,streams=streams,complete_calls_per_repeat=streams,
            encoder_frames=frames,source_type='q8_0',storage_type='f16',backend='Vulkan',control_backend='Vulkan',
            implementation='altiro_packed_register4_v1',weight_layout='m4_k_mlane',activation_layout='n4_k_nlane_f32',
            activation_input_rounding='f16_then_f32',f32_accumulation=True,workgroup=[8,8,1],register_tile=[4,4],
            dispatch=[(m+31)//32,(n+31)//32,1],packing_workgroup=[64,1,1],full_reference=cells<=8192,
            reference_cells=(cells if cells<=8192 else 64)*streams,reference_mode='double_dot_half_rounded_inputs',
            wall_includes_activation_pack=True,weight_pack_checked=True,activation_pack_checked=True,
            candidate_gpu_dispatches_per_repeat=streams*2,candidate_shared_memory_bytes=0,specialization_constants=[],
            finite=True,weight_pack_gpu_ns=None,control_counts={},control_shaders={},
            physical_device_count=1,same_device_verified=True,execution_order='alternating_candidate_first_even',
            candidate_device='Example GPU',control_device='Example GPU',candidate_vendor_id=1,candidate_device_id=1,
            candidate_driver_version=1,timestamp_valid_bits=64,timestamp_period_ns=1.0,
            candidate_memory_flags_all=7,candidate_all_memory_device_local=True,sampled_peak_pss_kib=-1,
            environment=dict(elapsed_ms=100,thermal_status=0,power_save=False,battery_deci_c=300,
                battery_percent=80,plugged=0,cpu_sample_khz=[1000000,None,None,None,None,None,None,None]))
        for key in ('relative_rmse','max_abs_error','control_relative_rmse','control_max_abs_error','control_reference_relative_rmse'): record[key]=0
        for key in ('resident_bytes','scratch_bytes','control_resident_bytes','pipeline_setup_ms','setup_ms','weight_pack_wall_ms','source_rounding_max_abs','host_reference_bytes'): record[key]=1.0
        for key in ('wall_ms','process_cpu_ms','activation_pack_wall_ms','activation_pack_gpu_ns','compute_gpu_ns','boundary_gpu_ns','readback_check_ms','control_wall_ms','control_boundary_gpu_ns'): record[key]=[1000,2,3,4,5,6]
        value['packed'].append(record)
    return value


def capability_case():
    value=case('capability-test','VULKAN',70)
    value.update(samples=[],capabilities=dict(opencl=dict(api='opencl',status='library_unavailable_in_app',verified=False),
        vulkan=dict(api='vulkan',status='queried',int8QueryAvailable=True,dotQueryAvailable=True,shaderInt8=True,
                    shaderIntegerDotProduct=True,integerDotProduct4x8BitPackedSignedAccelerated=False)))
    return value


class NewGpuAnalysisTest(unittest.TestCase):
    def report(self,value): return dict(schema=1,cases=[value])

    def test_packed_variants_are_separate_and_first_excluded(self):
        for variant in range(60,64):
            report=self.report(packed_case(variant))
            row=analysis.summarize(report)[0]
            self.assertFalse(row['eligible'])
            self.assertEqual(4,len(row['packed']))
            self.assertEqual(4,row['packed'][0]['median'])
            self.assertTrue(all(k['eligible'] for k in row['packed']))
            self.assertNotIn('Fastest completed',analysis.render(report))
            self.assertIn('activation packing',analysis.render(report))
            self.assertIn('| Model | Case |',analysis.render(report))

    def test_packed_rejects_incomplete_wrong_work_nonfinite_and_privacy_fields(self):
        for mutate in (
            lambda v:v['packed'].pop(),lambda v:v['packed'][0]['wall_ms'].pop(),
            lambda v:v['packed'][0].update(m=True),lambda v:v['packed'][0].update(control_backend='CPU'),
            lambda v:v['packed'][0].update(activation_input_rounding='f32'),
            lambda v:v['packed'][0].update(wall_includes_activation_pack=False),
            lambda v:v['packed'][0].update(relative_rmse=float('nan')),
            lambda v:v['packed'][0].update(private_path='/private'),
            lambda v:v['packed'][0].update(boundary_gpu_ns=[float('inf')]*6),
            lambda v:v['packed'][0].update(activation_pack_wall_ms=[999999]*6),
            lambda v:v['packed'][0].update(workgroup=[True,8,1]),
            lambda v:v['packed'][0]['environment'].update(private_path='/private'),
            lambda v:v['packed'][0].update(same_device_verified=False),
            lambda v:v['packed'][0].update(sampled_peak_pss_kib=float('nan')),
            lambda v:v['packed'][1].update(candidate_device='Different GPU'),
        ):
            value=packed_case();mutate(value)
            with self.assertRaises(ValueError):analysis.summarize(self.report(value))

    def test_packed_nullable_gpu_and_numerical_screen(self):
        value=packed_case()
        value['packed'][0].update(boundary_gpu_ns=[None]*6,relative_rmse=.001)
        row=analysis.summarize(self.report(value))[0]['packed'][0]
        self.assertIsNone(row['gpu_ms']);self.assertFalse(row['eligible'])
        self.assertIn('unavailable',analysis.render(self.report(value)))

    def test_capability_unavailable_is_valid_but_never_latency(self):
        report=self.report(capability_case())
        self.assertFalse(analysis.summarize(report)[0]['eligible'])
        output=analysis.render(report)
        self.assertIn('library_unavailable_in_app',output)
        self.assertIn('integerDotProduct4x8BitPackedSignedAccelerated',output)
        self.assertNotIn('Fastest completed',output)

    def test_capability_missing_contradictory_unknown_or_private_rejected(self):
        for mutate in (
            lambda v:v.pop('capabilities'),lambda v:v['capabilities'].pop('vulkan'),
            lambda v:v['capabilities']['opencl'].update(verified=True),
            lambda v:v['capabilities']['opencl'].update(driverVersion='/vendor/private'),
            lambda v:v['capabilities']['opencl'].update(buildLog='private'),
            lambda v:v['capabilities']['opencl'].update(compileUs=float('nan')),
            lambda v:v['capabilities']['opencl'].update(fp16Arithmetic=True),
            lambda v:v['capabilities']['vulkan'].update(dotQueryAvailable=False),
            lambda v:v['capabilities']['vulkan'].update(shaderInt8=1),
        ):
            value=capability_case();mutate(value)
            with self.assertRaises(ValueError):analysis.summarize(self.report(value))

    def test_failed_partial_special_cases_keep_campaign_readable(self):
        packed=packed_case();packed.update(status='FAILED',packed=[])
        capability=capability_case();capability.update(status='FAILED');capability.pop('capabilities')
        report=dict(schema=1,cases=[packed,capability,case()])
        self.assertIn('incomplete',analysis.render(report))
        self.assertTrue(analysis.summarize(report)[2]['eligible'])

    def test_capability_verified_requires_gpu_and_execution(self):
        value=capability_case()
        value['capabilities']['opencl'].update(status='verified',verified=True,gpuSelected=True,compileUs=500,executeUs=10)
        self.assertIsNotNone(analysis.summarize(self.report(value))[0]['capability'])
        del value['capabilities']['opencl']['executeUs']
        with self.assertRaises(ValueError):analysis.summarize(self.report(value))


def gemm_case(variant=80, fp16=False):
    family='int8' if variant<90 else 'acl'
    value=case(f'{family}-test','VULKAN',variant)
    value.update(model='transcribepsy-fp16' if fp16 else 'transcribepsy-q8',samples=[],
                 candidate_backend_requested='VULKAN' if family=='int8' else 'OPENCL')
    value[family]=[]
    width=145 if variant%10==1 else 201 if variant%10==2 else 139
    for m,n,k in ((2560,width,640),(640,width,2560),(65,65,96),(63,63,64)):
        cells=m*n
        r=dict(sequence=len(value[family]),m=m,n=n,k=k,encoder_frames=139,streams=1,
            complete_calls_per_repeat=1,source_type='f16' if fp16 else 'q8_0',
            control_backend='Vulkan',activation_input_rounding='f16_then_f32',
            execution_order='alternating_candidate_first_even',full_reference=cells<=8192,
            reference_cells=cells if cells<=8192 else 64,finite=True,weight_pack_gpu_ns=1000,
            control_counts={},control_shaders={},candidate_device='Example GPU',control_device='Example GPU',
            candidate_vendor_id=1,sampled_peak_pss_kib=-1,
            environment=dict(elapsed_ms=100,thermal_status=0,power_save=False,battery_deci_c=300,
                battery_percent=80,plugged=0,cpu_sample_khz=[1000000,None,None,None,None,None,None,None]))
        for key in ('wall_ms','process_cpu_ms','readback_check_ms','control_wall_ms'):
            r[key]=[1000,2,3,4,5,6]
        for key in ('boundary_gpu_ns','control_boundary_gpu_ns'):
            r[key]=[100000000,2000000,3000000,4000000,5000000,6000000]
        for key in ('resident_bytes','scratch_bytes','control_resident_bytes','host_reference_bytes',
                    'setup_ms','weight_pack_wall_ms'):
            r[key]=1
        if family=='int8':
            r.update(storage_type='q8_0',backend='Vulkan',candidate_backend='Vulkan',
                control_weight_type='q8_0',implementation='altiro_q8_block32_sdot4_v1',
                weight_layout='m4_block32_lane_q8',activation_layout='n4_block32_lane_q8',
                activation_quantization='symmetric_per32_f32_scale_rna',
                quantized_reference_mode='int64_block_dot_double_scale',
                reference_mode='double_dot_q8_weights_half_rounded_x',
                full_quantized_reference=True,quantized_reference_cells=cells,
                workgroup=[8,8,1],register_tile=[4,4],dispatch=[(m+31)//32,(n+31)//32,1],
                packing_workgroup=[64,1,1],specialization_constants=[],
                candidate_gpu_dispatches_per_repeat=2,candidate_shared_memory_bytes=0,
                physical_device_count=1,same_device_verified=True,activation_quant_checked=True,
                weight_pack_checked=True,wall_includes_activation_quant=True,
                shader_integer_dot_product=True,packed_signed_dot_accelerated=True,integer_dot_enabled=True,
                logical_integer_dot_operations=cells*k//4,f32_accumulation=True,integer_accumulation='i32_per32',
                candidate_device_id=1,candidate_driver_version=1,timestamp_valid_bits=64,
                candidate_memory_flags_all=7,candidate_all_memory_device_local=True,timestamp_period_ns=1,
                activation_rounding_tie_count=0)
            for key in ('activation_quant_wall_ms','activation_quant_gpu_ns','compute_gpu_ns'):
                r[key]=[1]*6
            r['compute_gpu_ns']=[v-1 for v in r['boundary_gpu_ns']]
            for key in ('pipeline_setup_ms','reference_setup_ms','source_quantization_max_abs',
                        'activation_quant_max_abs','quantized_relative_rmse','quantized_max_abs_error',
                        'float_relative_rmse','float_max_abs_error','control_reference_relative_rmse',
                        'control_relative_rmse','control_max_abs_error'):
                r[key]=0
        else:
            r.update(storage_type='f16',backend='OpenCL',candidate_backend='OpenCL',
                implementation='acl_reshaped_rhs_t',acl_revision='80bbedb606746dcc1451cffedf3931d9fec3f53c',
                gemm_kernel='gemm_mm_reshaped_only_rhs_t',reshape_kernel='gemm_reshape_rhs_matrix_t',
                accumulation='f16',control_accumulation='f32',output_type='f16',
                block_m0=4,block_n0=4,block_k0=4,block_h0=1,dispatch=[(m+3)//4,(n+3)//4,1],
                activation_transform='f32_to_f16',output_transform='f16_to_f32',weight_pack_checked=True,
                boundary_output_type='f32',glue_implementation='altiro_fixed_cast_v1',
                candidate_gpu_events_per_repeat=3,activation_conversion_gpu_ns=[1]*6,
                output_conversion_gpu_ns=[1]*6,compute_gpu_ns=[v-2 for v in r['boundary_gpu_ns']],
                wall_includes_all_transforms=True,unique_gpu_vendor_match=True,
                reference_mode='double_dot_half_rounded_inputs',opencl_gpu_count=1,vulkan_gpu_count=1,
                device_type='GPU',workgroup_selection='driver_selected',
                candidate_vendor='Example vendor',candidate_driver_version='1.2.3',
                control_process_cpu_ms=[1]*6)
            for key in ('program_build_ms','host_rhs_transpose_ms','source_rounding_max_abs',
                        'relative_rmse','max_abs_error','control_relative_rmse','control_max_abs_error',
                        'control_reference_relative_rmse'):
                r[key]=0
        value[family].append(r)
    return value


class AlternateGemmAnalysisTest(unittest.TestCase):
    def report(self,value): return dict(schema=1,cases=[value])

    def test_all_variants_are_isolated_and_exclude_first_call(self):
        for variant in (80,81,82,90,91,92):
            for fp16 in ((False,True) if variant>=90 else (False,)):
                value=gemm_case(variant,fp16)
                row=analysis.summarize(self.report(value))[0]
                self.assertFalse(row['eligible'])
                self.assertEqual(4,len(row['gemm']))
                self.assertEqual(4,row['gemm'][0]['median'])
                self.assertTrue(all(r['eligible'] for r in row['gemm']))
                self.assertNotIn('Fastest completed',analysis.render(self.report(value)))

    def test_native_event_fields_match_receiver_fixture(self):
        # A field added to the C++ event but missing from the Android/export
        # contract previously broke live receipts. Keep the independent fixture
        # tied to the actual emitted key set, including array/map helper calls.
        root=Path(__file__).parents[2]/'inference-transcribepsy/src/main/cpp'
        for family,variant in (('int8',80),('acl',90)):
            source=(root/f'{family}-kernel.cpp').read_text()
            marker='out<<"{\\"lab\\":true,\\"event\\":\\"'+family.upper()
            emission=source[source.index(marker):]
            emission=emission[:emission.index('emit(out.str())')]
            emitted=set(re.findall(r'\\"([A-Za-z0-9_]+)\\":',emission))
            emitted.update(re.findall(r'(?:array|map|counters)\(out,\s*"([A-Za-z0-9_]+)"',emission))
            fixture=set(gemm_case(variant)[family][0])-{'environment','sampled_peak_pss_kib'}
            self.assertEqual(fixture|{'lab','event'},emitted)

    def test_android_integral_double_json_spelling_is_accepted(self):
        for variant in (80,90):
            value=gemm_case(variant); family='int8' if variant==80 else 'acl'
            for record in value[family]:
                for key,item in list(record.items()):
                    if type(item) is int: record[key]=float(item)
                    elif isinstance(item,list) and key not in ('specialization_constants',):
                        record[key]=[float(x) if type(x) is int else x for x in item]
            # Android JSONObject flattens integral Doubles; both spellings must agree.
            floating=json.loads(json.dumps(value))
            def flatten(item):
                if type(item) is float and item.is_integer(): return int(item)
                if isinstance(item,list): return [flatten(v) for v in item]
                if isinstance(item,dict): return {k:flatten(v) for k,v in item.items()}
                return item
            self.assertEqual(analysis.summarize(self.report(floating)),
                             analysis.summarize(self.report(flatten(floating))))

    def test_int8_float_quantization_error_is_not_oracle_failure(self):
        value=gemm_case()
        value['int8'][0].update(float_relative_rmse=.2,control_relative_rmse=.2)
        self.assertTrue(analysis.summarize(self.report(value))[0]['gemm'][0]['eligible'])
        value['int8'][0]['quantized_relative_rmse']=.001
        self.assertFalse(analysis.summarize(self.report(value))[0]['gemm'][0]['eligible'])

    def test_no_timestamp_or_failed_partial_case_cannot_claim_performance(self):
        for variant in (80,90):
            value=gemm_case(variant); family='int8' if variant==80 else 'acl'
            value[family][0]['boundary_gpu_ns']=[None]*6
            self.assertFalse(analysis.summarize(self.report(value))[0]['gemm'][0]['eligible'])
            self.assertIn('timestamps unavailable',analysis.render(self.report(value)))
            value.update(status='FAILED'); value[family]=value[family][:1]
            self.assertEqual(1,len(analysis.summarize(self.report(value))[0]['gemm']))
            value[family]=[]
            self.assertIn('0/4',analysis.render(self.report(value)))

    def test_mismatched_work_proof_bounds_and_privacy_are_rejected(self):
        common=(
            lambda r:r.update(m=True),lambda r:r.update(sequence=.5),
            lambda r:r.update(streams=2),lambda r:r.update(control_backend='CPU'),
            lambda r:r.update(wall_ms=[1]*5),lambda r:r.update(private_path='/private'),
            lambda r:r.update(boundary_gpu_ns=[float('nan')]*6),
            lambda r:r.update(control_boundary_gpu_ns=[-1]*6),
            lambda r:r.update(candidate_device='private/path'),
            lambda r:r.update(reference_cells=1),lambda r:r.update(finite='true'),
            lambda r:r['environment'].update(private_path='/private'),
        )
        for variant in (80,90):
            family='int8' if variant==80 else 'acl'
            for mutate in common:
                value=gemm_case(variant); mutate(value[family][0])
                with self.assertRaises(ValueError): analysis.summarize(self.report(value))
            value=gemm_case(variant); value[family].pop()
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))
            value=gemm_case(variant); value[family][1]['encoder_frames']=140
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))
        for mutate in (
            lambda r:r.update(control_weight_type='f32'),
            lambda r:r.update(integer_dot_enabled=False),
            lambda r:r.update(shader_integer_dot_product=False),
            lambda r:r.update(packed_signed_dot_accelerated=False),
            lambda r:r.update(activation_quant_checked=False),
            lambda r:r.update(logical_integer_dot_operations=1),
            lambda r:r.update(activation_quant_wall_ms=[2000]*6),
            lambda r:r.update(activation_rounding_tie_count=10**12),
        ):
            value=gemm_case(); mutate(value['int8'][0])
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))
        for mutate in (
            lambda r:r.update(acl_revision='unverified'),
            lambda r:r.update(unique_gpu_vendor_match=False),
            lambda r:r.update(opencl_gpu_count=2),
            lambda r:r.update(gemm_kernel='custom_kernel'),
            lambda r:r.update(accumulation='f32'),
            lambda r:r.update(weight_pack_checked=False),
        ):
            value=gemm_case(90); mutate(value['acl'][0])
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))

    def test_int8_fp16_unknown_variant_and_requested_backend_rejected(self):
        for value in (gemm_case(80,True),gemm_case(90)):
            if value['variant']==90: value['candidate_backend_requested']='VULKAN'
            with self.assertRaises(ValueError): analysis.summarize(self.report(value))
        value=case(variant=50)
        with self.assertRaises(ValueError): analysis.summarize(self.report(value))


if __name__ == '__main__':
    unittest.main()
