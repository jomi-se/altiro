#!/usr/bin/env python3
"""Auditable hooks for graph preflight, cancellation and actual execution counts."""
from pathlib import Path
import sys


def replace(path, old, new, count=1):
    source = path.read_text()
    if source.count(new) == count:
        return
    if source.count(old) != count:
        raise SystemExit(f"TranscribePsy patch anchor drift: {path.name}")
    path.write_text(source.replace(old, new))


root = Path(sys.argv[1])
for filename in ['parakeet_ctc.cpp', 'parakeet_tdt.cpp']:
    path = root / 'src' / filename
    source = path.read_text()
    if '#include "run-control.h"' not in source:
        path.write_text('#include "run-control.h"\n' + source)

registry = root / 'ggml/src/ggml-backend-reg.cpp'
source = registry.read_text()
if '#include "run-control.h"' not in source:
    registry.write_text('#include "run-control.h"\n' + source)
replace(registry, 'register_backend(ggml_backend_vk_reg());',
        'if (altiro_allow_vulkan()) register_backend(ggml_backend_vk_reg());')

replace(root / 'src/parakeet_ctc.cpp',
        '    if (ggml_backend_graph_compute(backend, g.cgraph) != GGML_STATUS_SUCCESS) {\n        return -4;\n    }',
        '    if (!altiro_before_graph(backend, g.cgraph, 1)) return -4;\n'
        '    const auto altiro_status = ggml_backend_graph_compute(backend, g.cgraph);\n'
        '    altiro_after_graph(1, altiro_status);\n'
        '    if (altiro_status != GGML_STATUS_SUCCESS) return -4;', count=2)
replace(root / 'src/parakeet_tdt.cpp',
        '    return ggml_backend_graph_compute(rt.backend, cg) == GGML_STATUS_SUCCESS;',
        '    if (!altiro_before_graph(rt.backend, cg, 2)) return false;\n'
        '    const auto altiro_status = ggml_backend_graph_compute(rt.backend, cg);\n'
        '    altiro_after_graph(2, altiro_status);\n'
        '    return altiro_status == GGML_STATUS_SUCCESS;')

# The same registration-only compile bound used by our Whisper Vulkan build.
# CPU/GPU numerical kernels and shader optimization stay unchanged.
replace(root / 'ggml/src/ggml-vulkan/ggml-vulkan.cpp',
        'static void ggml_vk_load_shaders(vk_device& device) {',
        'static void\n#if defined(__clang__) && defined(__ANDROID__) && defined(NDEBUG)\n'
        '__attribute__((optnone))\n#endif\n'
        'ggml_vk_load_shaders(vk_device& device) {')
