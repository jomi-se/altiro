#include "kernel-backend.h"
#include "lab-small-tile.h"
#include <cassert>
#include <cstring>
#include <vector>

// Fake devices exercise selection and initialization without a graphics driver.
struct ggml_backend { int marker; } cpu{1}, vulkan{2};
struct ggml_backend_device { enum ggml_backend_dev_type type; ggml_backend_t result; };
struct ggml_backend_reg { std::vector<ggml_backend_device> devices; } cpu_reg, vk_reg;
static bool vulkan_registered = true;
static int initializations = 0;
static const char * requested_registry = nullptr;

ggml_backend_reg_t ggml_backend_reg_by_name(const char * name) {
    requested_registry = name;
    if (!std::strcmp(name, "CPU")) return &cpu_reg;
    assert(!std::strcmp(name, "Vulkan"));
    return vulkan_registered ? &vk_reg : nullptr;
}
size_t ggml_backend_reg_dev_count(ggml_backend_reg_t reg) { return reg->devices.size(); }
ggml_backend_dev_t ggml_backend_reg_dev_get(ggml_backend_reg_t reg, size_t index) { return &reg->devices.at(index); }
enum ggml_backend_dev_type ggml_backend_dev_type(ggml_backend_dev_t dev) { return dev->type; }
ggml_backend_t ggml_backend_dev_init(ggml_backend_dev_t dev, const char * params) {
    assert(!params); ++initializations; return dev->result;
}

static void check_small_tile(uint32_t subgroup) {
    const uint32_t block = subgroup > 32 ? subgroup : 32;
    const uint32_t warp = subgroup < 8 ? 8 : subgroup;
    const uint32_t wm = altiro_small_tile_wm(subgroup, false, true);
    const uint32_t wn = 32, wmiter = 2, tm = 2, tn = 2;
    assert(block / warp == (32 / wm) * (32 / wn));
    assert((wm * wn) % (warp * tm * tn * wmiter) == 0);
    const uint32_t wniter = (wm * wn) / (warp * tm * tn * wmiter);
    assert(wniter);
    const uint32_t wsubm = wm / wmiter, wsubn = wn / wniter;
    std::vector<int> writes(32 * 32);
    // Enumerate the scalar mul_mm.comp output ownership, including every lane,
    // logical warp, sub-tile iteration and per-thread element.
    for (uint32_t lane = 0; lane < block; ++lane) {
        const uint32_t wi = lane / warp, tiw = lane % warp;
        const uint32_t wr = wi % (32 / wm), wc = wi / (32 / wm);
        const uint32_t tr = tiw % (wsubm / tm), tc = tiw / (wsubm / tm);
        for (uint32_t mi = 0; mi < wmiter; ++mi)
        for (uint32_t ni = 0; ni < wniter; ++ni)
        for (uint32_t r = 0; r < tm; ++r)
        for (uint32_t c = 0; c < tn; ++c) {
            const uint32_t row = wr * wm + mi * wsubm + tr * tm + r;
            const uint32_t col = wc * wn + ni * wsubn + tc * tn + c;
            assert(row < 32 && col < 32);
            ++writes.at(row * 32 + col);
        }
    }
    for (int count : writes) assert(count == 1);
}

int main() {
    cpu_reg.devices = {{GGML_BACKEND_DEVICE_TYPE_CPU, &cpu}};
    vk_reg.devices = {{GGML_BACKEND_DEVICE_TYPE_IGPU, &vulkan}};
    assert(altiro_kernel_backend(true) == &vulkan); // Pixel/Mali
    vk_reg.devices[0].type = GGML_BACKEND_DEVICE_TYPE_GPU;
    assert(altiro_kernel_backend(true) == &vulkan); // discrete desktop GPU
    assert(altiro_kernel_backend(false) == &cpu);
    assert(!std::strcmp(requested_registry, "CPU"));
    for (auto rejected : {GGML_BACKEND_DEVICE_TYPE_CPU, GGML_BACKEND_DEVICE_TYPE_ACCEL, GGML_BACKEND_DEVICE_TYPE_META}) {
        vk_reg.devices = {{rejected, &cpu}};
        initializations = 0;
        assert(!altiro_kernel_backend(true));
        assert(initializations == 0);
    }
    vk_reg.devices = {{GGML_BACKEND_DEVICE_TYPE_IGPU, nullptr}, {GGML_BACKEND_DEVICE_TYPE_GPU, &vulkan}};
    initializations = 0;
    assert(!altiro_kernel_backend(true));
    assert(initializations == 1); // no device/backend retry on initialization failure
    vulkan_registered = false;
    initializations = 0;
    assert(!altiro_kernel_backend(true));
    assert(initializations == 0);
    vulkan_registered = true;
    vk_reg.devices.clear();
    assert(!altiro_kernel_backend(true));
    for (uint32_t subgroup : {8, 16, 32, 64}) {
        check_small_tile(subgroup);
        assert(altiro_small_tile_wm(subgroup, true, true) == (subgroup == 8 ? 8 : 32));
        assert(altiro_small_tile_wm(subgroup, false, false) == (subgroup == 8 ? 8 : 32));
    }
}
