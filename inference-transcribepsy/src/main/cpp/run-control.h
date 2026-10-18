#pragma once

#include "ggml-backend.h"
#include <atomic>
#include <string>

// Thread-local control belongs to the sole inference thread, never a global model.
struct AltiroRunControl {
    bool gpu = false;
    std::atomic<bool> * cancelled = nullptr;
    void (*event)(int role, const char * backend, int nodes, int unsupported, void * data) = nullptr;
    void * data = nullptr;
    int encoder_graphs = 0;
    int decoder_graphs = 0;
    bool unsupported = false;
    bool compute_failed = false;
    std::string unsupported_op = "NONE";
};

extern thread_local AltiroRunControl * altiro_run_control;
bool altiro_before_graph(ggml_backend_t backend, ggml_cgraph * graph, int role);
void altiro_after_graph(int role, ggml_status status);
bool altiro_allow_vulkan();
