#!/usr/bin/env python3
"""Check actual packaged 64-bit ELF LOAD and uncompressed APK alignment."""

from pathlib import Path
import struct
import sys
import zipfile


def check_apk(path: Path) -> int:
    count = 0
    with path.open("rb") as archive, zipfile.ZipFile(path) as apk:
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
    except (ValueError, OSError, zipfile.BadZipFile, struct.error) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
