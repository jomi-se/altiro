#include "kernel-backend.h"
#include "lab-small-tile.h"
#include "lab-experiments.h"
#include <algorithm>
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

static void check_tile256(int m, int n) {
    const int block=256, warp=16, wm=16, wn=16, wmiter=2, wniter=2, tm=2, tn=2;
    std::vector<int> writes(m*n);
    for (int br=0;br<m;br+=64) for (int bc=0;bc<n;bc+=64)
    for (int lane=0;lane<block;++lane) {
        const int wi=lane/warp, tiw=lane%warp;
        const int wr=wi%4, wc=wi/4, tr=tiw%4, tc=tiw/4;
        for(int mi=0;mi<wmiter;++mi) for(int ni=0;ni<wniter;++ni)
        for(int r=0;r<tm;++r) for(int c=0;c<tn;++c) {
            const int row=br+wr*wm+mi*8+tr*tm+r, col=bc+wc*wn+ni*8+tc*tn+c;
            if(row<m && col<n) ++writes.at(col*m+row);
        }
    }
    for (int count:writes) assert(count==1);
    // Aligned cooperative operand loads, F16 vec8 and Q8 vec4.
    for(int vec:{4,8}) {
        std::vector<int> loads(64*32/vec);
        const int stride=block/(32/vec);
        for(int lane=0;lane<block;++lane) for(int offset=0;offset<64;offset+=stride) {
            const int row=lane/(32/vec)+offset, col=lane%(32/vec);
            assert(row<64); ++loads.at(row*(32/vec)+col);
        }
        for(int count:loads) assert(count==1);
    }
}
static void check_experiments() {
    for(int v=0;v<60;++v) {
        assert(altiro_synthetic_variant(v)==((v>=20&&v<=29)||(v>=40&&v<=46)));
        if (v>=30&&v<=36) assert(!altiro_synthetic_variant(v)&&altiro_f32_variant(v));
        if(v>=37&&v<=39) assert(!altiro_supported_variant(v)&&!altiro_f32_variant(v));
    }
    assert(altiro_encoder_frames(184960,160,false)==145);
    assert(altiro_encoder_frames(1280,160,false)==2);
    assert(altiro_encoder_frames(1279,160,false)==1);
    assert(altiro_encoder_frames(1280,160,true)==2);
    assert(altiro_encoder_frames(1600,0,false)==0);
    assert(altiro_timestamp_delta(250,4,8)==10);
    assert(altiro_timestamp_delta(UINT64_MAX-2,2,64)==5);
    assert(altiro_timestamp_delta(0,1,0)==0);
    assert(altiro_tile256_supported(16,256,256,false,32768));
    assert(!altiro_tile256_supported(32,256,256,false,32768));
    assert(!altiro_tile256_supported(16,128,256,false,32768));
    assert(!altiro_tile256_supported(16,256,128,false,32768));
    assert(!altiro_tile256_supported(16,256,256,true,32768));
    assert(!altiro_tile256_supported(16,256,256,false,16384));
    for(int v:{33,34,43,44}) {
        const int split=altiro_split_factor(v,640,2560,false);
        assert(split==(v==33||v==43?2:4));
        const int span=((2560+split-1)/split+255)/256*256;
        assert(span*(split-1)<2560);
        std::vector<int> visits(2560);
        for(int s=0;s<split;++s) for(int k=s*span;k<std::min(2560,(s+1)*span);++k) ++visits[k];
        for(int count:visits) assert(count==1);
        assert(altiro_split_factor(v,2560,640,false)==1);
        assert(altiro_split_factor(v,640,1280,false)==1);
        assert(altiro_split_factor(v,640,2560,true)==1);
    }
    for(int m:{64,65,640}) for(int n:{63,65,129,145,257,289}) check_tile256(m,n);
}

int main() {
    check_experiments();
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
