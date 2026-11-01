#pragma once
#include <atomic>
#include <functional>
#include <string>
// Fixed upstream ACL kernels and bounded synthetic data only. No arbitrary
// driver paths, source, compiler options or tensors enter this API.
const char* altiro_acl_hunt(bool gpu, int variant, bool quantized, int encoder_frames,
    std::atomic<bool>& cancelled, const std::function<void(const std::string&)>& emit);
