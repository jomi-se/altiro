"""Embed build-generated SPIR-V words; no runtime files or shader compilation."""
from pathlib import Path
import struct
import sys

output = Path(sys.argv[1])
parts = ["#pragma once\n#include <cstdint>\n"]
for name in ("pack_weights", "pack_activations", "register4"):
    raw = Path(sys.argv[2], name.replace("_", "-") + ".spv").read_bytes()
    if len(raw) % 4 or raw[:4] != b"\x03\x02\x23\x07":
        raise SystemExit("Invalid generated SPIR-V")
    words = struct.unpack(f"<{len(raw)//4}I", raw)
    parts.append(f"static const uint32_t altiro_spv_{name}[] = {{\n")
    for start in range(0, len(words), 8):
        parts.append(",".join(f"0x{w:08x}" for w in words[start:start+8]) + ",\n")
    parts.append("};\n")
output.write_text("".join(parts))
