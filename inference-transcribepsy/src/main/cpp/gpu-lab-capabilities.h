#pragma once
#include <string>
// Fixed probes only: callers cannot supply library paths, kernels or work sizes.
std::string altiro_opencl_capabilities(bool (*cancelled)(void*), void* opaque);
#ifdef ALTIRO_VULKAN
#include <vulkan/vulkan.h>
std::string altiro_vulkan_integer_capabilities(VkPhysicalDevice device, uint32_t instance_api_version);
#endif
// One bounded JSON object containing opencl and vulkan objects. Driver calls may
// block; execute only inside the lab's disposable worker with its parent timeout.
std::string altiro_gpu_capabilities(bool (*cancelled)(void*), void* opaque);
