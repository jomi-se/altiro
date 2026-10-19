#!/usr/bin/env python3
"""Auditable hooks for graph preflight, cancellation and actual execution counts."""
from pathlib import Path
import sys
import hashlib


def replace(path, old, new, count=1):
    source = path.read_text()
    if source.count(new) == count:
        return
    if source.count(old) != count:
        raise SystemExit(f"TranscribePsy patch anchor drift: {path.name}: {old[:90]!r} ({source.count(old)})")
    path.write_text(source.replace(old, new))


root = Path(sys.argv[1])
stamp = root / '.altiro-encoder-patch'
digest = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
if stamp.exists():
    if stamp.read_text() != digest:
        raise SystemExit("TranscribePsy patch changed; extract a clean pinned source tree")
    raise SystemExit(0)
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

# Encoder lab hooks are dormant during ordinary dictation. Variants are immutable
# for the lifetime of an isolated lab worker; cached graphs never change flags.
ctc = root / 'src/parakeet_ctc.cpp'
for path in [ctc, root / 'ggml/src/ggml-vulkan/ggml-vulkan.cpp']:
    source = path.read_text()
    if '#include "encoder-lab.h"' not in source:
        path.write_text('#include "encoder-lab.h"\n' + source)
replace(ctc, 'const bool use_conv2d_dw = backend_is_cpu(backend);',
        'const bool use_conv2d_dw = backend_is_cpu(backend) || altiro_lab_variant(1);', count=2)
replace(ctc, '#ifdef PARAKEET_EXPERIMENTAL_FLASH_ATTN', 'if (altiro_lab_variant(5)) {')
# Only the attention branch has this exact adjacent text.
replace(ctc, '#else\n    ggml_tensor * ac', '}\n    ggml_tensor * ac')
replace(ctc, '#endif\n}\n\nggml_tensor * conformer_conv_graph', '} // ALTIRO_FLASH_BRANCH_END\n\nggml_tensor * conformer_conv_graph')
replace(ctc, '    snapshot_encoder_graph_srcs(g);',
        '    altiro_lab_prepare_graph(backend, g.cgraph);\n    snapshot_encoder_graph_srcs(g);')
# Scope timings around each host stage, keeping upstream graph lifetimes intact.
replace(ctc, '        if (int rc = build_encoder_graph_cached(model, e,',
        '        AltiroLabTimer lab_build("graph_build_reserve_us");\n'
        '        if (int rc = build_encoder_graph_cached(model, e,', count=2)
replace(ctc, '    if (!ggml_gallocr_alloc_graph(g.alloc, g.cgraph)) {\n        return -3;\n    }',
        '    { AltiroLabTimer lab_alloc("graph_allocate_us");\n'
        '    if (!ggml_gallocr_alloc_graph(g.alloc, g.cgraph)) {\n        return -3;\n    } }', count=2)
replace(ctc, '    safe_set(g.mel_in,', '    { AltiroLabTimer lab_upload("upload_us");\n    safe_set(g.mel_in,')
replace(ctc, '    if (!altiro_before_graph(backend, g.cgraph, 1)) return -4;',
        '    AltiroLabTimer lab_compute("compute_sync_us");\n'
        '    if (!altiro_before_graph(backend, g.cgraph, 1)) return -4;', count=2)
# Close upload scope before compute and compute scope before result readback.
replace(ctc, '    AltiroLabTimer lab_compute("compute_sync_us");',
        '    }\n    { AltiroLabTimer lab_compute("compute_sync_us");', count=2)
# bypass path has a different input upload, start its corresponding scope too.
replace(ctc, '    safe_set(g.pre_encode_in,', '    { AltiroLabTimer lab_upload("upload_us");\n    safe_set(g.pre_encode_in,')
replace(ctc, '    if (altiro_status != GGML_STATUS_SUCCESS) return -4;',
        '    if (altiro_status != GGML_STATUS_SUCCESS) return -4; }\n'
        '    AltiroLabTimer lab_readback("readback_us");', count=2)
vk = root / 'ggml/src/ggml-vulkan/ggml-vulkan.cpp'
replace(vk, 'ggml_vk_load_shaders(vk_device& device) {',
        'ggml_vk_load_shaders(vk_device& device) {\n    AltiroLabTimer lab_registry("shader_registry_compile_us");')
replace(vk, '            compiles.push_back(std::async(ggml_vk_create_pipeline_func,',
        '            altiro_lab_count("pipeline_compile_requests");\n'
        '            compiles.push_back(std::async(ggml_vk_create_pipeline_func,')
replace(vk, '    subctx->s->buffer->buf.dispatch(wg0, wg1, wg2);',
        '    altiro_lab_count("dispatches");\n'
        '    altiro_lab_shader(pipeline->name.c_str());\n'
        '    subctx->s->buffer->buf.dispatch(wg0, wg1, wg2);')
replace(vk, '    int nodes_per_submit = 100;',
        '    int nodes_per_submit = 100;')
replace(vk, '    for (int i = 0; i < cgraph->n_nodes; i++) {\n        if (first_node_in_batch)',
        '    if (altiro_lab_variant(3)) mul_mat_bytes_per_submit = 1000000;\n'
        '    if (altiro_lab_variant(4)) mul_mat_bytes_per_submit = 100000000;\n'
        '    for (int i = 0; i < cgraph->n_nodes; i++) {\n        if (first_node_in_batch)')
replace(vk, '            if (submit_count < 3) {',
        '            altiro_lab_count("encoder_submit_batches");\n'
        '            if (submit_count < 3 && !altiro_lab_variant(3) && !altiro_lab_variant(4)) {')
replace(vk, 'static void ggml_vk_synchronize(ggml_backend_vk_context * ctx) {',
        'static void ggml_vk_synchronize(ggml_backend_vk_context * ctx) {\n'
        '    AltiroLabTimer lab_sync("synchronize_us");\n'
        '    altiro_lab_count("synchronize_calls");')
replace(vk, '    if (ctx->submit_pending) {\n        if (ctx->device->async_use_transfer_queue',
        '    if (ctx->submit_pending) {\n        altiro_lab_count("blocking_syncs");\n'
        '        if (ctx->device->async_use_transfer_queue')
replace(vk, '                ctx->perf_logger->log_timing(node, name, uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));',
        '                altiro_lab_gpu_time(node, uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));\n'
        '                ctx->perf_logger->log_timing(node, name, uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));')
replace(vk, '    const bool qx_needs_dequant = mmp == nullptr || x_non_contig || qx_force_dequant;',
        '    altiro_lab_count("matmul_integer_dot_routes", quantize_y ? 1 : 0);\n'
        '    altiro_lab_count("matmul_separate_dequant_routes", (mmp == nullptr || x_non_contig || qx_force_dequant) ? 1 : 0);\n'
        '    const bool qx_needs_dequant = mmp == nullptr || x_non_contig || qx_force_dequant;')

stamp.write_text(digest)
