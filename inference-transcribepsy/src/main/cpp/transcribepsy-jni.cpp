#include <jni.h>
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
