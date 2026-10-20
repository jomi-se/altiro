"""Network-free fake ABI regression test; does not load a GPU or OpenCL library."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class CapabilityNativeTest(unittest.TestCase):
    def test_fake_opencl_lifecycle_and_fixed_probe(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix='altiro-capability-test-') as temporary:
            binary = Path(temporary) / 'capability-test'
            subprocess.run([
                'c++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                '-I' + str(root / 'scripts/tests/fixtures/opencl'),
                str(root / 'inference-transcribepsy/src/main/cpp/gpu-lab-capabilities-test.cpp'),
                '-ldl', '-o', str(binary),
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertIn('fake ABI tests passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
