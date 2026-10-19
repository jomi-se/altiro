#include "run-control.h"
#include "ggml-cpu.h"

thread_local AltiroRunControl * altiro_run_control = nullptr;
bool altiro_allow_vulkan() { return !altiro_run_control || altiro_run_control->gpu; }

static bool abort_graph(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

bool altiro_before_graph(ggml_backend_t backend, ggml_cgraph * graph, int role) {
    auto * control = altiro_run_control;
    if (!control) return true;
    if (control->cancelled->load()) return false;
    int unsupported = 0;
    const int nodes = ggml_graph_n_nodes(graph);
    for (int i = 0; i < nodes; ++i) {
        auto * node = ggml_graph_node(graph, i);
        if (!ggml_backend_supports_op(backend, node)) {
            ++unsupported;
            if (control->unsupported_op == "NONE") {
                control->unsupported_op = std::string(ggml_op_name(node->op)) + ":" + ggml_type_name(node->type) + ":" +
                    std::to_string(node->ne[0]) + "x" + std::to_string(node->ne[1]) + "x" +
                    std::to_string(node->ne[2]) + "x" + std::to_string(node->ne[3]);
            }
        }
    }
    control->unsupported |= unsupported != 0;
    if (control->event) control->event(role, ggml_backend_name(backend), nodes, unsupported, control->data);
    if (ggml_backend_is_cpu(backend)) {
        ggml_backend_cpu_set_abort_callback(backend, abort_graph, control->cancelled);
    }
    return unsupported == 0 && !control->cancelled->load();
}

void altiro_after_graph(int role, ggml_status status) {
    if (!altiro_run_control) return;
    if (status != GGML_STATUS_SUCCESS) altiro_run_control->compute_failed = true;
    else if (role == 1) ++altiro_run_control->encoder_graphs;
    else ++altiro_run_control->decoder_graphs;
}

#include "encoder-lab.h"
#include "ggml-backend-impl.h"
thread_local AltiroEncoderLab * altiro_encoder_lab = nullptr;
void altiro_lab_prepare_graph(ggml_backend_t backend, ggml_cgraph * graph) {
    if (!altiro_encoder_lab) return;
    if (altiro_lab_variant(2) && backend->iface.graph_optimize)
        backend->iface.graph_optimize(backend, graph);
    for (int i = 0; i < ggml_graph_n_nodes(graph); ++i) {
        auto * node = ggml_graph_node(graph, i);
        if (altiro_lab_f32() && node->op == GGML_OP_MUL_MAT)
            ggml_mul_mat_set_prec(node, GGML_PREC_F32);
        // Operation shapes describe the graph, never feature values.
        if (node->op == GGML_OP_MUL_MAT) {
            auto * w = node->src[0]; auto * x = node->src[1];
            const std::string key = std::string("MM:") + ggml_type_name(w->type) + ":" +
                std::to_string(w->ne[1]) + "x" + std::to_string(x->ne[1]) + "x" + std::to_string(w->ne[0]);
            if (altiro_encoder_lab->counts.size() < 80) ++altiro_encoder_lab->counts[key];
        }
    }
}
