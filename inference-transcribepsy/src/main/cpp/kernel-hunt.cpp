#include "encoder-lab.h"
#include "kernel-backend.h"
#include "run-control.h"
#include "ggml-cpu.h"
#include "ggml-alloc.h"
#include <algorithm>
#include <cmath>
#include <ctime>
#include <memory>
#include <sstream>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
double ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
double cpu_ms() {
    timespec ts{}; clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}
struct Resources {
    ggml_backend_t backend = nullptr;
    ggml_context * ctx = nullptr;
    ggml_backend_buffer_t buffer = nullptr;
    std::vector<ggml_backend_buffer_t> weight_buffers;
    ~Resources() {
        if (backend) ggml_backend_synchronize(backend);
        if (buffer) ggml_backend_buffer_free(buffer);
        for (auto weight : weight_buffers) ggml_backend_buffer_free(weight);
        if (ctx) ggml_free(ctx);
        if (backend) ggml_backend_free(backend);
    }
};

// Match the production encoder's optional CPU weight placement. Generic CPU
// buffers otherwise miss its repacked dot-product GEMM route.
bool repack_weights(Resources & resources, const std::vector<ggml_tensor *> & weights, ggml_tensor * op) {
    // The pinned extra-buffer support predicate accepts padded RHS strides,
    // but its packed GEMM assumes contiguous rows. Never measure wrong math.
    if (!ggml_is_contiguous(op->src[1])) return false;
    auto dev = ggml_backend_get_device(resources.backend);
    auto reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
    if (!reg) return false;
    auto extras = reinterpret_cast<ggml_backend_dev_get_extra_bufts_t>(ggml_backend_reg_get_proc_address(reg,"ggml_backend_dev_get_extra_bufts"));
    if (!extras) return false;
    for (auto types = extras(dev); types && *types; ++types) {
        auto probe = ggml_backend_buft_alloc_buffer(*types,0);
        if (!probe) continue;
        weights[0]->buffer = probe;
        const bool supported = ggml_backend_dev_supports_op(dev,op);
        weights[0]->buffer = nullptr;
        ggml_backend_buffer_free(probe);
        if (!supported) continue;
        for (auto * weight : weights) {
            auto buffer = ggml_backend_buft_alloc_buffer(*types,ggml_backend_buft_get_alloc_size(*types,weight) + ggml_backend_buft_get_alignment(*types));
            if (!buffer) throw std::bad_alloc();
            resources.weight_buffers.push_back(buffer);
            ggml_backend_buffer_set_usage(buffer,GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
            auto allocator = ggml_tallocr_new(buffer);
            if (ggml_tallocr_alloc(&allocator,weight) != GGML_STATUS_SUCCESS) throw std::bad_alloc();
        }
        return true;
    }
    return false;
}
// Same deterministic, nontrivial data for every variant. Independent streams
// change the working set; no model weights, features or audio are used here.
float datum(size_t i, uint32_t seed) {
    uint32_t v = static_cast<uint32_t>(i) + seed * 0x9e3779b9u;
    v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15; v *= 0x846ca68bu; v ^= v >> 16;
    return (static_cast<int>(v & 65535) - 32768) / 131072.0f;
}
size_t check_index(int sample, int m, int n) {
    if (sample == 0) return 0;
    if (sample == 1) return size_t(m) * n - 1;
    if (sample == 2) return m - 1;
    if (sample == 3) return size_t(n - 1) * m;
    return (size_t(sample) * 104729 + 17) % (size_t(m) * n);
}
template<class T> void map(std::ostringstream & out, const char * label, const std::map<std::string,T> & values) {
    out << ",\"" << label << "\":{";
    bool first = true;
    for (const auto & item : values) {
        if (!first) out << ','; first = false;
        // All keys originate in fixed shader names / integer dimensions.
        out << '"' << item.first << "\":" << item.second;
    }
    out << '}';
}
void clear_metrics(AltiroEncoderLab & lab) {
    lab.us.clear(); lab.counts.clear(); lab.shaders.clear(); lab.gpu_ns.clear();
    lab.gpu_shapes_ns.clear(); lab.gpu_shapes_calls.clear();
}
}

const char * altiro_kernel_hunt(bool gpu, int threads, int variant, bool quantized, int encoder_frames,
    std::atomic<bool> & cancelled, const std::function<void(const std::string &)> & emit) {
    Resources resources;
    auto & lab = *altiro_encoder_lab;
    lab.variant = variant;
    resources.backend = altiro_kernel_backend(gpu);
    if (!resources.backend) return "BACKEND_UNAVAILABLE";
    const std::string backend = ggml_backend_name(resources.backend);
    if ((gpu && backend.rfind("Vulkan", 0) != 0) || (!gpu && backend != "CPU")) return "BACKEND_MISMATCH";
    if (!gpu) ggml_backend_cpu_set_n_threads(resources.backend, threads);
    const int streams = variant == 26 ? 8 : 1;
    const int padded = variant == 25 ? 32 : 0;
    const auto source_type = quantized ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
    const auto storage_type = variant == 24 ? GGML_TYPE_F16 : source_type;
    const bool focused = altiro_focused_variant(variant);
    const int shapes[][2] = {{2560,640}, {640,2560}, {1920,640}, {640,640}}; // M,K
    const int widths[] = {32,64,65,128,129,139,256,384};
    struct Shape { int m,n,k; };
    std::vector<Shape> cases;
    if (focused) {
        if (encoder_frames < 1) return "GRAPH_UNSUPPORTED";
        cases = {{2560,encoder_frames,640},{640,encoder_frames,2560},{640,encoder_frames,1280},
            {640,2*encoder_frames-1,640},{64,63,64},{65,65,96},{640,129,2560},{640,257,2560}};
    } else for (const auto & shape : shapes) for (int n : widths) cases.push_back({shape[0],n,shape[1]});
    int sequence = 0;
    for (const auto & shape : cases) {
        if (cancelled.load()) return "NONE";
        const int m = shape.m, n = shape.n, k = shape.k;
        const bool full_reference = focused && size_t(m)*n <= 8192;
        const int checked = full_reference ? m*n : 64;
        const auto index_at = [&](int p) { return full_reference ? size_t(p) : check_index(p,m,n); };
        const auto setup_start = Clock::now();
        resources.ctx = ggml_init({4 * 1024 * 1024, nullptr, true});
        if (!resources.ctx) return "OUT_OF_MEMORY";
        auto * graph = ggml_new_graph_custom(resources.ctx, 256, false);
        struct Stream { ggml_tensor * w; ggml_tensor * base; ggml_tensor * x; ggml_tensor * y; ggml_tensor * bias; ggml_tensor * mm; };
        std::vector<Stream> tensors;
        for (int s = 0; s < streams; ++s) {
            auto * w = ggml_new_tensor_2d(resources.ctx, storage_type, k, m);
            auto * base = ggml_new_tensor_2d(resources.ctx, GGML_TYPE_F32, k + padded, n);
            auto * x = padded ? ggml_view_2d(resources.ctx, base, k, n, base->nb[1], 0) : base;
            auto * y = ggml_mul_mat(resources.ctx, w, x);
            if (altiro_lab_f32()) ggml_mul_mat_set_prec(y, GGML_PREC_F32);
            auto * mm = y;
            auto * bias = focused ? ggml_new_tensor_1d(resources.ctx,GGML_TYPE_F32,m) : nullptr;
            if (bias) y = ggml_silu(resources.ctx,ggml_add(resources.ctx,y,bias));
            ggml_build_forward_expand(graph, y);
            tensors.push_back({w,base,x,y,bias,mm});
        }
        std::vector<ggml_tensor *> weight_tensors;
        for (const auto & tensor : tensors) weight_tensors.push_back(tensor.w);
        const bool repacked = !gpu && ggml_is_quantized(storage_type) && repack_weights(resources,weight_tensors,tensors[0].mm);
        resources.buffer = ggml_backend_alloc_ctx_tensors(resources.ctx, resources.backend);
        if (!resources.buffer) return "OUT_OF_MEMORY";
        size_t resident_bytes = ggml_backend_buffer_get_size(resources.buffer);
        for (auto buffer : resources.weight_buffers) resident_bytes += ggml_backend_buffer_get_size(buffer);
        // Reconstruct the exact stored weights before the CPU reference. Q8
        // pre-dequantization starts from those Q8 values, not original floats.
        std::vector<std::vector<float>> weights(streams), activations(streams);
        for (int s = 0; s < streams; ++s) {
            if (cancelled.load()) return "NONE";
            auto & w = weights[s]; w.resize(size_t(m) * k);
            for (size_t i = 0; i < w.size(); ++i) w[i] = datum(i, 11 + s);
            std::vector<uint8_t> packed(ggml_row_size(source_type,k) * m);
            ggml_quantize_chunk(source_type,w.data(),packed.data(),0,m,k,nullptr);
            ggml_get_type_traits(source_type)->to_float(packed.data(),w.data(),w.size());
            if (storage_type != source_type) {
                packed.resize(ggml_row_size(storage_type,k) * m);
                ggml_quantize_chunk(storage_type,w.data(),packed.data(),0,m,k,nullptr);
                // Keep the source-Q8 reference, so additional FP16 rounding is
                // included in the measured error rather than silently hidden.
            }
            ggml_backend_tensor_set(tensors[s].w,packed.data(),0,packed.size());
            auto & x = activations[s]; x.resize(size_t(k + padded) * n);
            for (size_t i = 0; i < x.size(); ++i) x[i] = 0;
            for (int col = 0; col < n; ++col) for (int row = 0; row < k; ++row)
                x[size_t(col) * (k + padded) + row] = datum(size_t(col) * k + row,31 + s);
            ggml_backend_tensor_set(tensors[s].base,x.data(),0,x.size() * sizeof(float));
            if (focused) {
                std::vector<float> bias(m);
                for (int row=0; row<m; ++row) bias[row] = datum(row,57+s);
                ggml_backend_tensor_set(tensors[s].bias,bias.data(),0,bias.size()*sizeof(float));
            }
        }
        ggml_backend_synchronize(resources.backend);
        const double setup_ms = ms(setup_start);
        if (!altiro_before_graph(resources.backend,graph,1)) return cancelled.load() ? "NONE" : "GRAPH_UNSUPPORTED";
        // Fixed 64 well-spread output cells in EACH stream, computed in double.
        // Entire output is checked for finiteness after every measured call.
        std::vector<double> reference;
        for (int s = 0; s < streams; ++s) for (int p = 0; p < checked; ++p) {
            const size_t index = index_at(p);
            const int row = index % m, col = index / m;
            double sum = 0;
            for (int j = 0; j < k; ++j) sum += double(weights[s][size_t(row) * k + j]) * activations[s][size_t(col) * (k + padded) + j];
            if (focused) {
                const float stored = static_cast<float>(sum);
                const float biased = stored + datum(row,57+s);
                const float activated = biased / (1.0f + std::exp(-biased));
                sum = activated;
            }
            reference.push_back(sum);
        }
        // Leave CPU reference generation and all transfers outside compute.
        // Independent unfused GPU control on the exact same stored tensors.
        // No graph mutation or buffer reallocation; only backend fusion is off.
        std::vector<std::vector<float>> unfused;
        if (focused && gpu && altiro_lab_fusion()) {
            lab.variant = 40;
            const auto status = ggml_backend_graph_compute(resources.backend,graph);
            ggml_backend_synchronize(resources.backend);
            lab.variant = variant;
            if (status != GGML_STATUS_SUCCESS) return "KERNEL_COMPUTE_FAILED";
            for (const auto & tensor : tensors) {
                unfused.emplace_back(size_t(m)*n);
                ggml_backend_tensor_get(tensor.y,unfused.back().data(),0,unfused.back().size()*sizeof(float));
                for (float v : unfused.back()) if (!std::isfinite(v)) return "NONFINITE_OR_SHAPE";
            }
        }
        std::vector<double> walls, cpus, reads, boundaries, enqueues, waits;
        double unfused_rmse=0, unfused_abs=0;
        bool finite = true;
        double worst_rmse = 0, worst_abs = 0;
        clear_metrics(lab);
        for (int repeat = 0; repeat < 6; ++repeat) {
            if (cancelled.load()) return "NONE";
            lab.boundary_available = false;
            const double old_enqueue = lab.us["graph_enqueue_us"], old_wait = lab.us["final_fence_wait_us"];
            lab.measuring = true;
            const auto start = Clock::now(); const double cpu_start = cpu_ms();
            const auto status = ggml_backend_graph_compute(resources.backend,graph);
            ggml_backend_synchronize(resources.backend);
            walls.push_back(ms(start)); cpus.push_back(cpu_ms() - cpu_start);
            lab.measuring = false;
            boundaries.push_back(lab.boundary_available ? double(lab.boundary_gpu_ns) : -1);
            enqueues.push_back(lab.us["graph_enqueue_us"]-old_enqueue);
            waits.push_back(lab.us["final_fence_wait_us"]-old_wait);
            if (status != GGML_STATUS_SUCCESS) return "KERNEL_COMPUTE_FAILED";
            if (cancelled.load()) return "NONE";
            const auto read_start = Clock::now();
            double sq = 0, ref_sq = 0, max_abs = 0;
            for (int s = 0; s < streams; ++s) {
                std::vector<float> output(size_t(m) * n);
                ggml_backend_tensor_get(tensors[s].y,output.data(),0,output.size() * sizeof(float));
                for (float v : output) finite &= std::isfinite(v);
                if (!unfused.empty()) {
                    double usq=0, ursq=0;
                    for (size_t p=0; p<output.size(); ++p) {
                        if (!std::isfinite(output[p])) continue;
                        const double d = double(output[p])-unfused[s][p];
                        usq += d*d; ursq += double(unfused[s][p])*unfused[s][p];
                        unfused_abs = std::max(unfused_abs,std::abs(d));
                    }
                    unfused_rmse = std::max(unfused_rmse,std::sqrt(usq/std::max(ursq,1e-30)));
                }
                for (int p = 0; p < checked; ++p) {
                    const size_t index = index_at(p);
                    const double expected = reference[s * checked + p], actual = output[index];
                    if (!std::isfinite(actual)) continue;
                    const double delta = actual - expected;
                    sq += delta * delta; ref_sq += expected * expected;
                    max_abs = std::max(max_abs,std::abs(delta));
                }
            }
            reads.push_back(ms(read_start));
            worst_rmse = std::max(worst_rmse,std::sqrt(sq / std::max(ref_sq,1e-30)));
            worst_abs = std::max(worst_abs,max_abs);
        }
        std::ostringstream out; out.precision(9);
        out << "{\"lab\":true,\"event\":\"KERNEL\",\"sequence\":" << sequence++
            << ",\"m\":" << m << ",\"n\":" << n << ",\"k\":" << k << ",\"streams\":" << streams
            << ",\"source_type\":\"" << ggml_type_name(source_type) << "\",\"storage_type\":\"" << ggml_type_name(storage_type)
            << "\",\"backend\":\"" << backend << "\",\"f32_accumulation\":" << (altiro_lab_f32() ? "true" : "false")
            << ",\"cpu_repacked\":" << (repacked ? "true" : "false")
            << ",\"activation_row_padding\":" << padded << ",\"resident_bytes\":" << resident_bytes
            << ",\"setup_ms\":" << setup_ms << ",\"reference_cells\":" << reference.size()
            << ",\"finite\":" << (finite ? "true" : "false") << ",\"relative_rmse\":" << (finite ? worst_rmse : -1)
            << ",\"max_abs_error\":" << (finite ? worst_abs : -1);
        if (focused) {
            out << ",\"focused\":true,\"encoder_frames\":" << encoder_frames
                << ",\"shape_family\":\"" << (sequence <= 4 ? "recording" : "boundary") << "\""
                << ",\"reference_mode\":\"double_dot_f32_bias_silu\",\"full_reference\":" << (full_reference ? "true" : "false")
                << ",\"bias_requested\":true,\"activation_requested\":\"silu\",\"fusion_requested\":" << altiro_lab_fusion()
                << ",\"split_requested\":" << altiro_split_factor(variant,m,k,false)
                << ",\"unfused_compared\":" << (!unfused.empty() ? "true" : "false")
                << ",\"unfused_relative_rmse\":" << (unfused.empty() ? "null" : std::to_string(finite ? unfused_rmse : -1))
                << ",\"unfused_max_abs_error\":" << (unfused.empty() ? "null" : std::to_string(finite ? unfused_abs : -1));
        }
        const auto array = [&](const char * name,const std::vector<double> & values) {
            out << ",\"" << name << "\":[";
            for (size_t i = 0; i < values.size(); ++i) { if (i) out << ','; if (values[i] < 0) out << "null"; else out << values[i]; }
            out << ']';
        };
        array("wall_ms",walls); array("process_cpu_ms",cpus); array("readback_check_ms",reads);
        if (focused) {
            array("boundary_gpu_ns",boundaries); array("enqueue_us",enqueues); array("fence_wait_us",waits);
            map(out,"us",lab.us);
        }
        map(out,"counts",lab.counts); map(out,"shaders",lab.shaders);
        out << '}'; emit(out.str());
        if (!finite) return "NONFINITE_OR_SHAPE";
        ggml_backend_buffer_free(resources.buffer); resources.buffer = nullptr;
        for (auto buffer : resources.weight_buffers) ggml_backend_buffer_free(buffer);
        resources.weight_buffers.clear();
        ggml_free(resources.ctx); resources.ctx = nullptr;
    }
    emit("{\"lab\":true,\"event\":\"FINISHED\"}");
    return nullptr;
}
