#include "packed-layout.h"
#include "lab-experiments.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

static void packing(size_t m,size_t n,size_t k) {
    std::vector<uint16_t> dense(m*k),packed(altiro_packed_elements(m,k),0xffff);
    for(size_t i=0;i<dense.size();i++) dense[i]=uint16_t((i*49157+107)&65535);
    // Emulate the shader's uint pair loads and writes independently of the
    // C++ layout index formula, then check every half bit and padded row.
    for(size_t i=0;i<packed.size()/2;i++) {
        const size_t pair=i%2,kk=(i/2)%k,row=(i/(2*k))*4+pair*2;
        packed[2*i]=row<m?dense[row*k+kk]:0;
        packed[2*i+1]=row+1<m?dense[(row+1)*k+kk]:0;
    }
    std::vector<int> visits(packed.size());
    for(size_t row=0;row<(m+3)/4*4;row++) for(size_t kk=0;kk<k;kk++) {
        const auto index=altiro_packed_weight_index(row,kk,k);
        assert(index<packed.size()); ++visits[index];
        assert(packed[index]==(row<m?dense[row*k+kk]:0));
    }
    for(int count:visits) assert(count==1);
    std::vector<float> x(n*k),px(altiro_packed_elements(n,k),999);
    for(size_t i=0;i<x.size();i++) x[i]=float(int((i*31)%257)-128)/512;
    for(size_t i=0;i<px.size()/4;i++) {
        const size_t col=(i/k)*4,kk=i%k;
        for(size_t j=0;j<4;j++) px[i*4+j]=col+j<n?x[(col+j)*k+kk]:0;
    }
    for(size_t col=0;col<(n+3)/4*4;col++) for(size_t kk=0;kk<k;kk++)
        assert(px[altiro_packed_activation_index(col,kk,k)]==(col<n?x[col*k+kk]:0));
}
static void ownership_and_math(size_t m,size_t n,size_t k) {
    std::vector<float> w(m*k),x(n*k),pw(altiro_packed_elements(m,k)),px(altiro_packed_elements(n,k));
    for(size_t i=0;i<w.size();i++) w[i]=float(int((i*17)%31)-15)/64;
    for(size_t i=0;i<x.size();i++) x[i]=float(int((i*23)%37)-18)/64;
    for(size_t row=0;row<m;row++) for(size_t kk=0;kk<k;kk++) pw[altiro_packed_weight_index(row,kk,k)]=w[row*k+kk];
    for(size_t col=0;col<n;col++) for(size_t kk=0;kk<k;kk++) px[altiro_packed_activation_index(col,kk,k)]=x[col*k+kk];
    std::vector<int> visits(m*n);
    std::vector<float> output(m*n);
    // Enumerate actual 8x8 workgroups and per-invocation 4x4 registers.
    for(size_t gx=0;gx<(m+31)/32;gx++) for(size_t gy=0;gy<(n+31)/32;gy++)
    for(size_t lx=0;lx<8;lx++) for(size_t ly=0;ly<8;ly++) {
        const size_t rb=gx*8+lx,cb=gy*8+ly,row=rb*4,col=cb*4;
        if(row>=m||col>=n) continue;
        float sums[4][4]{};
        for(size_t kk=0;kk<k;kk++) for(size_t i=0;i<4;i++) for(size_t j=0;j<4;j++)
            sums[j][i]=std::fma(pw[(rb*k+kk)*4+i],px[(cb*k+kk)*4+j],sums[j][i]);
        for(size_t i=0;i<4;i++) for(size_t j=0;j<4;j++) if(row+i<m&&col+j<n) {
            const size_t at=(col+j)*m+row+i; ++visits[at]; output[at]=sums[j][i];
        }
    }
    for(size_t col=0;col<n;col++) for(size_t row=0;row<m;row++) {
        const size_t at=col*m+row; assert(visits[at]==1);
        double reference=0;
        for(size_t kk=0;kk<k;kk++) reference+=double(w[row*k+kk])*x[col*k+kk];
        // Dyadic data are exactly representable throughout this small test.
        assert(double(output[at])==reference);
    }
}
int main() {
    for(auto m:{1u,3u,4u,31u,32u,33u,63u,65u,640u,2560u})
        for(auto n:{1u,3u,4u,63u,65u,145u,201u}) packing(m,n,96);
    packing(640,145,2560); packing(2560,201,640);
    for(auto m:{1u,3u,4u,31u,33u,63u,65u})
        for(auto n:{1u,3u,4u,31u,33u,63u,65u}) ownership_and_math(m,n,96);
    for(int v=60;v<=63;v++) assert(altiro_packed_variant(v)&&altiro_synthetic_variant(v)&&altiro_supported_variant(v)&&altiro_f32_variant(v));
    assert(altiro_supported_variant(70)&&!altiro_synthetic_variant(70));
    assert(!altiro_packed_shape_valid(0,145,640));
    assert(!altiro_packed_shape_valid(640,1001,2560));
    assert(!altiro_packed_shape_valid(640,145,2559));
    assert(altiro_packed_shape_valid(65,65,96));
}
