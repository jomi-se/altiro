#pragma once
#include "ggml-backend.h"
#include <chrono>
#include <cstdint>
#include <map>
#include <string>

// Only installed for an explicit lab call, on the sole inference thread.
struct AltiroEncoderLab {
    int variant = 0;
    bool measuring = false;
    std::map<std::string, double> us;
    std::map<std::string, uint64_t> counts;
    std::map<std::string, uint64_t> shaders;
    std::map<std::string, uint64_t> gpu_ns;
};
extern thread_local AltiroEncoderLab * altiro_encoder_lab;
inline bool altiro_lab_variant(int variant) {
    return altiro_encoder_lab && altiro_encoder_lab->variant == variant;
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
    if (altiro_encoder_lab && altiro_encoder_lab->measuring && node)
        altiro_encoder_lab->gpu_ns[ggml_op_name(node->op)] += ns;
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
