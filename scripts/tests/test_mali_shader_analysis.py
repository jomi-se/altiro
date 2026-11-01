import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('mali',Path(__file__).parents[1]/'analyze-mali-shaders.py')
mali = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mali)


class MaliAnalysisTest(unittest.TestCase):
    def test_specialization_reflection_ignores_ordinary_decorations(self):
        words = [0x07230203,0x10000,0,10,0,(4<<16)|71,5,1,7,(4<<16)|71,6,33,9]
        self.assertEqual({7},mali.specialization_ids(struct.pack('<'+'I'*len(words),*words)))

    def test_truncated_and_zero_word_instructions_are_rejected(self):
        for words in ([0,0,0,0,0],[0x07230203,0,0,0,0,0],[0x07230203,0,0,0,0,(9<<16)|71]):
            with self.assertRaises(ValueError): mali.specialization_ids(struct.pack('<'+'I'*len(words),*words))

    def test_exact_scalar_tile_constants_are_not_desktop_defaults(self):
        self.assertEqual([128,64,64,16,16,32,2,4,2,1,16],mali.constants('medium',16,False))
        self.assertEqual(32,mali.constants('small',16,True)[3])
        self.assertEqual(8,mali.constants('small',8,False)[4])
        self.assertEqual(16,mali.constants('small',16,False)[4])
        self.assertEqual(128,mali.constants('large',16,False)[1])

    def test_256_thread_tile_preserves_reuse_and_limits_subgroup(self):
        for quantized in (False, True):
            specs = mali.constants('256-thread64',16,quantized)
            self.assertEqual([256,64,64,32,16,16,2,2,2,1,16],specs)
            block,bm,bn,bk,wm,wn,wmiter,tm,tn,tk,warp = specs
            self.assertEqual(block//warp,(bm//wm)*(bn//wn))
            self.assertEqual(16,bm*bn//block)
        for subgroup in (8,32):
            with self.assertRaises(ValueError): mali.constants('256-thread64',subgroup,True)

    def test_rectangular_tiles_cover_outputs_without_fractional_warp_loops(self):
        for subgroup in (8,16,32):
            for tile in ('64x32','32x64'):
                block,bm,bn,bk,wm,wn,wmiter,tm,tn,tk,warp = mali.constants(tile,subgroup,True)
                self.assertEqual(0,block%warp)
                self.assertEqual(block//warp,(bm//wm)*(bn//wn))
                self.assertEqual(0,bm%wm)
                self.assertEqual(0,bn%wn)
                self.assertEqual(0,(wm*wn)%(warp*tm*tn*wmiter))
                self.assertGreater((wm*wn)//(warp*tm*tn*wmiter),0)
                self.assertEqual(2048,bm*bn)


if __name__ == '__main__': unittest.main()
