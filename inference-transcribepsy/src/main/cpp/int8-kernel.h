#pragma once
#include <atomic>
#include <functional>
#include <string>

// Synthetic Q8_0 weights only. No model features, text, fallback, or product mode.
const char * altiro_int8_hunt(bool gpu, int variant, bool quantized, int encoder_frames,
    std::atomic<bool> & cancelled, const std::function<void(const std::string &)> & emit);
