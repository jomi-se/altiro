from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class Int8KernelPoliciesTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('c++'), 'Host C++ compiler unavailable')
    def test_block_scales_signed_bytes_quantization_and_output_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'int8-policies'
            subprocess.run([
                'c++', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                '-I', str(ROOT / 'inference-transcribepsy/src/main/cpp'),
                str(ROOT / 'inference-transcribepsy/src/test-native/Int8LayoutPolicies.cpp'),
                '-o', str(executable),
            ], check=True, capture_output=True, text=True)
            subprocess.run([str(executable)], check=True)
