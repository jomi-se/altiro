// Compile against the pinned runtime's ggml/include and ggml/src. Exercises
// its actual inline fusion guard without a GPU, allocator or backend runtime.
#include "ggml-impl.h"
#include <cassert>
#include <cstdlib>
#include <cmath>

extern "C" bool ggml_are_same_shape(const ggml_tensor *a, const ggml_tensor *b) {
    for (int d=0; d<4; ++d) if (a->ne[d]!=b->ne[d]) return false;
    return true;
}
extern "C" void ggml_abort(const char *, int, const char *, ...) { std::abort(); }
int main() {
    ggml_tensor mm{}, add{}, silu{}, bias{}, other{};
    ggml_tensor *nodes[]={&mm,&add,&silu};
    ggml_tensor *keys[32]{}; ggml_bitset_t used[1]{}; int32_t counts[32]{};
    ggml_cgraph graph{}; graph.n_nodes=3; graph.nodes=nodes;
    graph.visited_hash_set={32,used,keys}; graph.use_counts=counts;
    mm.op=GGML_OP_MUL_MAT; add.op=GGML_OP_ADD; silu.op=GGML_OP_UNARY;
    add.src[0]=&mm; add.src[1]=&bias; silu.src[0]=&add;
    for(auto *node:nodes) {
        node->flags=GGML_TENSOR_FLAG_COMPUTE;
        node->ne[0]=640; node->ne[1]=145; node->ne[2]=node->ne[3]=1;
        counts[ggml_hash_insert(&graph.visited_hash_set,node)]=1;
    }
    const auto eligible=[&](){return ggml_can_fuse(&graph,0,{GGML_OP_MUL_MAT,GGML_OP_ADD,GGML_OP_UNARY});};
    assert(eligible());
    const auto mm_index=ggml_hash_find(&graph.visited_hash_set,&mm);
    counts[mm_index]=2; assert(!eligible()); counts[mm_index]=1;
    mm.flags|=GGML_TENSOR_FLAG_OUTPUT; assert(!eligible()); mm.flags=GGML_TENSOR_FLAG_COMPUTE;
    mm.view_src=&other; assert(!eligible()); mm.view_src=nullptr;
    add.view_src=&other; assert(!eligible()); add.view_src=nullptr;
    add.flags|=GGML_TENSOR_FLAG_OUTPUT; assert(!eligible()); add.flags=GGML_TENSOR_FLAG_COMPUTE;
    silu.ne[1]=144; assert(!eligible()); silu.ne[1]=145;
    silu.src[0]=&other; assert(!eligible()); silu.src[0]=&add;
    // Operand order can change for ADD, but edge identity must survive.
    add.src[0]=&bias; add.src[1]=&mm; assert(eligible());
    // A nonlinear epilogue cannot be applied independently to K partials.
    const auto activate=[](float x){return x/(1.0f+std::exp(-x));};
    assert(std::abs(activate(1.0f)+activate(-1.0f)-activate(0.0f))>0.4f);
    // Bias addition must follow the F32 store, even if accumulation/reference
    // uses a wider type: a fused wide add would produce a different result.
    const double dot=16777217.; const float bias_value=-16777216.f;
    const float stored=float(dot), separate=stored+bias_value;
    assert(separate==0.f && float(dot+double(bias_value))==1.f);
}
