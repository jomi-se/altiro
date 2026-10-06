#!/usr/bin/env python3
"""Apply small audited changes to pinned source; reject revision drift."""
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
before = """    ggml_backend_t backend_gpu = whisper_backend_init_gpu(params);

    if (backend_gpu) {"""
after = """    ggml_backend_t backend_gpu = whisper_backend_init_gpu(params);

    // Altiro experiment: never disguise a failed GPU initialization as CPU.
    if (params.use_gpu && !backend_gpu) {
        throw std::runtime_error("Altiro requested GPU backend unavailable");
    }

    if (backend_gpu) {"""
metrics_anchor = "\nvoid whisper_print_timings(struct whisper_context * ctx) {"
metrics = """
// Altiro diagnostics: total counters instead of whisper_get_timings' per-call averages.
extern "C" void altiro_whisper_compute_totals(whisper_context * ctx, int64_t * values) {
    if (!ctx || !ctx->state) return;
    values[0] = ctx->state->t_encode_us / 1000;
    values[1] = ctx->state->t_decode_us / 1000;
    values[2] = ctx->state->t_batchd_us / 1000;
    values[3] = ctx->state->t_prompt_us / 1000;
    values[4] = ctx->state->t_sample_us / 1000;
    values[5] = ctx->state->n_encode;
    values[6] = ctx->state->n_decode;
}
extern "C" bool altiro_whisper_gpu_active(whisper_context * ctx) {
    if (!ctx || !ctx->state) return false;
    for (auto backend : ctx->state->backends) {
        auto device = ggml_backend_get_device(backend);
        auto type = ggml_backend_dev_type(device);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}
"""
if not (source.count(after) == 1 and source.count(metrics) == 1):
    if source.count(before) != 1 or source.count(metrics_anchor) != 1 or source.count('#include "whisper.h"') != 1:
        sys.exit("Pinned Whisper diagnostics anchors changed; review before building")
    source = source.replace(before, after).replace(metrics_anchor, metrics + metrics_anchor)
    source = source.replace('#include "whisper.h"', '#include "whisper.h"\n#include <stdexcept>')
    path.write_text(source)

if len(sys.argv) > 2:
    cmake_path = Path(sys.argv[2])
    cmake = cmake_path.read_text()
    old_args = "CMAKE_ARGS -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}/$<CONFIG>"
    new_args = "CMAKE_ARGS -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}\n                   -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}/$<CONFIG>"
    if cmake.count(new_args) != 1:
        if cmake.count(old_args) != 1:
            sys.exit("Pinned Vulkan shader-generator anchor changed; review before building")
        cmake_path.write_text(cmake.replace(old_args, new_args))

if len(sys.argv) > 3:
    gpu_path = Path(sys.argv[3])
    gpu = gpu_path.read_text()
    init = "static void ggml_vk_load_shaders(vk_device& device, vk_pipeline requested) {"
    bounded_init = """// Altiro: bound Android Release compilation of the huge pipeline-registration
// function. Numerical tensor code and GPU shader programs remain optimized.
static void
#if defined(__ANDROID__) && defined(__clang__) && defined(NDEBUG)
__attribute__((optnone))
#endif
ggml_vk_load_shaders(vk_device& device, vk_pipeline requested) {"""
    if gpu.count(bounded_init) != 1:
        if gpu.count(init) != 1:
            sys.exit("Pinned Vulkan registration anchor changed; review before building")
        gpu_path.write_text(gpu.replace(init, bounded_init))
