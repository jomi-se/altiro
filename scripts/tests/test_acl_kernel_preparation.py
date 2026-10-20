"""Offline integrity tests for embedding upstream ACL sources without modifying kernels."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('acl_prepare', Path(__file__).parents[1] / 'prepare-acl-kernels.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class AclPreparationTest(unittest.TestCase):
    def fixture(self, root, files):
        base = root / 'src/core/CL/cl_kernels'
        hashes = {}
        for name, text in files.items():
            path = base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            hashes[name] = hashlib.sha256(text.encode()).hexdigest()
        return hashes

    def test_exact_include_expansion_preserves_code_and_notice_and_mtime(self):
        with tempfile.TemporaryDirectory(prefix='altiro-acl-prep-') as temp:
            root = Path(temp)
            files = {'common/gemm.cl': '#include "helpers.h"\n__kernel void gemm() {}\n',
                     'common/gemm_utils.cl': '#include "helpers.h"\n__kernel void pack() {}\n',
                     'helpers.h': '// MIT notice stays here\n#define BLOCK 4\n'}
            with patch.object(prepare, 'HASHES', self.fixture(root, files)):
                output = root / 'generated.h'
                prepare.prepare(root, output)
                first = output.read_bytes()
                mtime = output.stat().st_mtime_ns
                prepare.prepare(root, output)
                self.assertEqual(first, output.read_bytes())
                self.assertEqual(mtime, output.stat().st_mtime_ns)
                self.assertIn(files['helpers.h'] + '\n__kernel void gemm() {}', first.decode())
                self.assertIn(prepare.REVISION, first.decode())

    def test_changed_pinned_input_rejected_before_output(self):
        with tempfile.TemporaryDirectory(prefix='altiro-acl-prep-') as temp:
            root = Path(temp)
            files = {'common/gemm.cl': 'original', 'common/gemm_utils.cl': 'packing'}
            with patch.object(prepare, 'HASHES', self.fixture(root, files)):
                (root / 'src/core/CL/cl_kernels/common/gemm.cl').write_text('modified')
                with self.assertRaises(ValueError): prepare.prepare(root, root / 'output')
                self.assertFalse((root / 'output').exists())

    def test_unknown_cyclic_and_unsafe_source_includes_rejected(self):
        for text, extra in (
            ('#include "missing.h"', {}),
            ('#include "helpers.h"', {'helpers.h': '#include "helpers.h"'}),
            ('#include "../../private.h"', {}),
            (')ACL_SOURCE"', {}),
            ('x' * 2_000_001, {}),
        ):
            with self.subTest(text=text[:30]), tempfile.TemporaryDirectory(prefix='altiro-acl-prep-') as temp:
                root = Path(temp)
                files = {'common/gemm.cl': text, 'common/gemm_utils.cl': 'packing', **extra}
                with patch.object(prepare, 'HASHES', self.fixture(root, files)):
                    with self.assertRaises(ValueError): prepare.prepare(root, root / 'output')

    def test_ragged_acl_partial_store_covers_each_output_once(self):
        # Independent reproduction of upstream partial-store addressing contract:
        # first tile writes remainder rows, subsequent tiles write four full rows.
        for rows in (1, 2, 3, 4, 63, 65, 145, 201, 1000):
            remainder = rows % 4
            written = []
            for group in range((rows + 3) // 4):
                start = max(0, group * 4 - (4 - remainder) % 4)
                count = remainder if group == 0 and remainder else 4
                written.extend(range(start, start + count))
            self.assertEqual(list(range(rows)), written)


if __name__ == '__main__':
    unittest.main()
