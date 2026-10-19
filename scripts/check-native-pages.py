#!/usr/bin/env python3
"""Check installable application manifests and packaged 64-bit native alignment."""

from pathlib import Path
import struct
import sys
import zipfile


def check_installable_manifest(data: bytes) -> None:
    """Reject test-only application APKs, reading their compiled manifest."""
    if len(data) < 8 or struct.unpack_from("<H", data)[0] != 3:
        raise ValueError("Expected a binary Android manifest")
    strings = []
    application = False
    position = 8
    while position < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, position)
        if header < 8 or size < header or position + size > len(data):
            raise ValueError("Invalid manifest chunk bounds")
        end = position + size
        if kind == 1:
            count, _, flags, start = struct.unpack_from("<IIII", data, position + 8)
            if header < 28 or header + 4 * count > size or start > size:
                raise ValueError("Invalid manifest string pool")
            strings = []
            for index in range(count):
                offset = struct.unpack_from("<I", data, position + header + 4 * index)[0]
                at = position + start + offset
                unit = 1 if flags & 256 else 2

                def length(at):
                    value = int.from_bytes(data[at:at + unit], "little")
                    high = 1 << (unit * 8 - 1)
                    if at + unit > end:
                        raise ValueError("Truncated manifest string")
                    at += unit
                    if value & high:
                        if at + unit > end:
                            raise ValueError("Truncated manifest string length")
                        value = ((value & (high - 1)) << (unit * 8)) | int.from_bytes(data[at:at + unit], "little")
                        at += unit
                    return value, at

                size_chars, at = length(at)
                if unit == 1:
                    size_chars, at = length(at)
                stop = at + size_chars * unit
                if stop + unit > end or any(data[stop:stop + unit]):
                    raise ValueError("Invalid manifest string bounds")
                strings.append(data[at:stop].decode("utf-8" if unit == 1 else "utf-16-le"))
        elif kind == 0x102:
            _, name, first, stride, count = struct.unpack_from("<IIHHH", data, position + 16)
            if name >= len(strings) or stride < 20 or 16 + first + count * stride > size:
                raise ValueError("Invalid manifest attribute bounds")
            if strings[name] == "application":
                application = True
                for index in range(count):
                    at = position + 16 + first + index * stride
                    namespace, key, _, _, _, value_type, value = struct.unpack_from("<IIIHBBI", data, at)
                    if key >= len(strings) or (namespace != 0xffffffff and namespace >= len(strings)):
                        raise ValueError("Invalid manifest attribute name")
                    if strings[key] == "testOnly" and namespace != 0xffffffff and strings[namespace] == "http://schemas.android.com/apk/res/android":
                        if value_type != 0x12 or value != 0:
                            raise ValueError("Application APK is android:testOnly; rebuild for normal installation")
        position = end
    if not application:
        raise ValueError("Manifest has no application")


def check_apk(path: Path) -> int:
    count = 0
    with path.open("rb") as archive, zipfile.ZipFile(path) as apk:
        check_installable_manifest(apk.read("AndroidManifest.xml"))
        for member in apk.infolist():
            if not member.filename.startswith("lib/") or not member.filename.endswith(".so"):
                continue
            count += 1
            data = apk.read(member)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"{member.filename}: expected a little-endian 64-bit ELF")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, entries = struct.unpack_from("<HH", data, 54)
            if size < 56 or offset + size * entries > len(data):
                raise ValueError(f"{member.filename}: invalid program header bounds")
            loads = 0
            for index in range(entries):
                position = offset + index * size
                if struct.unpack_from("<I", data, position)[0] != 1:
                    continue
                loads += 1
                file_offset, address = struct.unpack_from("<QQ", data, position + 8)
                alignment = struct.unpack_from("<Q", data, position + 48)[0]
                if alignment < 16384 or address % 16384 != file_offset % 16384:
                    raise ValueError(f"{member.filename}: LOAD segment is not 16 KiB compatible")
            if not loads:
                raise ValueError(f"{member.filename}: no LOAD segment")
            if member.compress_type == zipfile.ZIP_STORED:
                archive.seek(member.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", archive.read(4))
                if (member.header_offset + 30 + name_size + extra_size) % 16384:
                    raise ValueError(f"{member.filename}: uncompressed APK entry is not 16 KiB aligned")
    print(f"{path.name}: checked {count} native libraries for 16 KiB packaging.")
    return count


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: check-native-pages.py APK [APK...]")
    try:
        for argument in sys.argv[1:]:
            check_apk(Path(argument))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
