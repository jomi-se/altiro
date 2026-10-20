from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class KernelHuntPoliciesTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('c++'), 'Host C++ compiler unavailable')
    def test_device_selection_and_shader_output_ownership(self):
        # Minimal public GGML API declarations for this isolated policy test.
        # The Android build also checks production headers; a real-header host
        # run can compile the same fixture against the pinned GGML include dir.
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            (temp / 'ggml-backend.h').write_text('''#pragma once
#include <cstddef>
extern "C" {
struct ggml_backend; struct ggml_backend_device; struct ggml_backend_reg;
using ggml_backend_t = ggml_backend *;
using ggml_backend_dev_t = ggml_backend_device *;
using ggml_backend_reg_t = ggml_backend_reg *;
enum ggml_backend_dev_type { GGML_BACKEND_DEVICE_TYPE_CPU, GGML_BACKEND_DEVICE_TYPE_GPU,
GGML_BACKEND_DEVICE_TYPE_IGPU, GGML_BACKEND_DEVICE_TYPE_ACCEL, GGML_BACKEND_DEVICE_TYPE_META };
ggml_backend_reg_t ggml_backend_reg_by_name(const char *);
size_t ggml_backend_reg_dev_count(ggml_backend_reg_t);
ggml_backend_dev_t ggml_backend_reg_dev_get(ggml_backend_reg_t, size_t);
enum ggml_backend_dev_type ggml_backend_dev_type(ggml_backend_dev_t);
ggml_backend_t ggml_backend_dev_init(ggml_backend_dev_t, const char *);
}
''')
            result = subprocess.run([
                'c++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                '-I', str(temp), '-I', str(ROOT / 'inference-transcribepsy/src/main/cpp'),
                str(ROOT / 'inference-transcribepsy/src/test-native/KernelHuntPolicies.cpp'),
                '-o', str(temp / 'policies'),
            ], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            subprocess.run([str(temp / 'policies')], check=True)
