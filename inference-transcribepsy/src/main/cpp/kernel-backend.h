#pragma once
#include "ggml-backend.h"

// Restrict experiments to the requested backend. GGML distinguishes dedicated
// GPUs from integrated GPUs; a phone's Vulkan device normally has type IGPU.
inline ggml_backend_t altiro_kernel_backend(bool gpu) {
    auto reg = ggml_backend_reg_by_name(gpu ? "Vulkan" : "CPU");
    if (!reg) return nullptr;
    for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
        auto dev = ggml_backend_reg_dev_get(reg, i);
        const auto type = ggml_backend_dev_type(dev);
        if (gpu ? (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)
                : type == GGML_BACKEND_DEVICE_TYPE_CPU) {
            // Initialization failure is a failure, never permission to retry
            // on CPU or silently change to another graphics API/device.
            return ggml_backend_dev_init(dev, nullptr);
        }
    }
    return nullptr;
}
