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
replace(vk, 'const uint32_t s_warptile_wm = device->subgroup_size == 8 ? 8 : 32;',
        'const uint32_t s_warptile_wm = altiro_small_tile_wm(device->subgroup_size, device->coopmat_support, altiro_lab_tile() == 1);', count=2)
replace(vk, 'ggml_vk_load_shaders(vk_device& device) {',
        'ggml_vk_load_shaders(vk_device& device) {\n    AltiroLabTimer lab_registry("shader_registry_compile_us");')
replace(vk, '            compiles.push_back(std::async(ggml_vk_create_pipeline_func,',
        '            altiro_lab_count("pipeline_compile_requests");\n'
        '            compiles.push_back(std::async(ggml_vk_create_pipeline_func,')
replace(vk, '    subctx->s->buffer->buf.dispatch(wg0, wg1, wg2);',
        '    altiro_lab_count("dispatches");\n'
        '    altiro_lab_shader(pipeline->name.c_str());\n'
        '    if (pipeline->name.rfind("matmul_",0) == 0) {\n'
        '        const std::string tile = "tile_" + std::to_string(pipeline->wg_denoms[0]) + "x" + std::to_string(pipeline->wg_denoms[1]);\n'
        '        altiro_lab_count(tile.c_str());\n'
        '    }\n'
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

# Override only an existing supported tile family, before both the alignment
# query and the final pipeline selection. Never force unavailable shared memory.
replace(vk, '    if (ctx->device->coopmat2 && !mmp->scalar) {\n        const uint32_t shader_core_count',
        '    if (altiro_lab_tile() == 1 && has_s && (aligned ? mmp->a_s : mmp->s)) {\n'
        '        altiro_lab_count("forced_small_tile_routes");\n'
        '        return aligned ? mmp->a_s : mmp->s;\n    }\n'
        '    if (altiro_lab_tile() == 3 && has_l && (aligned ? mmp->a_l : mmp->l)) {\n'
        '        altiro_lab_count("forced_large_tile_routes");\n'
        '        return aligned ? mmp->a_l : mmp->l;\n    }\n'
        '    if (altiro_lab_tile() >= 4 && !ctx->device->coopmat2 && !ctx->device->coopmat_support && has_m &&\n'
        '        (src0_type == GGML_TYPE_F16 || src0_type == GGML_TYPE_Q8_0) && (aligned ? mmp->a_m : mmp->m)) {\n'
        '        altiro_lab_count("forced_rectangular_tile_routes");\n'
        '        return aligned ? mmp->a_m : mmp->m;\n    }\n'
        '    if (altiro_lab_tile() && altiro_encoder_lab->measuring) altiro_lab_count("tile_override_unavailable");\n'
        '    if (ctx->device->coopmat2 && !mmp->scalar) {\n        const uint32_t shader_core_count')

# Scope rectangular specializations to dense FP16/Q8 medium pipelines. Sharing
# the global denominator vectors would also alter unrelated MUL_MAT_ID families.
replace(vk, '                                              uint32_t align, bool disable_robustness = false, bool require_full_subgroups = false, uint32_t required_subgroup_size = 0) {\n\n        if (!require_full_subgroups',
        '                                              uint32_t align, bool disable_robustness = false, bool require_full_subgroups = false, uint32_t required_subgroup_size = 0) {\n'
        '        std::vector<uint32_t> altiro_specs;\n'
        '        const std::string altiro_name = altiro_lab_tile() >= 4 ? name : "";\n'
        '        if (altiro_lab_tile() >= 4 && !device->coopmat_support && !device->coopmat2 &&\n'
        '            specialization_constants.size() == 11 && altiro_name.size() > 2 && altiro_name.substr(altiro_name.size()-2) == "_m" &&\n'
        '            (altiro_name.rfind("matmul_f16_",0) == 0 || altiro_name.rfind("matmul_q8_0_f32",0) == 0)) {\n'
        '            altiro_specs = specialization_constants;\n'
        '            const uint32_t bm = altiro_lab_tile() == 4 ? 64 : 32, bn = altiro_lab_tile() == 4 ? 32 : 64;\n'
        '            altiro_specs[0] = 128; altiro_specs[1] = bm; altiro_specs[2] = bn;\n'
        '            altiro_specs[4] = subgroup_size_8; altiro_specs[5] = 16; altiro_specs[6] = 2;\n'
        '            altiro_specs[7] = 4; altiro_specs[8] = 2; altiro_specs[9] = 1; altiro_specs[10] = subgroup_size_8;\n'
        '            wg_denoms = {bm,bn,1};\n'
        '        }\n'
        '        if (!require_full_subgroups')
replace(vk, '                                          parameter_count, wg_denoms, specialization_constants, disable_robustness, require_full_subgroups, required_subgroup_size));',
        '                                          parameter_count, wg_denoms, (altiro_specs.empty() ? specialization_constants : altiro_specs), disable_robustness, require_full_subgroups, required_subgroup_size));')

# Shape-specific intervals complement the coarse operation groups. Fused groups
# remain explicitly grouped, never duplicated as if each node took the full span.
replace(vk, '                ctx->perf_logger->log_timing(nodes, names, uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));',
        '                altiro_lab_count("profile_fused_groups");\n'
        '                altiro_lab_count("profile_fused_ns", uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));\n'
        '                ctx->perf_logger->log_timing(nodes, names, uint64_t((timestamps[i] - timestamps[i-1]) * ctx->device->properties.limits.timestampPeriod));')

# Lab-only scalar experiments. Ordinary graph execution is unchanged.
replace(vk, '    // Spin (w/pause) waiting for the graph to finish executing.', '''    AltiroLabTimer lab_wait("final_fence_wait_us");
    if (altiro_lab_variant(31) || altiro_lab_variant(41)) {
        altiro_lab_count("blocking_final_fence");
        VK_CHECK(ctx->device->device.waitForFences({ctx->fence}, true, UINT64_MAX), "lab final fence");
        ctx->device->device.resetFences({ctx->fence});
        return;
    }
    // Spin (w/pause) waiting for the graph to finish executing.''')
# Keep the existing rectangular experiments exactly scoped to their own IDs.
replace(vk, 'altiro_lab_tile() >= 4', '(altiro_lab_tile() == 4 || altiro_lab_tile() == 5)', count=3)
replace(vk, '        std::vector<uint32_t> altiro_specs;', '''        std::vector<uint32_t> altiro_specs;
        if (altiro_lab_tile() == 6 &&
            altiro_tile256_supported(device->subgroup_size, device->properties.limits.maxComputeWorkGroupInvocations,
                device->properties.limits.maxComputeWorkGroupSize[0], device->coopmat_support || device->coopmat2,
                device->properties.limits.maxComputeSharedMemorySize) &&
            specialization_constants.size() == 11 && std::string(name).size() > 2 && std::string(name).substr(std::string(name).size()-2) == "_m" &&
            (std::string(name).rfind("matmul_f16_",0) == 0 || std::string(name).rfind("matmul_q8_0_f32",0) == 0)) {
            altiro_specs = {256,64,64,32,16,16,2,2,2,1,16};
            wg_denoms = {64,64,1};
        }''')
replace(vk, '    if (altiro_lab_tile() == 1 && has_s', '''    if (altiro_lab_tile() == 6 && has_m &&
        altiro_tile256_supported(ctx->device->subgroup_size, ctx->device->properties.limits.maxComputeWorkGroupInvocations,
            ctx->device->properties.limits.maxComputeWorkGroupSize[0], ctx->device->coopmat_support || ctx->device->coopmat2,
            ctx->device->properties.limits.maxComputeSharedMemorySize) &&
        (src0_type == GGML_TYPE_F16 || src0_type == GGML_TYPE_Q8_0) && (aligned ? mmp->a_m : mmp->m)) {
        altiro_lab_count("tile256_routes");
        return aligned ? mmp->a_m : mmp->m;
    }
    if (altiro_lab_tile() == 1 && has_s''')
replace(vk, '    const uint32_t split_k = ggml_vk_guess_split_k(ctx, ne01, ne11, ne10, disable_split_k, pipeline);', '''    const int lab_split = altiro_encoder_lab ? altiro_split_factor(altiro_encoder_lab->variant, ne01, ne10, disable_split_k) : 1;
    const uint32_t split_k = lab_split > 1 ? uint32_t(lab_split) : ggml_vk_guess_split_k(ctx, ne01, ne11, ne10, disable_split_k, pipeline);
    altiro_lab_count(("effective_split_" + std::to_string(split_k)).c_str());
    altiro_lab_count("matmul_workgroups", CEIL_DIV(ne01, pipeline->wg_denoms[0]) * CEIL_DIV(ne11, pipeline->wg_denoms[1]) * ne12 * ne13 * split_k);
    altiro_lab_count("split_scratch_bytes", split_k > 1 ? sizeof(float) * d_ne * split_k : 0);''')
# Boundary queries use command-buffer reset, the actual compute queue's valid
# bits, and a final all-commands timestamp BEFORE final submission. No per-op
# profiler, no extra inter-operation waits. Pool destruction follows completion.
replace(vk, '    vk::QueryPool query_pool;', '    vk::QueryPool altiro_boundary_pool;\n    vk::QueryPool query_pool;')
replace(vk, '    if (submit || last_node) {\n        ggml_vk_ctx_end(compute_ctx);', '''    if (submit || last_node) {
        if (last_node && ctx->altiro_boundary_pool) {
            compute_ctx->s->buffer->buf.writeTimestamp(vk::PipelineStageFlagBits::eAllCommands, ctx->altiro_boundary_pool, 1);
        }
        ggml_vk_ctx_end(compute_ctx);''')
replace(vk, '    int last_node = cgraph->n_nodes - 1;', '''    struct AltiroBoundary {
        ggml_backend_vk_context *ctx;
        uint32_t bits = 0;
        ~AltiroBoundary() {
            if (ctx->altiro_boundary_pool) {
                ggml_vk_synchronize(ctx);
                ctx->device->device.destroyQueryPool(ctx->altiro_boundary_pool);
                ctx->altiro_boundary_pool = nullptr;
            }
        }
    } lab_boundary{ctx};
    if (altiro_lab_boundary() && !vk_perf_logger_enabled) {
        altiro_encoder_lab->boundary_available = false;
        const auto families = ctx->device->physical_device.getQueueFamilyProperties();
        lab_boundary.bits = families.at(ctx->device->compute_queue.queue_family_index).timestampValidBits;
        if (lab_boundary.bits > 0 && lab_boundary.bits <= 64 && ctx->device->properties.limits.timestampPeriod > 0) {
            vk::QueryPoolCreateInfo info{};
            info.queryType = vk::QueryType::eTimestamp; info.queryCount = 2;
            ctx->altiro_boundary_pool = ctx->device->device.createQueryPool(info);
            auto subctx = ggml_vk_get_compute_ctx(ctx);
            subctx->s->buffer->buf.resetQueryPool(ctx->altiro_boundary_pool, 0, 2);
            subctx->s->buffer->buf.writeTimestamp(vk::PipelineStageFlagBits::eAllCommands, ctx->altiro_boundary_pool, 0);
        } else altiro_lab_count("boundary_timestamps_unavailable");
    }
    const auto lab_enqueue_start = std::chrono::steady_clock::now();
    int last_node = cgraph->n_nodes - 1;''')
replace(vk, '    ctx->last_total_mul_mat_bytes = total_mul_mat_bytes;', '''    ctx->last_total_mul_mat_bytes = total_mul_mat_bytes;
    if (altiro_encoder_lab && altiro_encoder_lab->measuring)
        altiro_encoder_lab->us["graph_enqueue_us"] += std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-lab_enqueue_start).count();
    if (ctx->altiro_boundary_pool) {
        ggml_vk_synchronize(ctx);
        uint64_t stamps[2]{};
        VK_CHECK(ctx->device->device.getQueryPoolResults(ctx->altiro_boundary_pool, 0, 2, sizeof(stamps), stamps, sizeof(uint64_t),
            vk::QueryResultFlagBits::e64 | vk::QueryResultFlagBits::eWait), "lab boundary timestamp results");
        altiro_encoder_lab->boundary_gpu_ns = uint64_t(altiro_timestamp_delta(stamps[0], stamps[1], lab_boundary.bits) * ctx->device->properties.limits.timestampPeriod);
        altiro_encoder_lab->boundary_available = true;
    }''')

# A separate dense GEMM shader preserves the stock load/FMA/store ordering,
# then applies F32 ADD and optional SiLU. The old mat-vec fusion is untouched.
shader_dir = root / 'ggml/src/ggml-vulkan/vulkan-shaders'
fused_shader = shader_dir / 'altiro_mul_mm.comp'
fused_shader.write_text((shader_dir / 'mul_mm.comp').read_text())
replace(fused_shader, 'layout (binding = 2) writeonly buffer D {D_TYPE data_d[];};', '''layout (binding = 2) writeonly buffer D {D_TYPE data_d[];};
layout (binding = 3) readonly buffer Bias {float data_bias[];};
layout (constant_id = 11) const uint ALTIRO_SILU = 0;
float altiro_epilogue(float value, uint row) {
    // Materialize the old D_TYPE store before ADD; precise prevents an FMA
    // contraction across the original tensor-write rounding boundary.
    precise float stored = float(D_TYPE(value));
    precise float biased = stored + data_bias[row];
    return ALTIRO_SILU != 0 ? biased / (1.0f + exp(-biased)) : biased;
}''')
replace(fused_shader, '= D_TYPE(sums[sums_idx].x);', '= D_TYPE(altiro_epilogue(float(sums[sums_idx].x), dr_warp + 2 * cr));', count=2)
replace(fused_shader, '= D_TYPE(sums[sums_idx].y);', '= D_TYPE(altiro_epilogue(float(sums[sums_idx].y), dr_warp + 2 * cr + 1));', count=2)
gen = shader_dir / 'vulkan-shaders-gen.cpp'
replace(gen, '        // don\'t generate f32 variants for coopmat2', '''        // Only the two scalar F32-accumulator formats used by the lab.
        if (matmul_id_type == MatMulIdType::NONE && !coopmat && !coopmat2 && !f16acc &&
            (fp16 && (tname == "f16" || tname == "q8_0"))) {
            string_to_spv("altiro_dense_" + tname, "altiro_mul_mm.comp", merge_maps(merge_maps(base_dict, float_type_dict),
                {{data_a_key,"1"},{"LOAD_VEC_A",load_vec_a_unaligned},{"B_TYPE","float"},{"D_TYPE","float"}}), fp16,false,false,false);
            string_to_spv("altiro_dense_" + tname + "_aligned", "altiro_mul_mm.comp", merge_maps(merge_maps(base_dict, float_type_dict),
                {{data_a_key,"1"},{"LOAD_VEC_A",load_vec_a},{"LOAD_VEC_B",load_vec},{"B_TYPE",aligned_b_type_f32},{"D_TYPE","float"},{"ALIGNED","1"}}), fp16,false,false,false);
        }
        // don't generate f32 variants for coopmat2''')
replace(vk, '    vk_matmul_pipeline2 pipeline_matmul_f16_f32;', '''    // storage (F16/Q8), aligned, epilogue (bias/bias+SiLU).
    vk_pipeline altiro_dense[2][2][2];
    vk_matmul_pipeline2 pipeline_matmul_f16_f32;''')
# Registration occurs after scalar geometry and capability screening. Only lab
# workers instantiate these pipelines; ordinary runtime never compiles them.
# Insert before the main pipeline creation branches, after all geometry exists.
# The final loader compiles all queued requests before returning. Register the
# four extra SPIR-V sources at the start of scalar FP16 registration.
replace(vk, '    if (device->fp16) {\n        // Create 6 variants, {s,m,l}x{unaligned,aligned}', '''    if (device->fp16) {
        if (altiro_lab_fusion() && device->subgroup_size == 16 &&
            !device->coopmat_support && !device->coopmat2 &&
            device->properties.limits.maxComputeWorkGroupInvocations >= 128 &&
            device->properties.limits.maxComputeWorkGroupSize[0] >= 128 &&
            device->properties.limits.maxComputeSharedMemorySize >= 8704) {
            for (int type = 0; type < 2; ++type) for (int aligned = 0; aligned < 2; ++aligned) for (int silu = 0; silu < 2; ++silu) {
                if (!device->mul_mat_m[type ? GGML_TYPE_Q8_0 : GGML_TYPE_F16]) continue;
                auto specs = type ? m_warptile_mmq : m_warptile;
                specs.push_back(silu);
                const auto bytes = type ? (aligned ? altiro_dense_q8_0_aligned_len : altiro_dense_q8_0_len)
                                        : (aligned ? altiro_dense_f16_aligned_len : altiro_dense_f16_len);
                const auto data = type ? (aligned ? altiro_dense_q8_0_aligned_data : altiro_dense_q8_0_data)
                                       : (aligned ? altiro_dense_f16_aligned_data : altiro_dense_f16_data);
                const std::string name = std::string("matmul_altiro_dense_") + (type ? "q8" : "f16") + (aligned ? "_aligned" : "") + (silu ? "_bias_silu_m" : "_bias_m");
                ggml_vk_create_pipeline(device, device->altiro_dense[type][aligned][silu], name.c_str(), bytes, data,
                    "main", 4, sizeof(vk_mat_mat_push_constants), m_wg_denoms, specs, aligned ? m_align : 1);
            }
        }
        // Create 6 variants, {s,m,l}x{unaligned,aligned}''')
# Dispatch directly into the final tensor, keeping original source tensors and
# graph allocation intact. Consumer/alias checks are applied before elision.
replace(vk, '    // Handle huge A matrix by splitting the M dimensions.', '''    if (altiro_lab_fusion() && ctx->num_additional_fused_ops && dst->ne[1] > 1) {
        auto * add = cgraph->nodes[node_idx + 1];
        auto * bias = add->src[0] == dst ? add->src[1] : add->src[0];
        auto * output = cgraph->nodes[node_idx + ctx->num_additional_fused_ops];
        const uint32_t m = dst->ne[0], n = dst->ne[1], k = src0->ne[0];
        const bool aligned = k % 64 == 0;
        auto pipeline = ctx->device->altiro_dense[src0->type == GGML_TYPE_Q8_0][aligned][ctx->num_additional_fused_ops == 2];
        GGML_ASSERT(pipeline);
        ggml_pipeline_request_descriptor_sets(ctx, pipeline, 1);
        const vk_mat_mat_push_constants pc = {m,n,k,k,k,m,k*m,k*n,m*n,0,1,k,1,1,1,1,n};
        ggml_vk_dispatch_pipeline(ctx, subctx, pipeline,
            {ggml_vk_tensor_subbuffer(ctx,src0),ggml_vk_tensor_subbuffer(ctx,src1),
             ggml_vk_tensor_subbuffer(ctx,output),ggml_vk_tensor_subbuffer(ctx,bias)}, pc, {m,n,1});
        altiro_lab_count("dense_bias_routes");
        if (ctx->num_additional_fused_ops == 2) altiro_lab_count("dense_silu_routes");
        altiro_lab_count("effective_split_1");
        altiro_lab_count("matmul_workgroups", CEIL_DIV(m,64)*CEIL_DIV(n,64));
        return;
    }
    // Handle huge A matrix by splitting the M dimensions.''')
# Separate predicate rather than broadening mat-vec eligibility. The generic
# fusion consumer analysis and existing overlap rejection remain authoritative.
replace(vk, 'static bool ggml_vk_can_fuse(const ggml_backend_vk_context * ctx,', '''static bool ggml_vk_tensors_overlap(const ggml_tensor * a, const ggml_tensor * b, bool elementwise);
static int altiro_dense_fusion(const ggml_backend_vk_context *ctx, const ggml_cgraph *graph, int index) {
    if (!altiro_lab_fusion() || index + 1 >= graph->n_nodes || graph->nodes[index]->op != GGML_OP_MUL_MAT ||
        graph->nodes[index+1]->op != GGML_OP_ADD) return 0;
    const auto *mm = graph->nodes[index], *add = graph->nodes[index+1];
    const auto *w = mm->src[0], *x = mm->src[1];
    const auto *bias = add->src[0] == mm ? add->src[1] : add->src[0];
    const auto reject = [](){ altiro_lab_count("dense_fusion_unavailable"); return 0; };
    if (ctx->device->coopmat_support || ctx->device->coopmat2 || ctx->device->subgroup_size != 16 ||
        !w || !x || !bias || (w->type != GGML_TYPE_F16 && w->type != GGML_TYPE_Q8_0) ||
        x->type != GGML_TYPE_F32 || bias->type != GGML_TYPE_F32 || mm->type != GGML_TYPE_F32 ||
        (ggml_prec)mm->op_params[0] != GGML_PREC_F32 || mm->ne[0] <= 32 || mm->ne[1] <= 32 ||
        !ggml_is_matrix(w) || !ggml_is_matrix(x) || !ggml_is_matrix(mm) ||
        ggml_nelements(bias) != mm->ne[0] || bias->ne[0] != mm->ne[0] ||
        !ggml_is_contiguous(w) || !ggml_is_contiguous(x) || !ggml_is_contiguous(bias) ||
        !ggml_is_contiguous(add) || !ggml_are_same_shape(mm,add)) return reject();
    const bool aligned = w->ne[0] % 64 == 0;
    if (!ctx->device->altiro_dense[w->type == GGML_TYPE_Q8_0][aligned][0]) return reject();
    int fused = 1;
    if (!ggml_can_fuse(graph,index,{GGML_OP_MUL_MAT,GGML_OP_ADD})) return reject();
    if (altiro_lab_fusion() == 2 && index + 2 < graph->n_nodes &&
        graph->nodes[index+2]->op == GGML_OP_UNARY && ggml_get_unary_op(graph->nodes[index+2]) == GGML_UNARY_OP_SILU &&
        ggml_can_fuse(graph,index,{GGML_OP_MUL_MAT,GGML_OP_ADD,GGML_OP_UNARY})) fused = 2;
    const auto *out = graph->nodes[index+fused];
    if (out->type != GGML_TYPE_F32 || !ggml_is_contiguous(out) || !ggml_are_same_shape(mm,out)) return reject();
    // The generic overlap helper casts Vulkan buffer contexts. Imported UMA
    // host buffers are supported by ordinary matmul, but excluded from this lab.
    if (!out->buffer || !ggml_backend_buffer_is_vk(out->buffer)) return reject();
    for (const auto *input : {w,x,bias})
        if (!input->buffer || !ggml_backend_buffer_is_vk(input->buffer)) return reject();
    // Include leaf inputs (the generic overlap pass skips GGML_OP_NONE).
    for (const auto *input : {w,x,bias}) {
        if (get_misalign_bytes(ctx,input) || ggml_nbytes(input) > ctx->device->properties.limits.maxStorageBufferRange ||
            ggml_vk_tensors_overlap(input,out,false)) return reject();
    }
    if (get_misalign_bytes(ctx,out) || ggml_nbytes(out) > ctx->device->properties.limits.maxStorageBufferRange) return reject();
    return fused;
}

static bool ggml_vk_can_fuse(const ggml_backend_vk_context * ctx,''')
replace(vk, '            if (num_adds) {', '''            const int lab_dense = altiro_dense_fusion(ctx,cgraph,i);
            if (lab_dense) {
                ctx->num_additional_fused_ops = lab_dense;
                fusion_string = lab_dense == 2 ? "DENSE_GEMM_BIAS_SILU" : "DENSE_GEMM_BIAS";
                std::fill_n(op_srcs_fused_elementwise, lab_dense + 1, false);
            } else if (num_adds) {''')

stamp.write_text(digest)
