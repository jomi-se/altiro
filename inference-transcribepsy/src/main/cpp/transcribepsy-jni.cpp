#include <jni.h>
#include "gguf.h"
#include "parakeet/engine.h"
#include "run-control.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <vector>
#ifdef ALTIRO_VULKAN
#include <vulkan/vulkan.h>
#endif
#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

namespace {
struct Operation {
    std::atomic<bool> cancelled{false};
    std::mutex mutex;
    parakeet::Engine * engine = nullptr;
    void cancel() {
        cancelled.store(true);
        std::lock_guard<std::mutex> guard(mutex);
        if (engine) engine->cancel();
    }
};
struct Failure { const char * code; };
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Operation>> operations;
std::atomic<jlong> next_id{1};

std::shared_ptr<Operation> lookup(jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto found = operations.find(id);
    return found == operations.end() ? nullptr : found->second;
}
struct UtfChars {
    JNIEnv * env; jstring value; const char * data;
    UtfChars(JNIEnv * env, jstring value): env(env), value(value), data(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (data) env->ReleaseStringUTFChars(value, data); }
};

struct Progress {
    JNIEnv * env;
    jobject receiver;
    Operation * operation;
    jmethodID phase;
    jmethodID runtime;
    AltiroRunControl control;
    std::string backend = "UNINITIALIZED";
    std::string encoder = "UNINITIALIZED";
    std::string decoder = "UNINITIALIZED";
    bool fp16 = false, dotprod = false, i8mm = false;
    int graph_checks = 0, unsupported = 0;
    int current_phase = 0;
    std::string gpu_name, vulkan_version;
    uint32_t driver = 0;
    int devices = -1, vulkan_result = 0;
    bool storage16 = false, shader_fp16 = false, shader_int8 = false;
    bool timestamps = false;
    double timestamp_period_ns = 0;
    int timestamp_bits_min = -1;
    uint32_t subgroup_size = 0, shared_memory_bytes = 0, max_workgroup_invocations = 0;

    void check_java() {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            operation->cancel();
        }
    }
    void stage(int value) {
        current_phase = value;
        env->CallVoidMethod(receiver, phase, value);
        check_java();
    }
    void report(const char * status, const char * failure = "NONE", const parakeet::EngineResult * result = nullptr) {
        std::ostringstream json;
        json << "{\"status\":\"" << status << "\",\"failure\":\"" << failure
             << "\",\"engine\":\"TRANSCRIBEPSY\",\"native_backend\":\"" << backend
             << "\",\"encoder_backend\":\"" << encoder << "\",\"decoder_backend\":\"" << decoder
             << "\",\"cpu_fp16\":" << (fp16 ? "true" : "false")
             << ",\"cpu_dotprod\":" << (dotprod ? "true" : "false")
             << ",\"cpu_i8mm\":" << (i8mm ? "true" : "false")
             << ",\"gpu_active\":" << (backend.rfind("Vulkan", 0) == 0 ? "true" : "false")
             << ",\"graph_checks\":" << graph_checks << ",\"unsupported_ops\":" << unsupported
             << ",\"unsupported_op\":\"" << control.unsupported_op << "\""
             << ",\"encode_calls\":" << control.encoder_graphs
             << ",\"decode_calls\":" << control.decoder_graphs;
        if (result) {
            json << ",\"preprocess_ms\":" << static_cast<int64_t>(result->preprocess_ms)
                 << ",\"encode_ms\":" << static_cast<int64_t>(result->encoder_ms)
                 << ",\"decode_ms\":" << static_cast<int64_t>(result->decode_ms)
                 << ",\"total_ms\":" << static_cast<int64_t>(result->total_ms);
        }
        if (devices >= 0) {
            json << ",\"gpu\":\"" << gpu_name << "\",\"vulkan\":\"" << vulkan_version
                 << "\",\"driver\":" << driver << ",\"devices\":" << devices
                 << ",\"storage16\":" << (storage16 ? "true" : "false")
                 << ",\"float16\":" << (shader_fp16 ? "true" : "false")
                 << ",\"int8\":" << (shader_int8 ? "true" : "false");
        }
        if (vulkan_result) json << ",\"vk_result\":" << vulkan_result;
        json << '}';
        auto value = env->NewStringUTF(json.str().c_str());
        if (value) { env->CallVoidMethod(receiver, runtime, value); env->DeleteLocalRef(value); }
        check_java();
    }
};

void probe_vulkan(Progress & progress) {
#ifdef ALTIRO_VULKAN
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Altiro TranscribePsy probe";
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    struct Instance { VkInstance handle = VK_NULL_HANDLE; ~Instance() { if (handle) vkDestroyInstance(handle, nullptr); } } instance;
    progress.vulkan_result = vkCreateInstance(&info, nullptr, &instance.handle);
    if (progress.vulkan_result != VK_SUCCESS) throw Failure{"VULKAN_UNAVAILABLE"};
    uint32_t count = 0;
    progress.vulkan_result = vkEnumeratePhysicalDevices(instance.handle, &count, nullptr);
    if (progress.vulkan_result != VK_SUCCESS || count == 0 || count > 64) throw Failure{"NO_GPU"};
    std::vector<VkPhysicalDevice> devices(count);
    progress.vulkan_result = vkEnumeratePhysicalDevices(instance.handle, &count, devices.data());
    if (progress.vulkan_result != VK_SUCCESS) throw Failure{"VULKAN_UNAVAILABLE"};
    progress.devices = static_cast<int>(count);
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(devices[0], &properties);
    VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    VkPhysicalDeviceProperties2 properties2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    properties2.pNext = &subgroup;
    vkGetPhysicalDeviceProperties2(devices[0], &properties2);
    progress.subgroup_size = subgroup.subgroupSize;
    progress.shared_memory_bytes = properties.limits.maxComputeSharedMemorySize;
    progress.max_workgroup_invocations = properties.limits.maxComputeWorkGroupInvocations;
    uint32_t queue_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &queue_count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(queue_count);
    vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &queue_count, queues.data());
    // Conservative: every compute queue must support full-width timestamps.
    // This avoids attributing an unsupported queue or mishandling wraparound.
    progress.timestamp_period_ns = properties.limits.timestampPeriod;
    progress.timestamps = properties.limits.timestampPeriod > 0 && queue_count > 0;
    for (const auto & q : queues) if (q.queueFlags & VK_QUEUE_COMPUTE_BIT) {
        progress.timestamp_bits_min = progress.timestamp_bits_min < 0 ? static_cast<int>(q.timestampValidBits) : std::min(progress.timestamp_bits_min, static_cast<int>(q.timestampValidBits));
        progress.timestamps &= q.timestampValidBits == 64;
    }
    progress.timestamps &= progress.timestamp_bits_min >= 0;
    for (const char c : std::string(properties.deviceName)) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            std::strchr(" ._()+:-", c)) progress.gpu_name += c;
        if (progress.gpu_name.size() == 96) break;
    }
    if (progress.gpu_name.empty()) progress.gpu_name = "Unknown GPU";
    progress.driver = properties.driverVersion;
    progress.vulkan_version = std::to_string(VK_API_VERSION_MAJOR(properties.apiVersion)) + "." +
        std::to_string(VK_API_VERSION_MINOR(properties.apiVersion)) + "." + std::to_string(VK_API_VERSION_PATCH(properties.apiVersion));
    VkPhysicalDevice16BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    VkPhysicalDeviceShaderFloat16Int8Features numeric{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    features.pNext = &storage; storage.pNext = &numeric;
    vkGetPhysicalDeviceFeatures2(devices[0], &features);
    progress.storage16 = storage.storageBuffer16BitAccess;
    progress.shader_fp16 = numeric.shaderFloat16;
    progress.shader_int8 = numeric.shaderInt8;
    progress.report("PROBING");
    if (!progress.storage16) throw Failure{"STORAGE_16_UNSUPPORTED"};
#else
    (void)progress;
    throw Failure{"VULKAN_UNAVAILABLE"};
#endif
}

void graph_event(int role, const char * backend, int, int unsupported, void * data) {
    auto * progress = static_cast<Progress *>(data);
    if (role == 1) progress->encoder = backend;
    else progress->decoder = backend;
    ++progress->graph_checks;
    progress->unsupported += unsupported;
    // Publish role transitions and failures, not thousands of decoder updates.
    if (role == 1 || progress->graph_checks <= 2 || unsupported) progress->report("READY");
}

struct Exposure {
    Operation & op;
    Exposure(Operation & op, parakeet::Engine * engine): op(op) {
        std::lock_guard<std::mutex> guard(op.mutex); op.engine = engine;
    }
    ~Exposure() { std::lock_guard<std::mutex> guard(op.mutex); op.engine = nullptr; }
};

struct CancelPump {
    Operation & op;
    std::atomic<bool> done{false};
    std::thread thread;
    explicit CancelPump(Operation & op): op(op), thread([this] {
        while (!done.load()) {
            if (this->op.cancelled.load()) this->op.cancel();
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
    }) {}
    ~CancelPump() { done.store(true); thread.join(); }
};

uint32_t le32(const unsigned char * p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
uint16_t le16(const unsigned char * p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }

std::vector<float> read_audio(const char * path, const Operation & operation) {
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream) throw Failure{"AUDIO_READ_FAILED"};
    const auto size = static_cast<std::streamoff>(stream.tellg());
    if (size <= 44 || size > 44 + 16000 * 300 * 2) throw Failure{"AUDIO_READ_FAILED"};
    stream.seekg(0);
    unsigned char header[44];
    stream.read(reinterpret_cast<char *>(header), 44);
    if (!stream || std::memcmp(header, "RIFF", 4) || std::memcmp(header + 8, "WAVEfmt ", 8) ||
        le32(header + 16) != 16 || le16(header + 20) != 1 || le16(header + 22) != 1 ||
        le32(header + 24) != 16000 || le32(header + 28) != 32000 || le16(header + 32) != 2 ||
        le16(header + 34) != 16 || std::memcmp(header + 36, "data", 4) ||
        le32(header + 40) != size - 44 || le32(header + 4) != size - 8 || (size - 44) % 2) throw Failure{"AUDIO_READ_FAILED"};
    std::vector<float> audio(static_cast<size_t>(size - 44) / 2);
    unsigned char buffer[8192];
    size_t frame = 0;
    while (frame < audio.size()) {
        if (operation.cancelled.load()) return {};
        const size_t frames = std::min(sizeof(buffer) / 2, audio.size() - frame);
        stream.read(reinterpret_cast<char *>(buffer), static_cast<std::streamsize>(frames * 2));
        if (!stream) throw Failure{"AUDIO_READ_FAILED"};
        for (size_t i = 0; i < frames; ++i) audio[frame++] = static_cast<int16_t>(le16(buffer + i * 2)) / 32768.0f;
    }
    return audio;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_altiro_transcribepsy_NativeTranscribePsy_create(JNIEnv *, jobject) {
    auto id = next_id.fetch_add(1);
    std::lock_guard<std::mutex> guard(registry_mutex);
    operations[id] = std::make_shared<Operation>();
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_org_altiro_transcribepsy_NativeTranscribePsy_cancel(JNIEnv *, jobject, jlong id) {
    auto op = lookup(id); if (op) op->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_org_altiro_transcribepsy_NativeTranscribePsy_release(JNIEnv *, jobject, jlong id) {
    auto op = lookup(id); if (op) op->cancel();
    std::lock_guard<std::mutex> guard(registry_mutex);
    operations.erase(id);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_altiro_transcribepsy_NativeTranscribePsy_recognize(JNIEnv * env, jobject, jlong id,
    jstring model_path, jstring wav_path, jboolean gpu, jobject receiver) {
    auto op = lookup(id);
    if (!op || !model_path || !wav_path || !receiver) return nullptr;
    auto cls = env->GetObjectClass(receiver);
    Progress progress{env, receiver, op.get(), env->GetMethodID(cls, "onPhase", "(I)V"),
        env->GetMethodID(cls, "onRuntime", "(Ljava/lang/String;)V")};
    env->DeleteLocalRef(cls);
    if (!progress.phase || !progress.runtime) return nullptr;
    progress.control.gpu = gpu;
    progress.control.cancelled = &op->cancelled;
    progress.control.event = graph_event;
    progress.control.data = &progress;
    altiro_run_control = &progress.control;
    struct Reset { ~Reset() { altiro_run_control = nullptr; } } reset;
    try {
#if defined(__aarch64__)
        const auto hw = getauxval(AT_HWCAP);
        progress.fp16 = (hw & HWCAP_FPHP) && (hw & HWCAP_ASIMDHP);
        progress.dotprod = (hw & HWCAP_ASIMDDP) != 0;
        progress.i8mm = (getauxval(AT_HWCAP2) & HWCAP2_I8MM) != 0;
        progress.report("STARTING");
        if (!progress.fp16 || !progress.dotprod) throw Failure{"CPU_UNSUPPORTED"};
#endif
        if (op->cancelled.load()) throw Failure{"NONE"};
        UtfChars model(env, model_path), wav(env, wav_path);
        if (!model.data || !wav.data) throw Failure{"AUDIO_READ_FAILED"};
        progress.stage(1);
        const auto audio = read_audio(wav.data, *op);
        if (op->cancelled.load()) throw Failure{"NONE"};
        if (gpu) { progress.stage(7); probe_vulkan(progress); }
        parakeet::EngineOptions options;
        options.model_gguf_path = model.data;
        options.n_threads = 4;
        options.n_gpu_layers = gpu ? 1 : 0;
        options.long_form_window_frames = 375; // 30 s at 80 ms/frame; short clips use actual length.
        options.long_form_context_frames = 47; // ~25% shared context; trimmed before decoding.
        options.verbose = false;
        progress.stage(2);
        progress.report("INITIALIZING");
        auto engine = std::make_unique<parakeet::Engine>(options);
        Exposure exposure(*op, engine.get());
        progress.backend = engine->backend_name();
        if (gpu && progress.backend.rfind("Vulkan", 0) != 0) throw Failure{"GPU_INIT_FAILED"};
        if (!gpu && engine->backend_device() != parakeet::BackendDevice::CPU) throw Failure{"GPU_INIT_FAILED"};
        if (!gpu) progress.decoder = "CPU";
        progress.report("READY");
        if (op->cancelled.load()) throw Failure{"NONE"};
        CancelPump pump(*op);
        progress.stage(3);
        auto result = engine->transcribe_samples(audio.data(), static_cast<int>(audio.size()), 16000);
        if (op->cancelled.load()) throw Failure{"NONE"};
        if (progress.control.unsupported) throw Failure{"GRAPH_UNSUPPORTED"};
        if (progress.control.compute_failed || progress.control.encoder_graphs == 0 ||
            (gpu && progress.control.decoder_graphs == 0)) throw Failure{"INFERENCE_FAILED"};
        if (result.text.size() > 1'048'576) throw Failure{"INFERENCE_FAILED"};
        progress.stage(4);
        progress.report("FINISHED", "NONE", &result);
        auto bytes = env->NewByteArray(static_cast<jsize>(result.text.size()));
        if (!bytes) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            throw Failure{"OUT_OF_MEMORY"};
        }
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.text.size()),
            reinterpret_cast<const jbyte *>(result.text.data()));
        progress.stage(5);
        return bytes;
    } catch (const Failure & failure) {
        if (!op->cancelled.load()) progress.stage(6);
        progress.report(op->cancelled.load() ? "CANCELLED" : "FAILED", failure.code);
    } catch (const std::bad_alloc &) {
        progress.stage(6); progress.report("FAILED", "OUT_OF_MEMORY");
    } catch (...) {
        const bool loading = progress.current_phase == 2;
        if (!op->cancelled.load()) progress.stage(6);
        progress.report(op->cancelled.load() ? "CANCELLED" : "FAILED",
            progress.control.unsupported ? "GRAPH_UNSUPPORTED" : loading ? "MODEL_LOAD_FAILED" : "INFERENCE_FAILED");
    }
    progress.stage(5);
    return nullptr;
}

// This entry point is intentionally separate from everyday recognition. A worker
// retains one model only for this bounded case, and returns no transcript/features.
#include "encoder-lab.h"
#include "parakeet_ctc.h"
#include "mel_preprocess.h"
#include <cmath>
#include <cctype>
#include <cstdlib>
#include <ctime>

namespace {
double lab_ms(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-start).count();
}
double process_ms() {
    timespec ts{};
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}
void lab_emit(Progress & progress, const std::string & report) {
    auto value = progress.env->NewStringUTF(report.c_str());
    if (!value) throw Failure{"OUT_OF_MEMORY"};
    progress.env->CallVoidMethod(progress.receiver, progress.runtime, value);
    progress.env->DeleteLocalRef(value);
    progress.check_java();
}
// Only fixed native shader identifiers are admitted into the structured report.
std::string lab_key(const std::string & key) {
    std::string safe;
    for (char c : key) if (std::isalnum(static_cast<unsigned char>(c)) || std::strchr("_:.-x", c)) {
        safe += c; if (safe.size() == 120) break;
    }
    return safe;
}
template<typename T> void lab_map(std::ostringstream & out, const char * key, const std::map<std::string, T> & values) {
    out << ",\"" << key << "\":{";
    int n = 0;
    for (const auto & item : values) {
        if (n++) out << ',';
        out << '"' << lab_key(item.first) << "\":" << item.second;
    }
    out << '}';
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_altiro_transcribepsy_NativeTranscribePsy_benchmark(JNIEnv * env, jobject, jlong id,
    jstring model_path, jstring wav_path, jboolean gpu, jint threads, jint variant,
    jstring cache_path, jobject receiver) {
    auto op = lookup(id);
    if (!op || !model_path || !wav_path || !receiver || threads < 1 || threads > 8 ||
        !altiro_supported_variant(variant)) return false;
    auto cls = env->GetObjectClass(receiver);
    Progress progress{env, receiver, op.get(), env->GetMethodID(cls, "onPhase", "(I)V"),
        env->GetMethodID(cls, "onRuntime", "(Ljava/lang/String;)V")};
    env->DeleteLocalRef(cls);
    if (!progress.phase || !progress.runtime) return false;
    progress.control.gpu = gpu;
    progress.control.cancelled = &op->cancelled;
    altiro_run_control = &progress.control;
    AltiroEncoderLab metrics;
    altiro_encoder_lab = &metrics;
    struct ResetLab { ~ResetLab() { altiro_encoder_lab = nullptr; altiro_run_control = nullptr; } } reset;
    try {
#if defined(__aarch64__)
        const auto hw = getauxval(AT_HWCAP);
        if (!(hw & HWCAP_FPHP) || !(hw & HWCAP_ASIMDHP) || !(hw & HWCAP_ASIMDDP)) throw Failure{"CPU_UNSUPPORTED"};
#endif
        if (op->cancelled.load()) throw Failure{"NONE"};
        UtfChars model(env, model_path), wav(env, wav_path), cache(env, cache_path);
        if (!model.data || !wav.data || !cache.data) throw Failure{"INVALID_ARGUMENT"};
        auto audio = read_audio(wav.data, *op);
        if (audio.empty() || audio.size() > 30 * 16000) throw Failure{"LAB_AUDIO_LIMIT"};
        if (variant == 10 && audio.size() > 5 * 16000) audio.resize(5 * 16000);
        if (gpu) { progress.stage(7); probe_vulkan(progress); }
        std::ostringstream device;
        device << "{\"lab\":true,\"event\":\"DEVICE\",\"gpu\":\"" << progress.gpu_name
               << "\",\"vulkan\":\"" << progress.vulkan_version << "\",\"driver\":" << progress.driver
               << ",\"timestamp_period_ns\":" << progress.timestamp_period_ns << ",\"timestamp_bits_min\":" << progress.timestamp_bits_min
               << ",\"subgroup_size\":" << progress.subgroup_size << ",\"shared_memory_bytes\":" << progress.shared_memory_bytes
               << ",\"max_workgroup_invocations\":" << progress.max_workgroup_invocations
               << ",\"timestamps_supported\":" << (progress.timestamps ? "true" : "false") << '}';
        lab_emit(progress, device.str());
        if (variant == 7) setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
        if (variant == 8 || variant == 9 || variant == 14 || variant >= 20) setenv("GGML_VK_PIPELINE_CACHE_DIR", cache.data, 1);
        // Profile uses upstream per-operation timestamp queries with serialization.
        // Queue support is checked before enabling it (no invented zero timings).
        if (variant == 9) {
#ifdef ALTIRO_VULKAN
            if (!progress.timestamps) throw Failure{"TIMESTAMPS_UNAVAILABLE"};
#endif
            setenv("GGML_VK_PERF_LOGGER", "1", 1);
        }
        if (altiro_synthetic_variant(variant)) {
            progress.stage(3);
            const std::string path(model.data);
            // Model identity is pinned and verified by the parent. The file is
            // not loaded: this mode isolates synthetic kernels of that format.
            const auto filename = path.substr(path.find_last_of("/\\") + 1);
            if (filename != "transcribepsy-250m-eu-sub.gguf" && filename != "transcribepsy-250m-eu-sub-f16.gguf")
                throw Failure{"MODEL_IDENTITY_INVALID"};
            const bool quantized = filename == "transcribepsy-250m-eu-sub.gguf";
            int encoder_frames = 0;
            if (altiro_focused_variant(variant)) {
                std::unique_ptr<gguf_context, decltype(&gguf_free)> metadata(
                    gguf_init_from_file(model.data, {true, nullptr}), gguf_free);
                if (!metadata) throw Failure{"MODEL_IDENTITY_INVALID"};
                const auto hop_key = gguf_find_key(metadata.get(), "parakeet.preproc.hop_length");
                const auto causal_key = gguf_find_key(metadata.get(), "parakeet.encoder.causal_downsampling");
                if (hop_key >= 0 && gguf_get_kv_type(metadata.get(), hop_key) != GGUF_TYPE_UINT32)
                    throw Failure{"MODEL_IDENTITY_INVALID"};
                if (causal_key >= 0 && gguf_get_kv_type(metadata.get(), causal_key) != GGUF_TYPE_BOOL)
                    throw Failure{"MODEL_IDENTITY_INVALID"};
                const uint32_t hop = hop_key < 0 ? 160 : gguf_get_val_u32(metadata.get(), hop_key);
                const bool causal = causal_key >= 0 && gguf_get_val_bool(metadata.get(), causal_key);
                encoder_frames = altiro_encoder_frames(audio.size(), hop, causal);
                if (encoder_frames < 1 || encoder_frames > 1000) throw Failure{"MODEL_IDENTITY_INVALID"};
            }
            const char * failure = altiro_kernel_hunt(gpu,threads,variant,quantized,encoder_frames,op->cancelled,
                [&](const std::string & event) { lab_emit(progress,event); });
            if (failure) throw Failure{failure};
            return true;
        }
        progress.stage(2);
        lab_emit(progress, "{\"lab\":true,\"event\":\"PHASE\",\"phase\":\"REFERENCE\"}");
        const auto ref_start = std::chrono::steady_clock::now();
        std::vector<float> mel;
        std::vector<float> reference;
        int frames = 0;
        // Release all reference weights before loading the candidate. Features
        // stay only in this worker's RAM and never cross Binder or enter logs.
        {
            parakeet::ParakeetCtcModel baseline;
            if (parakeet::load_from_gguf(model.data, baseline, 4, 0, false)) throw Failure{"MODEL_LOAD_FAILED"};
            if (parakeet::compute_log_mel(audio.data(), static_cast<int>(audio.size()), baseline.mel_cfg, mel, frames)) throw Failure{"PREPROCESS_FAILED"};
            parakeet::EncoderOutputs out;
            if (parakeet::run_encoder(baseline, mel.data(), frames, baseline.mel_cfg.n_mels, out, -1, false)) throw Failure{"REFERENCE_FAILED"};
            reference = std::move(out.encoder_out);
        }
        if (op->cancelled.load()) throw Failure{"NONE"};
        const double reference_ms = lab_ms(ref_start);
        metrics.variant = variant;
        lab_emit(progress, "{\"lab\":true,\"event\":\"PHASE\",\"phase\":\"CANDIDATE_LOAD\"}");
        const auto load_start = std::chrono::steady_clock::now();
        parakeet::ParakeetCtcModel candidate;
        if (parakeet::load_from_gguf(model.data, candidate, threads, gpu ? 1 : 0, false)) throw Failure{"MODEL_LOAD_FAILED"};
        const double load_ms = lab_ms(load_start);
        const auto backend = parakeet::model_active_backend_name(candidate);
        if ((gpu && backend.rfind("Vulkan", 0) != 0) || (!gpu && backend != "CPU")) throw Failure{"BACKEND_MISMATCH"};
        for (int repeat = 0; repeat < 4; ++repeat) {
            if (op->cancelled.load()) throw Failure{"NONE"};
            metrics.us.clear(); metrics.counts.clear(); metrics.shaders.clear(); metrics.gpu_ns.clear();
            metrics.gpu_shapes_ns.clear(); metrics.gpu_shapes_calls.clear();
            metrics.measuring = true;
            progress.stage(3);
            lab_emit(progress, "{\"lab\":true,\"event\":\"BEGIN\",\"repeat\":" + std::to_string(repeat) + "}");
            const auto wall_start = std::chrono::steady_clock::now();
            const double cpu_start = process_ms();
            parakeet::EncoderOutputs out;
            const int rc = parakeet::run_encoder(candidate, mel.data(), frames, candidate.mel_cfg.n_mels, out, -1, false);
            const double wall_ms = lab_ms(wall_start), cpu_ms = process_ms() - cpu_start;
            metrics.measuring = false;
            if (op->cancelled.load()) throw Failure{"NONE"};
            if (rc || progress.control.unsupported || progress.control.compute_failed) throw Failure{progress.control.unsupported ? "GRAPH_UNSUPPORTED" : "ENCODER_FAILED"};
            bool finite = !out.encoder_out.empty() && out.encoder_out.size() == reference.size();
            double sq = 0, ref_sq = 0, max_abs = 0;
            if (finite) for (size_t i=0; i<reference.size(); ++i) {
                const double a = out.encoder_out[i], b = reference[i];
                if (!std::isfinite(a) || !std::isfinite(b)) { finite = false; break; }
                sq += (a-b)*(a-b); ref_sq += b*b; max_abs = std::max(max_abs, std::abs(a-b));
            }
            std::ostringstream json;
            json.precision(9);
            json << "{\"lab\":true,\"event\":\"SAMPLE\",\"repeat\":" << repeat
                 << ",\"wall_ms\":" << wall_ms << ",\"process_cpu_ms\":" << cpu_ms
                 << ",\"reference_setup_ms\":" << reference_ms << ",\"candidate_load_ms\":" << load_ms
                 << ",\"audio_ms\":" << audio.size()/16 << ",\"mel_frames\":" << frames
                 << ",\"encoder_frames\":" << out.n_enc_frames << ",\"width\":" << out.d_model
                 << ",\"backend\":\"" << backend << "\",\"finite\":" << (finite ? "true" : "false")
                 << ",\"relative_rmse\":" << (finite ? std::sqrt(sq / std::max(ref_sq, 1e-30)) : -1)
                 << ",\"max_abs_error\":" << (finite ? max_abs : -1);
            lab_map(json, "host_us", metrics.us); lab_map(json, "counts", metrics.counts);
            lab_map(json, "shaders", metrics.shaders); lab_map(json, "gpu_op_ns", metrics.gpu_ns);
            lab_map(json, "gpu_shapes_ns", metrics.gpu_shapes_ns); lab_map(json, "gpu_shapes_calls", metrics.gpu_shapes_calls);
            json << '}';
            lab_emit(progress, json.str());
            if (!finite) throw Failure{"NONFINITE_OR_SHAPE"};
        }
        lab_emit(progress, "{\"lab\":true,\"event\":\"FINISHED\"}");
        return true;
    } catch (const Failure & failure) {
        lab_emit(progress, std::string("{\"lab\":true,\"event\":\"") + (op->cancelled.load() ? "CANCELLED" : "FAILED") + "\",\"failure\":\"" + failure.code +
            "\",\"unsupported_op\":\"" + progress.control.unsupported_op + "\",\"vk_result\":" + std::to_string(progress.vulkan_result) + "}");
    } catch (const std::bad_alloc &) {
        lab_emit(progress, "{\"lab\":true,\"event\":\"FAILED\",\"failure\":\"OUT_OF_MEMORY\"}");
    } catch (...) {
        lab_emit(progress, "{\"lab\":true,\"event\":\"FAILED\",\"failure\":\"NATIVE_ERROR\"}");
    }
    return false;
}
