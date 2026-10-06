package org.altiro.core

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecognitionStage(val label: String) {
    STARTUP("Starting microphone"),
    RECORDING("Recording"),
    FINALIZING("Finishing recording"),
    QUEUED("Waiting for recognition worker"),
    RUNTIME_START("Starting recognition runtime"),
    VERIFYING("Checking model file"),
    AUDIO_READ("Reading recorded audio"),
    WORKER_START("Connecting recognition process"),
    WORKER_STOP("Stopping recognition process"),
    GPU_PROBE("Checking Vulkan GPU"),
    MODEL_LOAD("Loading model into memory"),
    INFERENCE("Recognizing speech"),
    TEXT_ASSEMBLY("Preparing result"),
    MODEL_RELEASE("Releasing model memory"),
    AUDIO_DELETE("Deleting temporary audio"),
    WORKER_RELEASE("Finishing recognition worker"),
}

enum class DiagnosticOutcome {
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class DiagnosticStep(
    val stage: RecognitionStage,
    val modelId: String?,
    val startMillis: Long,
    val durationMillis: Long,
    val running: Boolean,
    val backend: RecognitionBackend? = null,
)

/** An allowlisted, content-free trace of the latest session, kept only in memory. */
data class DiagnosticReport(
    val language: String,
    val modelIds: List<String>,
    val comparison: Boolean,
    val audioMillis: Long? = null,
    val steps: List<DiagnosticStep> = emptyList(),
    val elapsedMillis: Long = 0,
    val outcome: DiagnosticOutcome? = null,
    val cancellationRequested: Boolean = false,
    val failureStage: RecognitionStage? = null,
    val backends: List<RecognitionBackend> = modelIds.map { RecognitionBackend.CPU },
    val runtimes: List<RuntimeDetails> = emptyList(),
) {
    val processingMillis: Long
        get() =
            steps
                .filter { it.stage !in setOf(RecognitionStage.STARTUP, RecognitionStage.RECORDING) }
                .sumOf { it.durationMillis }

    fun export(): String = buildString {
        appendLine("Altiro recognition diagnostics · content-free")
        appendLine("Language: $language; mode: ${if (comparison) "comparison" else "single model"}")
        appendLine("Models: ${modelIds.joinToString()}")
        appendLine("Requested backends: ${backends.joinToString { it.name }}")
        appendLine(
            "Runtime: whisper.cpp 1.9.4; 4 CPU threads; greedy; temperature 0; no translation"
        )
        appendLine(
            "GPU mode uses Vulkan with CPU operations as scheduled by Whisper; no automatic CPU retry"
        )
        appendLine("Model lifecycle: verify, cold load, release on every run; no resident model")
        appendLine("Audio: ${audioMillis?.let { seconds(it) + " s" } ?: "not finalized"}")
        appendLine(
            "Outcome: ${outcome?.name ?: "RUNNING"}; cancellation requested: $cancellationRequested"
        )
        failureStage?.let { appendLine("Failure stage: ${it.name}") }
        appendLine(
            "Session: ${seconds(elapsedMillis)} s; after Stop: ${seconds(processingMillis)} s"
        )
        audioMillis
            ?.takeIf { it > 0 }
            ?.let {
                appendLine(
                    "Processing/audio ratio: ${String.format(
                        Locale.ROOT,
                        "%.2f",
                        processingMillis.toDouble() / it,
                    )}"
                )
            }
        for (step in steps) {
            appendLine(
                "+${seconds(
                        step.startMillis
                    )} s · ${step.modelId ?: "session"}${step.backend?.let { " · ${it.name}" } ?: ""} · ${step.stage.label}: " +
                    "${seconds(step.durationMillis)} s${if (step.running) " (running)" else ""}"
            )
        }
        for (runtime in runtimes) append(runtime.export())
        appendLine(
            "Whisper counters are upstream compute counters, not an additive breakdown of wall time."
        )
        appendLine(
            "Inference includes audio features, language detection when Auto, encoder and decoder."
        )
        appendLine(
            "No dictated text, audio, editor identity, file paths or exception messages included."
        )
    }

    companion object {
        fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.2f", millis / 1000.0)
    }
}

class RecognitionDiagnostics(private val clockNanos: () -> Long = System::nanoTime) {
    private val mutableReport = MutableStateFlow<DiagnosticReport?>(null)
    val report = mutableReport.asStateFlow()
    private var started = 0L
    private var activeStarted = 0L
    private var completed = emptyList<DiagnosticStep>()
    private var active: Pair<RecognitionStage, String?>? = null
    private var backend: RecognitionBackend? = null
    private var activeBackend: RecognitionBackend? = null

    @Synchronized
    fun begin(
        language: String,
        modelIds: List<String>,
        comparison: Boolean,
        backends: List<RecognitionBackend> = modelIds.map { RecognitionBackend.CPU },
    ) {
        require(language in setOf("auto", "en", "fr", "es"))
        require(modelIds.size in 1..4 && modelIds.all { it.matches(Regex("[a-z0-9-]{1,80}")) })
        require(
            backends.size == modelIds.size &&
                modelIds.zip(backends).distinct().size == modelIds.size
        )
        started = clockNanos()
        completed = emptyList()
        active = null
        backend = null
        mutableReport.value =
            DiagnosticReport(language, modelIds.toList(), comparison, backends = backends.toList())
        phase(RecognitionStage.STARTUP)
    }

    @Synchronized
    fun selectBackend(value: RecognitionBackend?) {
        backend = value
    }

    @Synchronized
    fun runtime(value: RuntimeDetails) {
        val report = mutableReport.value ?: return
        if (report.outcome != null) return
        require((value.modelId to value.backend) in report.modelIds.zip(report.backends))
        mutableReport.value =
            report.copy(
                runtimes =
                    report.runtimes.filterNot {
                        it.modelId == value.modelId && it.backend == value.backend
                    } + value
            )
    }

    @Synchronized
    fun phase(
        stage: RecognitionStage,
        modelId: String? = null,
    ) {
        val report = mutableReport.value ?: return
        if (
            report.outcome != null ||
                (active == (stage to modelId) &&
                    activeBackend == if (modelId == null) null else backend)
        )
            return
        require(modelId == null || modelId in report.modelIds)
        check(completed.size < 64)
        val now = clockNanos()
        closeStep(now)
        active = stage to modelId
        activeBackend = if (modelId == null) null else backend
        activeStarted = now
        refreshAt(now)
    }

    @Synchronized
    fun audioDuration(millis: Long) {
        require(millis in 0..300_000)
        mutableReport.value = mutableReport.value?.copy(audioMillis = millis)
    }

    @Synchronized
    fun requestCancellation() {
        mutableReport.value = mutableReport.value?.copy(cancellationRequested = true)
    }

    @Synchronized
    fun markFailure() {
        mutableReport.value =
            mutableReport.value?.let {
                if (it.outcome != null) it
                else it.copy(failureStage = it.failureStage ?: active?.first)
            }
    }

    @Synchronized
    fun finish(outcome: DiagnosticOutcome) {
        if (mutableReport.value?.outcome != null) return
        val now = clockNanos()
        closeStep(now)
        active = null
        refreshAt(now)
        mutableReport.value = mutableReport.value?.copy(outcome = outcome)
    }

    @Synchronized
    fun refresh() {
        if (mutableReport.value?.outcome == null) refreshAt(clockNanos())
    }

    @Synchronized
    fun clear() {
        mutableReport.value = null
        completed = emptyList()
        active = null
    }

    private fun closeStep(now: Long) {
        active?.let { (stage, model) ->
            completed =
                completed +
                    DiagnosticStep(
                        stage,
                        model,
                        (activeStarted - started) / 1_000_000,
                        (now - activeStarted) / 1_000_000,
                        false,
                        activeBackend,
                    )
        }
    }

    private fun refreshAt(now: Long) {
        val step = active?.let { (stage, model) ->
            DiagnosticStep(
                stage,
                model,
                (activeStarted - started) / 1_000_000,
                (now - activeStarted) / 1_000_000,
                true,
                activeBackend,
            )
        }
        mutableReport.value =
            mutableReport.value?.copy(
                steps = completed + listOfNotNull(step),
                elapsedMillis = (now - started) / 1_000_000,
            )
    }
}
