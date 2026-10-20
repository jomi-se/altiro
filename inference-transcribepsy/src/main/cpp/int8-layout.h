#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

struct AltiroInt8Block { uint32_t q[8]; float scale; };
static_assert(sizeof(AltiroInt8Block)==36, "Shader std430 block layout");
inline size_t altiro_int8_block_index(size_t row,size_t block,size_t blocks) {
    return ((row/4)*blocks+block)*4+row%4;
}
inline size_t altiro_int8_block_count(size_t rows,size_t k) { return ((rows+3)/4)*4*(k/32); }
inline int altiro_int8_signed_byte(uint32_t word,unsigned lane) {
    const unsigned byte=(word>>(lane*8))&255;
    return byte>=128?int(byte)-256:int(byte);
}
inline int64_t altiro_int8_dot32(const AltiroInt8Block & w,const AltiroInt8Block & x) {
    int64_t sum=0;
    for(unsigned i=0;i<8;i++) for(unsigned lane=0;lane<4;lane++)
        sum+=int64_t(altiro_int8_signed_byte(w.q[i],lane))*altiro_int8_signed_byte(x.q[i],lane);
    return sum;
}
inline bool altiro_int8_quantization_valid(const float * x,const AltiroInt8Block & block,unsigned & ties) {
    float maximum=0;
    for(unsigned i=0;i<32;i++) {
        if(!std::isfinite(x[i])) return false;
        maximum=std::max(maximum,std::abs(x[i]));
    }
    const float expected_scale=maximum/127.0f;
    if(!std::isfinite(block.scale) || block.scale<0 ||
        std::abs(double(block.scale)-expected_scale)>std::max(1e-30, double(expected_scale)*1e-6)) return false;
    if(maximum==0) {
        if(block.scale!=0) return false;
        for(uint32_t word:block.q) if(word) return false;
        return true;
    }
    if(block.scale==0) return false;
    for(unsigned i=0;i<32;i++) {
        const double normalized=double(x[i])/block.scale;
        const int expected=std::max(-127,std::min(127,int(std::copysign(std::floor(std::abs(normalized)+0.5),normalized))));
        const int actual=altiro_int8_signed_byte(block.q[i/4],i%4);
        if(actual<-127 || actual>127) return false;
        if(actual!=expected) {
            // Vulkan division may round on either side of an exact half tie.
            // Accept only its two adjacent integers; never a general ±1 error.
            const double fraction=std::abs(normalized)-std::floor(std::abs(normalized));
            const int lower=int(std::floor(std::abs(normalized))),sign=normalized<0?-1:1;
            if((actual!=sign*lower && actual!=sign*(lower+1)) || std::abs(fraction-0.5)>1e-4) return false;
            ++ties;
        }
    }
    return true;
}
