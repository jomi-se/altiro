"""Embed build-generated SPIR-V words; no runtime files or shader compilation."""
from pathlib import Path
import struct
import sys

output = Path(sys.argv[1])
parts = ["#pragma once\n#include <cstdint>\n"]
for name in ("pack_weights", "quant_activations", "dot4"):
    raw = Path(sys.argv[2], name.replace("_", "-") + ".spv").read_bytes()
    if len(raw) % 4 or raw[:4] != b"\x03\x02\x23\x07":
        raise SystemExit("Invalid generated SPIR-V")
    words = struct.unpack(f"<{len(raw)//4}I", raw)
    if name == "dot4":
        # The pinned frontend uses EXT_spirv_intrinsics. Verify the emitted
        # arithmetic, not merely the GLSL function name: signed packed OpSDot,
        # required caps, no accidental Int8/Int64 or unsigned dot route.
        cursor, dots, capabilities, types = 5, [], set(), {}
        while cursor < len(words):
            size, opcode = words[cursor] >> 16, words[cursor] & 65535
            if size < 1 or cursor + size > len(words):
                raise SystemExit("Invalid SPIR-V instruction")
            operands = words[cursor + 1:cursor + size]
            if opcode == 17:
                capabilities.add(operands[0])
            if opcode == 21:
                types[operands[0]] = operands[1:]
            if opcode == 4450:
                dots.append(operands)
            if opcode in (4451, 4452, 4453, 4454, 4455):
                raise SystemExit("Unexpected unsigned/mixed/saturating dot")
            cursor += size
        if capabilities != {1, 6018, 6019} or len(dots) != 16:
            raise SystemExit("Signed packed dot capabilities/instructions missing")
        if any(len(dot) != 5 or dot[-1] != 0 or types.get(dot[0]) != (32, 1) for dot in dots):
            raise SystemExit("Wrong packed dot result type or operand format")
    parts.append(f"static const uint32_t altiro_int8_spv_{name}[] = {{\n")
    for start in range(0, len(words), 8):
        parts.append(",".join(f"0x{w:08x}" for w in words[start:start+8]) + ",\n")
    parts.append("};\n")
output.write_text("".join(parts))
