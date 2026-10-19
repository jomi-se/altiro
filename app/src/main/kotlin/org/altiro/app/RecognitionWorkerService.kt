package org.altiro.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import org.altiro.core.RecognitionEngine
import org.altiro.inference.NativeProgress
import org.altiro.inference.NativeWhisper
import org.altiro.transcribepsy.NativeTranscribePsy

/** One native call per process: CPU never enumerates Vulkan, and GPU failures stay here. */
class RecognitionWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var requestId = 0L
    private var cancelled = false
    private var cancelNative: (() -> Unit)? = null
    private var releaseNative: (() -> Unit)? = null
    private var handle = 0L
    private val binder =
        object : IRecognitionWorker.Stub() {
            override fun run(
                request: Long,
                engine: String,
                model: String,
                audio: String,
                language: String,
                gpu: Boolean,
                flashAttention: Boolean,
                dynamicWindow: Boolean,
                vocabularyPrompt: String,
                encoderExperiment: String,
                callback: IRecognitionCallback,
            ) {
                synchronized(lock) {
                    check(requestId == 0L)
                    requestId = request
                }
                executor.execute {
                    var status = 2
                    var labTraceOpen = false
                    try {
                        val prompt = promptBytes(vocabularyPrompt)
                        if (prompt == null) {
                            callback.onRuntime(
                                "{\"status\":\"FAILED\",\"failure\":\"VOCABULARY_INVALID\"}"
                            )
                            return@execute
                        }
                        val family = RecognitionEngine.valueOf(engine)
                        val lab =
                            if (encoderExperiment.isEmpty()) null
                            else
                                checkNotNull(
                                    org.altiro.core.EncoderExperiment.options(encoderExperiment)
                                )
                        check(
                            lab == null ||
                                (family == RecognitionEngine.TRANSCRIBEPSY &&
                                    (gpu || lab.second in listOf(0, 10)))
                        )
                        if (
                            family == RecognitionEngine.TRANSCRIBEPSY &&
                                (language != "auto" ||
                                    flashAttention ||
                                    dynamicWindow ||
                                    vocabularyPrompt.isNotEmpty())
                        ) {
                            callback.onRuntime(
                                "{\"status\":\"FAILED\",\"failure\":\"ENGINE_OPTIONS_UNSUPPORTED\"}"
                            )
                            return@execute
                        }
                        val whisper =
                            if (family == RecognitionEngine.WHISPER) NativeWhisper() else null
                        val psy =
                            if (family == RecognitionEngine.TRANSCRIBEPSY) NativeTranscribePsy()
                            else null
                        synchronized(lock) {
                            handle = whisper?.create() ?: checkNotNull(psy).create()
                            val currentHandle = handle
                            cancelNative = {
                                if (whisper != null) whisper.cancel(currentHandle)
                                else psy!!.cancel(currentHandle)
                            }
                            releaseNative = {
                                if (whisper != null) whisper.release(currentHandle)
                                else psy!!.release(currentHandle)
                            }
                            if (cancelled) cancelNative?.invoke()
                        }
                        var sampledPeakPss = 0L
                        val progress =
                            object : NativeProgress {
                                override fun onProgress(percent: Int) = callback.onProgress(percent)

                                override fun onPhase(phase: Int) = callback.onPhase(phase)

                                override fun onRuntime(report: String) {
                                    if (lab != null) {
                                        val event = org.json.JSONObject(report)
                                        val name =
                                            when (event.optString("event")) {
                                                "PHASE" ->
                                                    when (event.optString("phase")) {
                                                        "REFERENCE" -> "altiro.encoder.reference"
                                                        "CANDIDATE_LOAD" -> "altiro.encoder.load"
                                                        else -> null
                                                    }
                                                "BEGIN" ->
                                                    "altiro.encoder.repeat-${event.optInt("repeat")}"
                                                else -> null
                                            }
                                        if (labTraceOpen) {
                                            android.os.Trace.endSection()
                                            labTraceOpen = false
                                        }
                                        if (name != null) {
                                            android.os.Trace.beginSection(name)
                                            labTraceOpen = true
                                        }
                                    }
                                    if (family == RecognitionEngine.TRANSCRIBEPSY) {
                                        sampledPeakPss =
                                            maxOf(
                                                sampledPeakPss,
                                                android.os.Debug.getPss().toLong(),
                                            )
                                        callback.onRuntime(
                                            org.json
                                                .JSONObject(report)
                                                .put("sampled_peak_pss_kib", sampledPeakPss)
                                                .toString()
                                        )
                                    } else callback.onRuntime(report)
                                }
                            }
                        val labCache = java.io.File(cacheDir, "encoder-lab-vulkan")
                        fun cacheReport(event: String) {
                            val files =
                                labCache
                                    .listFiles()
                                    ?.filter { it.isFile && it.name.endsWith(".pcache") }
                                    .orEmpty()
                            callback.onRuntime(
                                org.json
                                    .JSONObject()
                                    .put("lab", true)
                                    .put("event", event)
                                    .put("files", files.size)
                                    .put("bytes", files.sumOf { it.length() })
                                    .toString()
                            )
                        }
                        val text =
                            if (lab != null) {
                                cacheReport("CACHE_BEFORE")
                                if (
                                    psy!!.benchmark(
                                        handle,
                                        model,
                                        audio,
                                        gpu,
                                        lab.first,
                                        lab.second,
                                        labCache.also { it.mkdirs() }.absolutePath,
                                        progress,
                                    )
                                )
                                    ""
                                else null
                            } else if (whisper == null)
                                psy!!.transcribe(handle, model, audio, gpu, progress)
                            else
                                whisper.transcribe(
                                    handle,
                                    model,
                                    audio,
                                    language,
                                    gpu,
                                    flashAttention,
                                    dynamicWindow,
                                    progress,
                                    prompt,
                                )
                        if (lab != null) cacheReport("CACHE_AFTER")
                        if (text != null && !synchronized(lock) { cancelled }) {
                            // Bound Binder transactions; partial chunks never become an insertion
                            // payload.
                            for (chunk in text.chunked(8192)) callback.onTextChunk(chunk)
                            status = 0
                        } else {
                            status = if (synchronized(lock) { cancelled }) 1 else 2
                        }
                    } catch (_: LinkageError) {
                        callback.onRuntime(
                            "{\"status\":\"FAILED\",\"failure\":\"NATIVE_UNAVAILABLE\"}"
                        )
                    } catch (_: Exception) {
                        // Native code has already supplied a typed failure when possible.
                    } finally {
                        if (labTraceOpen) android.os.Trace.endSection()
                        synchronized(lock) {
                            if (handle != 0L) releaseNative?.invoke()
                            handle = 0
                            cancelNative = null
                            releaseNative = null
                        }
                        try {
                            callback.onComplete(status)
                        } catch (_: Exception) {
                            Process.killProcess(Process.myPid())
                        }
                    }
                }
            }

            override fun cancel(request: Long) {
                synchronized(lock) {
                    if (requestId != request) return
                    cancelled = true
                    if (handle != 0L) cancelNative?.invoke()
                }
            }

            override fun shutdown() {
                Process.killProcess(Process.myPid())
            }
        }

    override fun onBind(intent: Intent): IBinder = binder

    /** Lone surrogates fail instead of becoming '?'; native enforces the byte cap and NUL. */
    private fun promptBytes(value: String): ByteArray? =
        try {
            val bytes =
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            ByteArray(bytes.remaining()).also { bytes.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }

    override fun onUnbind(intent: Intent): Boolean {
        // No native work may outlive its single parent-owned binding.
        Process.killProcess(Process.myPid())
        return false
    }
}
