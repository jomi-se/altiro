import importlib.util
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


if __name__ == '__main__':
    unittest.main()
