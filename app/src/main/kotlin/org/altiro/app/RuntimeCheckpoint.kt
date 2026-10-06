package org.altiro.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.DiagnosticReport
import org.altiro.core.RuntimeDetails
import org.altiro.core.RuntimeFailure
import org.altiro.core.RuntimeStatus
import org.json.JSONObject

/** A single bounded, generated report. No native log strings or recognition text reach disk. */
class RuntimeCheckpoint(context: Context) {
    private val preferences =
        context.getSharedPreferences("diagnostic-notice", Context.MODE_PRIVATE)
    private val file = AtomicFile(context.noBackupFilesDir.resolve("recognition-checkpoint.txt"))
    private val mutableSaved = MutableStateFlow(read())
    val saved = mutableSaved.asStateFlow()
    private val mutableInterrupted =
        MutableStateFlow(
            mutableSaved.value?.lineSequence()?.any { it.startsWith("Outcome: RUNNING;") } ==
                true && !preferences.getBoolean("interruption-dismissed", false)
        )
    val interrupted = mutableInterrupted.asStateFlow()

    fun beginSession() {
        mutableInterrupted.value = false
        preferences.edit().remove("interruption-dismissed").apply()
    }

    fun dismissInterruption() {
        mutableInterrupted.value = false
        preferences.edit().putBoolean("interruption-dismissed", true).apply()
    }

    @Synchronized
    fun save(report: DiagnosticReport?) {
        if (report == null) return
        val bytes =
            ("Content-free checkpoint · app ${BuildConfig.VERSION_NAME}\n" + report.export())
                .toByteArray(Charsets.UTF_8)
        if (bytes.size > 65_536) return
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
            mutableSaved.value = String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            file.failWrite(output)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        mutableSaved.value = null
        mutableInterrupted.value = false
        preferences.edit().remove("interruption-dismissed").apply()
    }

    private fun read(): String? =
        try {
            file.openRead().use { input ->
                val bytes = input.readNBytes(65_537)
                if (bytes.size > 65_536) null else String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
}

/** Decode only named fields; ignore extra data rather than exporting arbitrary native text. */
internal fun runtimeDetails(
    previous: RuntimeDetails,
    json: String,
): RuntimeDetails {
    require(json.length <= 8192)
    val value = JSONObject(json)

    fun number(
        key: String,
        old: Long?,
    ): Long? = if (value.has(key)) value.getLong(key) else old

    fun boolean(
        key: String,
        old: Boolean?,
    ): Boolean? = if (value.has(key)) value.getBoolean(key) else old

    fun label(
        key: String,
        old: String?,
    ): String? = if (value.has(key)) value.getString(key) else old
    return previous.copy(
        status = RuntimeStatus.valueOf(value.getString("status")),
        failure =
            if (value.has("failure")) RuntimeFailure.valueOf(value.getString("failure"))
            else previous.failure,
        vulkanResult = number("vk_result", previous.vulkanResult?.toLong())?.toInt(),
        gpuName = label("gpu", previous.gpuName),
        vulkanVersion = label("vulkan", previous.vulkanVersion),
        driverVersion = number("driver", previous.driverVersion),
        deviceCount = number("devices", previous.deviceCount?.toLong())?.toInt(),
        storage16 = boolean("storage16", previous.storage16),
        shaderFloat16 = boolean("float16", previous.shaderFloat16),
        shaderInt8 = boolean("int8", previous.shaderInt8),
        gpuActive = boolean("gpu_active", previous.gpuActive),
        flashAttention = boolean("flash_attention", previous.flashAttention),
        audioContextFrames =
            number("audio_ctx", previous.audioContextFrames?.toLong())?.let { Math.toIntExact(it) },
        languageDetectionContextFrames =
            number(
                    "language_detection_audio_ctx",
                    previous.languageDetectionContextFrames?.toLong(),
                )
                ?.let { Math.toIntExact(it) },
        encodeCalls =
            number("encode_calls", previous.encodeCalls?.toLong())?.let { Math.toIntExact(it) },
        decodeCalls =
            number("decode_calls", previous.decodeCalls?.toLong())?.let { Math.toIntExact(it) },
        encodeMillis = number("encode_ms", previous.encodeMillis),
        decodeMillis = number("decode_ms", previous.decodeMillis),
        batchMillis = number("batch_ms", previous.batchMillis),
        promptMillis = number("prompt_ms", previous.promptMillis),
        sampleMillis = number("sample_ms", previous.sampleMillis),
    )
}
