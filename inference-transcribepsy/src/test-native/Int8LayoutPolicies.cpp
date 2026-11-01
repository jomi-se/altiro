#include "int8-layout.h"
#include <array>
#include <cassert>
#include <limits>
#include <vector>

static uint32_t pack(int a,int b,int c,int d) {
    return (uint32_t(a)&255)|((uint32_t(b)&255)<<8)|((uint32_t(c)&255)<<16)|((uint32_t(d)&255)<<24);
}
static AltiroInt8Block quantize(const std::array<float,32> & input) {
    AltiroInt8Block result{};
    float maximum=0;
    for(float value:input) maximum=std::max(maximum,std::abs(value));
    result.scale=maximum/127;
    if(!maximum) return result;
    for(unsigned i=0;i<32;i++) {
        const float value=input[i]/result.scale;
        const int q=int(std::copysign(std::floor(std::abs(value)+0.5f),value));
        result.q[i/4]|=(uint32_t(std::max(-127,std::min(127,q)))&255)<<(8*(i%4));
    }
    return result;
}
static void quantizer_checks() {
    unsigned ties=0;
    std::array<float,32> x{};
    auto q=quantize(x);
    assert(altiro_int8_quantization_valid(x.data(),q,ties));
    q.q[7]=1; assert(!altiro_int8_quantization_valid(x.data(),q,ties));
    x[0]=127; x[1]=-127; x[2]=0.5f; x[3]=-0.5f; x[4]=1.5f; x[5]=-1.5f;
    q=quantize(x);
    assert(q.scale==1 && altiro_int8_quantization_valid(x.data(),q,ties));
    assert(altiro_int8_signed_byte(q.q[0],0)==127);
    assert(altiro_int8_signed_byte(q.q[0],1)==-127);
    assert(altiro_int8_signed_byte(q.q[0],2)==1);
    assert(altiro_int8_signed_byte(q.q[0],3)==-1);
    assert(altiro_int8_signed_byte(q.q[1],0)==2);
    assert(altiro_int8_signed_byte(q.q[1],1)==-2);
    auto altered=q; altered.q[0]&=~(255u<<16); // adjacent exact tie allowed
    assert(altiro_int8_quantization_valid(x.data(),altered,ties) && ties==1);
    altered=q; altered.q[1]|=10u<<24;
    assert(!altiro_int8_quantization_valid(x.data(),altered,ties));
    altered=q; altered.q[1]=(altered.q[1]&~255u)|3; // 1.5 cannot round to 3
    assert(!altiro_int8_quantization_valid(x.data(),altered,ties));
    altered=q; altered.scale*=1.01f;
    assert(!altiro_int8_quantization_valid(x.data(),altered,ties));
    altered=q; altered.scale=std::numeric_limits<float>::quiet_NaN();
    assert(!altiro_int8_quantization_valid(x.data(),altered,ties));
    for(unsigned i=0;i<32;i++) x[i]=float(int(i)-16)/1024;
    q=quantize(x); assert(altiro_int8_quantization_valid(x.data(),q,ties));
}
static void raw_weight_packing(size_t m,size_t k) {
    const size_t blocks=k/32,bytes=m*blocks*34;
    // Raw uint storage is explicitly rounded up for the odd final Q8_0 block.
    std::vector<uint32_t> raw((bytes+3)/4);
    for(size_t i=0;i<bytes;i++) raw[i/4]|=uint32_t((i*79+137)&255)<<(8*(i%4));
    const auto byte_at=[&](size_t i) { assert(i/4<raw.size()); return (raw[i/4]>>(8*(i%4)))&255; };
    std::vector<std::array<uint32_t,9>> packed(altiro_int8_block_count(m,k));
    for(size_t i=0;i<packed.size();i++) {
        const size_t row=(i/(4*blocks))*4+i%4,b=(i/4)%blocks,at=(row*blocks+b)*34;
        if(row>=m) continue;
        packed[i][8]=byte_at(at)|(byte_at(at+1)<<8); // exact original half bits
        for(size_t j=0;j<8;j++) for(size_t lane=0;lane<4;lane++)
            packed[i][j]|=byte_at(at+2+j*4+lane)<<(8*lane);
    }
    std::vector<int> visits(packed.size());
    for(size_t row=0;row<(m+3)/4*4;row++) for(size_t b=0;b<blocks;b++) {
        const auto index=altiro_int8_block_index(row,b,blocks); ++visits.at(index);
        if(row>=m) { for(auto word:packed[index]) assert(word==0); continue; }
        const size_t at=(row*blocks+b)*34;
        assert(packed[index][8]==(((at*79+137)&255)|((((at+1)*79+137)&255)<<8)));
        for(size_t j=0;j<32;j++) assert(((packed[index][j/4]>>(8*(j%4)))&255)==(((at+2+j)*79+137)&255));
    }
    for(int count:visits) assert(count==1);
    if(bytes%4) assert((raw.back()>>(8*(bytes%4)))==0);
}
static void dot_extremes() {
    assert(altiro_int8_signed_byte(pack(-128,-1,0,127),0)==-128);
    assert(altiro_int8_signed_byte(pack(-128,-1,0,127),1)==-1);
    assert(altiro_int8_signed_byte(pack(-128,-1,0,127),2)==0);
    assert(altiro_int8_signed_byte(pack(-128,-1,0,127),3)==127);
    AltiroInt8Block a{},b{};
    for(unsigned i=0;i<8;i++) { a.q[i]=pack(-128,-128,-128,-128); b.q[i]=a.q[i]; }
    assert(altiro_int8_dot32(a,b)==524288); // maximum magnitude safely fits I32
    for(unsigned i=0;i<8;i++) b.q[i]=pack(127,127,127,127);
    assert(altiro_int8_dot32(a,b)==-520192);
}
static void ownership_and_math(size_t m,size_t n,size_t k) {
    const size_t blocks=k/32;
    std::vector<AltiroInt8Block> w(altiro_int8_block_count(m,k)),x(altiro_int8_block_count(n,k));
    for(size_t row=0;row<m;row++) for(size_t b=0;b<blocks;b++) {
        auto & value=w[altiro_int8_block_index(row,b,blocks)];
        value.scale=(b%2?0.5f:0.25f);
        for(unsigned j=0;j<8;j++) value.q[j]=pack(int((row+j)%15)-7,-128,127,int(b)-1);
    }
    for(size_t col=0;col<n;col++) for(size_t b=0;b<blocks;b++) {
        auto & value=x[altiro_int8_block_index(col,b,blocks)];
        value.scale=col%2?0.125f:0.0625f;
        for(unsigned j=0;j<8;j++) value.q[j]=pack(-3,int(col%7)-3,int(b+1),int(j)-4);
    }
    std::vector<int> visits(m*n);
    std::vector<float> output(m*n);
    for(size_t gx=0;gx<(m+31)/32;gx++) for(size_t gy=0;gy<(n+31)/32;gy++)
    for(size_t lx=0;lx<8;lx++) for(size_t ly=0;ly<8;ly++) {
        const size_t rb=gx*8+lx,cb=gy*8+ly,row=rb*4,col=cb*4;
        if(row>=m||col>=n) continue;
        float results[4][4]{};
        for(size_t b=0;b<blocks;b++) for(size_t i=0;i<4;i++) for(size_t j=0;j<4;j++) {
            const auto & a=w[(rb*blocks+b)*4+i]; const auto & z=x[(cb*blocks+b)*4+j];
            // Independently emulate the shader's eight signed packed dot4s.
            int32_t dot=0;
            for(unsigned word=0;word<8;word++) for(unsigned lane=0;lane<4;lane++) {
                const int aa=int((a.q[word]>>(lane*8))&255),bb=int((z.q[word]>>(lane*8))&255);
                dot+=(aa>=128?aa-256:aa)*(bb>=128?bb-256:bb);
            }
            results[j][i]=std::fma(float(dot),a.scale*z.scale,results[j][i]);
        }
        for(size_t i=0;i<4;i++) for(size_t j=0;j<4;j++) if(row+i<m&&col+j<n) {
            const size_t at=(col+j)*m+row+i; ++visits[at]; output[at]=results[j][i];
        }
    }
    for(size_t col=0;col<n;col++) for(size_t row=0;row<m;row++) {
        assert(visits[col*m+row]==1);
        double oracle=0;
        for(size_t b=0;b<blocks;b++) {
            const auto & a=w[altiro_int8_block_index(row,b,blocks)],&z=x[altiro_int8_block_index(col,b,blocks)];
            oracle+=double(altiro_int8_dot32(a,z))*a.scale*z.scale;
        }
        assert(oracle==double(output[col*m+row]));
    }
}
int main() {
    quantizer_checks(); dot_extremes();
    for(size_t m:{1,3,4,31,33,63,65,640,2560}) for(size_t k:{32,64,96}) raw_weight_packing(m,k);
    raw_weight_packing(640,2560); raw_weight_packing(2560,640);
    for(size_t m:{1,3,4,31,33,63,65}) for(size_t n:{1,3,4,31,33,63,65}) ownership_and_math(m,n,96);
}
