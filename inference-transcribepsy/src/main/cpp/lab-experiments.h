#pragma once
#include <cstdint>

inline bool altiro_focused_variant(int v) { return v >= 40 && v <= 46; }
inline bool altiro_synthetic_variant(int v) { return (v >= 20 && v <= 29) || altiro_focused_variant(v); }
inline bool altiro_supported_variant(int v) {
    return (v >= 0 && v <= 16) || altiro_synthetic_variant(v) || (v >= 30 && v <= 36);
}
inline bool altiro_f32_variant(int v) {
    return v == 6 || v == 13 || v == 14 || v == 15 || v == 16 ||
        (v >= 23 && v <= 29) || (v >= 30 && v <= 36) || altiro_focused_variant(v);
}
inline int altiro_split_factor(int v, uint32_t m, uint32_t k, bool disabled) {
    if (disabled || m != 640 || k != 2560) return 1;
    return (v == 33 || v == 43) ? 2 : (v == 34 || v == 44) ? 4 : 1;
}
inline bool altiro_tile256_supported(uint32_t subgroup, uint32_t invocations, uint32_t size_x,
    bool cooperative, uint32_t shared_bytes) {
    return !cooperative && subgroup == 16 && invocations >= 256 && size_x >= 256 && shared_bytes >= 17408;
}
// Mirrors pinned mel_preprocess.cpp (centered STFT) and the three stride-2,
// kernel-3 subsampling convolutions in build_encoder_graph_cached. Metadata is
// read from the verified GGUF; no model weights or audio features are loaded.
inline int altiro_encoder_frames(uint32_t samples, uint32_t hop, bool causal) {
    if (!hop) return 0;
    uint32_t frames = 1 + samples / hop;
    for (int layer = 0; layer < 3; ++layer) frames = causal ? frames / 2 + 1 : (frames + 1) / 2;
    return int(frames);
}
inline uint64_t altiro_timestamp_delta(uint64_t start, uint64_t end, uint32_t bits) {
    if (!bits || bits > 64) return 0;
    return bits == 64 ? end - start : (end - start) & ((uint64_t(1) << bits) - 1);
}
