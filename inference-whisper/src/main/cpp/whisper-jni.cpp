#include <jni.h>
#include "whisper.h"
#include "ggml-backend.h"
#ifdef ALTIRO_VULKAN
#include <vulkan/vulkan.h>
#endif
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sstream>
#include <system_error>
#include <unordered_map>
#include <vector>

extern "C" void altiro_whisper_compute_totals(whisper_context *, int64_t *);
extern "C" bool altiro_whisper_gpu_active(whisper_context *);

namespace {
struct Operation { std::atomic<bool> cancelled{false}; };
struct RuntimeError : std::runtime_error {
    const char *code;
    explicit RuntimeError(const char *value): std::runtime_error(value), code(value) {}
};
std::mutex registry_mutex;
std::mutex inference_mutex;
std::unordered_map<jlong, std::shared_ptr<Operation>> operations;
std::atomic<jlong> next_id{1};
std::once_flag log_once;
std::once_flag backend_once;
bool process_gpu = false;
std::shared_ptr<Operation> lookup(jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    const auto found = operations.find(id);
    return found == operations.end() ? nullptr : found->second;
}
void error(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct UtfChars {
    JNIEnv *env; jstring value; const char *data;
    UtfChars(JNIEnv *env, jstring value): env(env), value(value), data(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (data) env->ReleaseStringUTFChars(value, data); }
};
uint32_t le32(const unsigned char *p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }
uint16_t le16(const unsigned char *p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
std::vector<float> read_audio(const char *path, const Operation &operation) {
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream) throw std::runtime_error("Audio unavailable");
    const auto size = static_cast<std::streamoff>(stream.tellg());
    if (size <= 44 || size > 44 + 16000 * 300 * 2) throw std::runtime_error("Unsupported audio length");
    stream.seekg(0);
    unsigned char header[44];
    stream.read(reinterpret_cast<char *>(header), 44);
    if (!stream || std::memcmp(header, "RIFF", 4) || std::memcmp(header + 8, "WAVEfmt ", 8) ||
        le32(header + 16) != 16 || le16(header + 20) != 1 || le16(header + 22) != 1 ||
        le32(header + 24) != 16000 || le32(header + 28) != 32000 || le16(header + 32) != 2 ||
        le16(header + 34) != 16 || std::memcmp(header + 36, "data", 4) ||
        le32(header + 40) != size - 44 || le32(header + 4) != size - 8 || (size - 44) % 2) {
        throw std::runtime_error("Unsupported audio format");
    }
    std::vector<float> audio(static_cast<size_t>(size - 44) / 2);
    unsigned char buffer[8192];
    size_t frame = 0;
    while (frame < audio.size()) {
        if (operation.cancelled.load()) return {};
        const size_t frames = std::min(sizeof(buffer) / 2, audio.size() - frame);
        stream.read(reinterpret_cast<char *>(buffer), static_cast<std::streamsize>(frames * 2));
        if (!stream) throw std::runtime_error("Audio read failed");
        for (size_t i = 0; i < frames; ++i) audio[frame++] = static_cast<int16_t>(le16(buffer + i * 2)) / 32768.0f;
    }
    return audio;
}
struct Progress { JNIEnv *env; jobject receiver; jmethodID method; Operation *operation; };
bool abort_inference(void *data) { return static_cast<Operation *>(data)->cancelled.load(); }
bool begin_encoder(whisper_context *, whisper_state *, void *data) { return !abort_inference(data); }
void progress(whisper_context *, whisper_state *, int percent, void *data) {
    auto *callback = static_cast<Progress *>(data);
    if (callback->operation->cancelled.load()) return;
    callback->env->CallVoidMethod(callback->receiver, callback->method, std::clamp(percent, 0, 100));
    if (callback->env->ExceptionCheck()) {
        callback->env->ExceptionClear();
        callback->operation->cancelled.store(true);
    }
}
jstring utf8_string(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    const auto cls = env->FindClass("java/lang/String");
    const auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto charset = env->NewStringUTF("UTF-8");
    auto value = static_cast<jstring>(env->NewObject(cls, constructor, bytes, charset));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return value;
}

#ifdef ALTIRO_VULKAN
std::string probe_gpu(bool &storage16) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Altiro";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) throw RuntimeError("VULKAN_UNAVAILABLE");
    struct Destroy { VkInstance value; ~Destroy() { vkDestroyInstance(value, nullptr); } } destroy{instance};
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || count == 0) throw RuntimeError("NO_GPU");
    if (count > 64) throw RuntimeError("VULKAN_UNAVAILABLE");
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) != VK_SUCCESS) throw RuntimeError("VULKAN_UNAVAILABLE");
    const auto device = devices.front();
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(device, &properties);
    VkPhysicalDeviceShaderFloat16Int8Features floats{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
    VkPhysicalDevice16BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    const bool floats_queried = properties.apiVersion >= VK_API_VERSION_1_2;
    if (floats_queried) storage.pNext = &floats;
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    features.pNext = &storage;
    vkGetPhysicalDeviceFeatures2(device, &features);
    storage16 = storage.storageBuffer16BitAccess;
    std::string name;
    for (const unsigned char c : std::string(properties.deviceName)) {
        if (name.size() == 96) break;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == ' ' || c == '.' || c == '_' || c == '(' || c == ')' || c == '+' || c == ':' || c == '-') name += c;
    }
    if (name.empty()) name = "unnamed GPU";
    std::ostringstream report;
    report << "{\"status\":\"AVAILABLE\",\"gpu\":\"" << name << "\",\"devices\":" << count
        << ",\"vulkan\":\"" << VK_VERSION_MAJOR(properties.apiVersion) << '.' << VK_VERSION_MINOR(properties.apiVersion)
        << '.' << VK_VERSION_PATCH(properties.apiVersion) << "\",\"driver\":" << properties.driverVersion
        << ",\"storage16\":" << (storage.storageBuffer16BitAccess ? "true" : "false");
    // Do not label unqueried extension capabilities as unsupported on older APIs.
    if (floats_queried) {
        report << ",\"float16\":" << (floats.shaderFloat16 ? "true" : "false")
            << ",\"int8\":" << (floats.shaderInt8 ? "true" : "false");
    }
    report << '}';
    return report.str();
}
#endif
}

extern "C" JNIEXPORT jlong JNICALL Java_org_altiro_inference_NativeWhisper_create(JNIEnv *env, jobject) {
    try {
        auto operation = std::make_shared<Operation>();
        const jlong id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> guard(registry_mutex);
        operations.emplace(id, std::move(operation));
        return id;
    } catch (...) { error(env, "Native allocation failed"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_org_altiro_inference_NativeWhisper_cancel(JNIEnv *, jobject, jlong id) {
    if (auto operation = lookup(id)) operation->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_org_altiro_inference_NativeWhisper_release(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    operations.erase(id);
}
extern "C" JNIEXPORT jstring JNICALL Java_org_altiro_inference_NativeWhisper_transcribe(
    JNIEnv *env, jobject, jlong id, jstring model_path, jstring audio_path, jstring language, jboolean gpu, jobject receiver) {
    auto operation = lookup(id);
    if (!operation || !model_path || !audio_path || !language || !receiver) {
        error(env, "Invalid native operation"); return nullptr;
    }
    jmethodID runtime_method = nullptr;
    const char *failure_code = "INFERENCE_FAILED";
    auto runtime = [&](const std::string &json) {
        if (!runtime_method || env->ExceptionCheck()) return;
        auto value = env->NewStringUTF(json.c_str());
        if (!value) return;
        env->CallVoidMethod(receiver, runtime_method, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) { env->ExceptionClear(); operation->cancelled.store(true); }
    };
    try {
        // The shared operation remains alive if cancellation races with registry release.
        // Kotlin also keeps one executor and releases only after this call returns.
        std::lock_guard<std::mutex> serialized(inference_mutex);
        if (operation->cancelled.load()) return nullptr;
        UtfChars model(env, model_path), wav(env, audio_path), lang(env, language);
        if (!model.data || !wav.data || !lang.data) return nullptr;
        const std::string hint(lang.data);
        if (hint != "auto" && hint != "en" && hint != "fr" && hint != "es") throw std::runtime_error("Unsupported language");
        auto cls = env->GetObjectClass(receiver);
        auto method = env->GetMethodID(cls, "onProgress", "(I)V");
        auto phase_method = env->GetMethodID(cls, "onPhase", "(I)V");
        runtime_method = env->GetMethodID(cls, "onRuntime", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!method || !phase_method || !runtime_method) return nullptr;
        auto phase = [&](int value) {
            env->CallVoidMethod(receiver, phase_method, value);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                operation->cancelled.store(true);
            }
        };
        failure_code = "AUDIO_READ_FAILED";
        phase(1);
        auto audio = read_audio(wav.data, *operation);
        if (operation->cancelled.load()) return nullptr;
        if (std::all_of(audio.begin(), audio.end(), [](float value) { return value == 0; })) {
            runtime("{\"status\":\"FINISHED\"}");
            return utf8_string(env, "");
        }
        std::call_once(backend_once, [&] {
            process_gpu = gpu;
            // Registry initialization is process-global. The Android owner uses a
            // fresh worker per pass so CPU never probes or initializes a GPU driver.
            if (!gpu) setenv("GGML_DISABLE_VULKAN", "1", 1);
        });
        if (process_gpu != bool(gpu)) throw RuntimeError("GPU_INIT_FAILED");
        std::call_once(log_once, [] {
            auto silent = [](ggml_log_level, const char *, void *) {};
            whisper_log_set(silent, nullptr);
            ggml_log_set(silent, nullptr);
        });
        if (gpu) {
            failure_code = "GPU_INIT_FAILED";
            phase(7);
            runtime("{\"status\":\"PROBING\"}");
#ifdef ALTIRO_VULKAN
            bool storage16 = false;
            runtime(probe_gpu(storage16));
            if (!storage16) throw RuntimeError("STORAGE_16_UNSUPPORTED");
            auto reg = ggml_backend_reg_by_name("Vulkan");
            if (!reg || ggml_backend_reg_dev_count(reg) == 0) throw RuntimeError("NO_GPU");
#else
            throw RuntimeError("VULKAN_UNAVAILABLE");
#endif
        }
        if (operation->cancelled.load()) return nullptr;
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = gpu;
        context_params.flash_attn = false;
        phase(2);
        runtime("{\"status\":\"INITIALIZING\"}");
        failure_code = gpu ? "GPU_INIT_FAILED" : "MODEL_LOAD_FAILED";
        std::unique_ptr<whisper_context, std::function<void(whisper_context *)>> context(
            whisper_init_from_file_with_params(model.data, context_params), [&](whisper_context *value) {
                if (std::uncaught_exceptions() && !operation->cancelled.load()) phase(6);
                phase(5);
                whisper_free(value);
            });
        // Model loading itself is synchronous upstream. Cancellation is honored before
        // inference and the context is freed here before completion is reported.
        if (operation->cancelled.load()) return nullptr;
        if (!context) throw RuntimeError("MODEL_LOAD_FAILED");
        const bool gpu_active = altiro_whisper_gpu_active(context.get());
        if (gpu && !gpu_active) throw RuntimeError("GPU_INIT_FAILED");
        runtime(std::string("{\"status\":\"READY\",\"gpu_active\":") + (gpu_active ? "true}" : "false}"));
        Progress callback{env, receiver, method, operation.get()};
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = 4;
        params.translate = false;
        params.language = hint == "auto" ? nullptr : lang.data;
        params.detect_language = false; // null language detects then transcribes; true would detect only.
        params.no_context = true;
        params.single_segment = false;
        params.duration_ms = 0; // Process all windows, including recordings longer than 30 seconds.
        params.print_realtime = params.print_progress = params.print_timestamps = params.print_special = false;
        params.temperature = params.temperature_inc = 0;
        params.suppress_blank = params.suppress_nst = true;
        params.progress_callback = progress;
        params.progress_callback_user_data = &callback;
        params.encoder_begin_callback = begin_encoder;
        params.encoder_begin_callback_user_data = operation.get();
        params.abort_callback = abort_inference;
        params.abort_callback_user_data = operation.get();
        phase(3);
        failure_code = "INFERENCE_FAILED";
        const int status = whisper_full(context.get(), params, audio.data(), static_cast<int>(audio.size()));
        int64_t totals[7]{};
        altiro_whisper_compute_totals(context.get(), totals);
        std::ostringstream counters;
        counters << "{\"status\":\"" << (operation->cancelled.load() ? "CANCELLED" : status ? "FAILED" : "FINISHED")
            << "\",\"encode_ms\":" << totals[0] << ",\"decode_ms\":" << totals[1]
            << ",\"batch_ms\":" << totals[2] << ",\"prompt_ms\":" << totals[3]
            << ",\"sample_ms\":" << totals[4] << '}';
        runtime(counters.str());
        if (operation->cancelled.load()) return nullptr;
        if (status != 0) throw std::runtime_error("Recognition failed");
        phase(4);
        std::string text;
        for (int segment = 0; segment < whisper_full_n_segments(context.get()); ++segment) {
            text += whisper_full_get_segment_text(context.get(), segment);
            if (text.size() > 1024 * 1024) throw std::runtime_error("Recognition output exceeded limit");
        }
        return utf8_string(env, text);
    } catch (const std::bad_alloc &) {
        runtime("{\"status\":\"FAILED\",\"failure\":\"OUT_OF_MEMORY\"}");
        if (!operation->cancelled.load()) error(env, "Local recognition failed");
        return nullptr;
    } catch (const std::system_error &failure) {
        const int code = failure.code().value();
        const bool vulkan_error = std::strcmp(failure.code().category().name(), "vk::Result") == 0;
        const char *kind = vulkan_error && code == -4 ? "DEVICE_LOST" :
            vulkan_error && (code == -1 || code == -2) ? "OUT_OF_MEMORY" : failure_code;
        runtime(std::string("{\"status\":\"FAILED\",\"failure\":\"") + kind + "\"" +
            (vulkan_error ? ",\"vk_result\":" + std::to_string(code) : "") + "}");
        if (!operation->cancelled.load()) error(env, "Local recognition failed");
        return nullptr;
    } catch (const RuntimeError &failure) {
        runtime(std::string("{\"status\":\"FAILED\",\"failure\":\"") + failure.code + "\"}");
        if (!operation->cancelled.load()) error(env, "Local recognition failed");
        return nullptr;
    } catch (...) {
        runtime(std::string("{\"status\":\"FAILED\",\"failure\":\"") + failure_code + "\"}");
        if (!operation->cancelled.load()) error(env, "Local recognition failed");
        return nullptr;
    }
}
