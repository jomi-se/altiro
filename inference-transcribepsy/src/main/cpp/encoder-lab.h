#pragma once
#include "ggml-backend.h"
#include "lab-small-tile.h"
#include "lab-experiments.h"
#include <chrono>
#include <cstdint>
#include <map>
#include <string>
#include <functional>
#include <atomic>

// Only installed for an explicit lab call, on the sole inference thread.
struct AltiroEncoderLab {
    int variant = 0;
    uint64_t boundary_gpu_ns = 0;
    bool boundary_available = false;
    bool measuring = false;
    std::map<std::string, double> us;
    std::map<std::string, uint64_t> counts;
    std::map<std::string, uint64_t> shaders;
    std::map<std::string, uint64_t> gpu_ns;
    std::map<std::string, uint64_t> gpu_shapes_ns;
    std::map<std::string, uint64_t> gpu_shapes_calls;
};
extern thread_local AltiroEncoderLab * altiro_encoder_lab;
inline bool altiro_lab_variant(int variant) {
    return altiro_encoder_lab && altiro_encoder_lab->variant == variant;
}
inline int altiro_lab_tile() {
    if (altiro_lab_variant(11) || altiro_lab_variant(13) || altiro_lab_variant(21)) return 1;
    if (altiro_lab_variant(12) || altiro_lab_variant(22)) return 3;
    if (altiro_lab_variant(15) || altiro_lab_variant(28)) return 4;
    if (altiro_lab_variant(16) || altiro_lab_variant(29)) return 5;
    if (altiro_lab_variant(32) || altiro_lab_variant(42)) return 6;
    return 0;
}
inline bool altiro_lab_f32() {
    return altiro_encoder_lab && altiro_f32_variant(altiro_encoder_lab->variant);
}
inline int altiro_lab_fusion() {
    if (altiro_lab_variant(35) || altiro_lab_variant(45)) return 1;
    if (altiro_lab_variant(36) || altiro_lab_variant(46)) return 2;
    return 0;
}
inline bool altiro_lab_boundary() {
    return altiro_encoder_lab && altiro_encoder_lab->measuring &&
        (altiro_focused_variant(altiro_encoder_lab->variant) || altiro_packed_variant(altiro_encoder_lab->variant));
}
inline void altiro_lab_count(const char * name, uint64_t value = 1) {
    if (altiro_encoder_lab && altiro_encoder_lab->measuring)
        altiro_encoder_lab->counts[name] += value;
}
inline void altiro_lab_shader(const char * name) {
    if (!altiro_encoder_lab || !altiro_encoder_lab->measuring) return;
    auto & shaders = altiro_encoder_lab->shaders;
    auto found = shaders.find(name);
    if (found != shaders.end()) ++found->second;
    else if (shaders.size() < 64) shaders.emplace(name, 1);
    else altiro_lab_count("shader_labels_dropped");
}
inline void altiro_lab_gpu_time(const ggml_tensor * node, uint64_t ns) {
    if (!altiro_encoder_lab || !altiro_encoder_lab->measuring || !node) return;
    auto & lab = *altiro_encoder_lab;
    lab.gpu_ns[ggml_op_name(node->op)] += ns;
    std::string key = ggml_op_name(node->op);
    // Batch dimensions and contiguity distinguish different graph workloads.
    // kernels. Never use tensor names: they may belong to a model or an input.
    for (int s = 0; s < 2 && node->src[s]; ++s) {
        auto * src = node->src[s];
        key += std::string(":") + ggml_type_name(src->type);
        for (int d = 0; d < 4; ++d) key += "x" + std::to_string(src->ne[d]);
        key += ggml_is_contiguous(src) ? ":C" : ":S";
    }
    if (lab.gpu_shapes_ns.count(key) || lab.gpu_shapes_ns.size() < 80) {
        lab.gpu_shapes_ns[key] += ns;
        ++lab.gpu_shapes_calls[key];
    } else altiro_lab_count("gpu_shape_labels_dropped");
}
class AltiroLabTimer {
    const char * name;
    AltiroEncoderLab * lab;
    std::chrono::steady_clock::time_point start;
public:
    explicit AltiroLabTimer(const char * name): name(name), lab(altiro_encoder_lab), start(std::chrono::steady_clock::now()) {}
    ~AltiroLabTimer() {
        if (lab && lab->measuring) lab->us[name] += std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now()-start).count();
    }
};
void altiro_lab_prepare_graph(ggml_backend_t backend, ggml_cgraph * graph);
// Deterministic synthetic inputs only. Returns a fixed failure code or nullptr.
const char * altiro_kernel_hunt(bool gpu, int threads, int variant, bool quantized, int encoder_frames,
    std::atomic<bool> & cancelled, const std::function<void(const std::string &)> & emit);
const char * altiro_packed_hunt(bool gpu, int variant, bool quantized, int encoder_frames,
    std::atomic<bool> & cancelled, const std::function<void(const std::string &)> & emit);
