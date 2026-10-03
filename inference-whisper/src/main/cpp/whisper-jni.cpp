#include <jni.h>
#include "whisper.h"
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Operation { std::atomic<bool> cancelled{false}; };
std::mutex registry_mutex;
std::mutex inference_mutex;
std::unordered_map<jlong, std::shared_ptr<Operation>> operations;
std::atomic<jlong> next_id{1};
std::once_flag log_once;
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
    JNIEnv *env, jobject, jlong id, jstring model_path, jstring audio_path, jstring language, jobject receiver) {
    auto operation = lookup(id);
    if (!operation || !model_path || !audio_path || !language || !receiver) {
        error(env, "Invalid native operation"); return nullptr;
    }
    try {
        // The shared operation remains alive if cancellation races with registry release.
        // Kotlin also keeps one executor and releases only after this call returns.
        std::lock_guard<std::mutex> serialized(inference_mutex);
        if (operation->cancelled.load()) return nullptr;
        UtfChars model(env, model_path), wav(env, audio_path), lang(env, language);
        if (!model.data || !wav.data || !lang.data) return nullptr;
        const std::string hint(lang.data);
        if (hint != "auto" && hint != "en" && hint != "fr" && hint != "es") throw std::runtime_error("Unsupported language");
        auto audio = read_audio(wav.data, *operation);
        if (operation->cancelled.load()) return nullptr;
        if (std::all_of(audio.begin(), audio.end(), [](float value) { return value == 0; })) return utf8_string(env, "");
        std::call_once(log_once, [] { whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr); });
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(model.data, context_params), whisper_free);
        // Model loading itself is synchronous upstream. Cancellation is honored before
        // inference and the context is freed here before completion is reported.
        if (operation->cancelled.load()) return nullptr;
        if (!context) throw std::runtime_error("Model load failed");
        auto cls = env->GetObjectClass(receiver);
        auto method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (!method) return nullptr;
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
        const int status = whisper_full(context.get(), params, audio.data(), static_cast<int>(audio.size()));
        if (operation->cancelled.load()) return nullptr;
        if (status != 0) throw std::runtime_error("Recognition failed");
        std::string text;
        for (int segment = 0; segment < whisper_full_n_segments(context.get()); ++segment) {
            text += whisper_full_get_segment_text(context.get(), segment);
            if (text.size() > 1024 * 1024) throw std::runtime_error("Recognition output exceeded limit");
        }
        return utf8_string(env, text);
    } catch (...) {
        if (!operation->cancelled.load()) error(env, "Local recognition failed");
        return nullptr;
    }
}
