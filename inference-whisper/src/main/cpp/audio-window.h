#pragma once

#include <algorithm>
#include <cstddef>

// Whisper has 50 encoder positions/second after its stride-two convolution.
// Size from the complete decoded recording; never discard samples to fit.
inline int altiro_audio_context(std::size_t samples, int full_context, bool dynamic) {
    constexpr std::size_t sample_rate = 16000;
    constexpr std::size_t samples_per_position = 320;
    constexpr std::size_t padding = 50; // one second
    constexpr std::size_t quantum = 250; // five seconds
    if (!dynamic || samples >= 30 * sample_rate || full_context <= 0) return full_context;
    const std::size_t covered = (samples + samples_per_position - 1) / samples_per_position;
    const std::size_t rounded = ((covered + padding + quantum - 1) / quantum) * quantum;
    return static_cast<int>(std::min(rounded, static_cast<std::size_t>(full_context)));
}
