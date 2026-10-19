"""Prevent an IDE deployment APK from passing the phone-download checks."""
import importlib.util
from pathlib import Path
import struct
import unittest


spec = importlib.util.spec_from_file_location(
    "native_pages", Path(__file__).resolve().parents[1] / "check-native-pages.py"
)
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


def manifest(test_only=None, utf8=True):
    values = ["application", "http://schemas.android.com/apk/res/android", "testOnly"]
    strings = bytearray()
    offsets = []
    for text in values:
        offsets.append(len(strings))
        if utf8:
            encoded = text.encode("utf-8")
            strings += bytes([len(text), len(encoded)]) + encoded + b"\0"
        else:
            strings += struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\0\0"
    start = 28 + len(values) * 4
    pool = struct.pack("<HHIIIIII", 1, 28, start + len(strings), len(values), 0,
                       256 if utf8 else 0, start, 0)
    pool += struct.pack("<III", *offsets) + strings
    attrs = b"" if test_only is None else struct.pack(
        "<IIIHBBI", 1, 2, 0xffffffff, 8, 0, 0x12, 0xffffffff if test_only else 0
    )
    node = struct.pack("<HHIII", 0x102, 16, 36 + len(attrs), 1, 0xffffffff)
    node += struct.pack("<IIHHHHHH", 0xffffffff, 0, 20, 20,
                        0 if test_only is None else 1, 0, 0, 0) + attrs
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(node)) + pool + node


class InstallabilityTest(unittest.TestCase):
    def test_rejects_test_only_apk(self):
        for utf8 in (True, False):
            with self.subTest(utf8=utf8), self.assertRaisesRegex(ValueError, "testOnly"):
                pages.check_installable_manifest(manifest(True, utf8))

    def test_accepts_absent_or_false_flag(self):
        for flag in (None, False):
            for utf8 in (True, False):
                with self.subTest(flag=flag, utf8=utf8):
                    pages.check_installable_manifest(manifest(flag, utf8))

    def test_rejects_missing_application(self):
        with self.assertRaisesRegex(ValueError, "application"):
            pages.check_installable_manifest(struct.pack("<HHI", 3, 8, 8))

    def test_rejects_invalid_chunk_bounds(self):
        invalid = bytearray(manifest())
        struct.pack_into("<I", invalid, 12, 0)
        with self.assertRaisesRegex(ValueError, "bounds"):
            pages.check_installable_manifest(invalid)


if __name__ == "__main__":
    unittest.main()
