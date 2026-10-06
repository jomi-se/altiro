package org.altiro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.altiro.core.DiagnosticOutcome
import org.altiro.core.EditorAuthority
import org.altiro.core.Phase
import org.altiro.core.RecognitionBackend
import org.altiro.core.RecognitionDiagnostics
import org.altiro.core.RecognitionInput
import org.altiro.core.RecognitionStage
import org.altiro.core.Session
import org.altiro.core.SessionEvent
import org.altiro.core.SessionId
import org.altiro.core.TimedTranscript
import org.altiro.core.reduce
import java.io.File

class DictationController(
    private val context: Context,
) {
    val editor = EditorAuthority()
    private val mutableSession = MutableStateFlow(Session())
    val session = mutableSession.asStateFlow()
    val connected = MutableStateFlow(false)
    val editorLabel = MutableStateFlow("No eligible field")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val models = ModelStore(context)
    val checkpoint = RuntimeCheckpoint(context)
    val recognition = LocalRecognition(context, checkpoint)
    val progress = MutableStateFlow(0)
    val activeModelName = MutableStateFlow("")
    val lastRun = MutableStateFlow<List<TimedTranscript>>(emptyList())
    val comparing = MutableStateFlow(false)
    val diagnostics = RecognitionDiagnostics()
    private var diagnosticGeneration = 0L

    private data class RunPlan(
        val inputs: List<RecognitionInput>,
        val language: String,
    )

    private var runPlan: RunPlan? = null
    val processingSeconds = MutableStateFlow(0)
    val language =
        MutableStateFlow(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("language", "auto") ?: "auto")
    val backend =
        MutableStateFlow(
            RecognitionBackend.entries.firstOrNull {
                it.name == context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("backend", "CPU")
            } ?: RecognitionBackend.CPU,
        )
    var insertion: (() -> Unit)? = null
    var refreshSettings: (() -> Unit)? = null
    private var generation = 0L
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    fun isDisabled(packageName: String): Boolean = packageName in preferences.getStringSet("disabled", emptySet()).orEmpty()

    fun disableCurrentApp() {
        val packageName = editor.current.identity?.packageName ?: return
        val disabled = preferences.getStringSet("disabled", emptySet()).orEmpty() + packageName
        preferences.edit().putStringSet("disabled", disabled).apply()
        editor.observe(editor.current.copy(blocked = true))
        editorLabel.value = "Disabled for this app"
        refreshSettings?.invoke()
    }

    fun clearDisabledApps() {
        preferences.edit().remove("disabled").apply()
        editor.invalidate()
        refreshSettings?.invoke()
    }

    fun begin(
        explicit: Boolean,
        compareIds: List<String>? = null,
        gpuCompare: Boolean = false,
        gpuFirst: Boolean = false,
    ): SessionId? {
        checkMain()
        val state = session.value
        if (state.busy || state.phase in setOf(Phase.READY, Phase.AWAITING_USER)) return null
        if (models.busy.value || recognition.busy.value) return null
        if (compareIds != null && (!explicit || compareIds.size !in 2..4 || compareIds.distinct().size != compareIds.size)) return null
        if (gpuCompare && (!explicit || compareIds != null)) return null
        val profiles =
            (compareIds ?: listOf(models.selected.value.spec.id)).map { id ->
                models.profiles.firstOrNull { it.spec.id == id } ?: return null
            }
        if (profiles.any { it.spec.id !in models.installed.value }) return null
        val inputs =
            if (gpuCompare) {
                val order = listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN).let { if (gpuFirst) it.reversed() else it }
                order.map { models.snapshot(profiles.single()).copy(backend = it) }
            } else {
                profiles.map { models.snapshot(it).copy(backend = backend.value) }
            }
        runPlan = RunPlan(inputs, language.value)
        comparing.value = compareIds != null || gpuCompare
        lastRun.value = emptyList()
        activeModelName.value = profiles.first().name
        val id = SessionId(++generation)
        diagnosticGeneration = id.value
        diagnostics.begin(runPlan!!.language, inputs.map { it.spec.id }, comparing.value, inputs.map { it.backend })
        checkpoint.save(diagnostics.report.value)
        scope.launch {
            while (diagnosticGeneration == id.value && diagnostics.report.value?.let { it.outcome == null } == true) {
                delay(1000)
                if (diagnosticGeneration == id.value) diagnostics.refresh()
            }
            delay(600_000)
            if (diagnosticGeneration == id.value && !recognition.busy.value) diagnostics.clear()
        }
        event(SessionEvent.Start(id, if (explicit) null else editor.capture()))
        return id
    }

    fun event(event: SessionEvent) {
        checkMain()
        val previous = session.value
        mutableSession.value = reduce(previous, event)
        if (previous.id != event.id || diagnosticGeneration != event.id.value) return
        if (event is SessionEvent.Cancel && !previous.attemptConsumed) {
            diagnostics.requestCancellation()
            if (!recognition.busy.value) diagnostics.finish(DiagnosticOutcome.CANCELLED)
        } else if (event is SessionEvent.Fail && !previous.attemptConsumed) {
            diagnostics.markFailure()
            if (!recognition.busy.value) diagnostics.finish(DiagnosticOutcome.FAILED)
        } else if (previous.phase != session.value.phase) {
            when (session.value.phase) {
                Phase.RECORDING -> diagnostics.phase(RecognitionStage.RECORDING)
                Phase.FINALIZING -> diagnostics.phase(RecognitionStage.FINALIZING)
                else -> Unit
            }
        }
    }

    fun transcribe(
        id: SessionId,
        audio: File,
        onProgress: (Int) -> Unit,
        complete: () -> Unit,
    ) {
        event(SessionEvent.AudioReady(id))
        if (session.value.id != id || session.value.phase != Phase.TRANSCRIBING) {
            audio.delete()
            complete()
            return
        }
        progress.value = 0
        processingSeconds.value = 0
        scope.launch {
            while (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                delay(1000)
                if (session.value.id != id || session.value.phase != Phase.TRANSCRIBING) break
                processingSeconds.value += 1
                if (processingSeconds.value >= 600) {
                    recognition.cancel()
                    event(SessionEvent.Fail(id, "Recognition exceeded ten minutes. Try a shorter recording."))
                    context.stopService(Intent(context, DictationRecordingService::class.java))
                    break
                }
            }
        }
        try {
            val plan = checkNotNull(runPlan)
            diagnostics.audioDuration(((audio.length() - 44).coerceAtLeast(0) / 32).coerceAtMost(300_000))
            recognition.transcribe(audio, plan.inputs, plan.language, diagnostics, { percent, modelId ->
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    activeModelName.value = models.profiles.first { it.spec.id == modelId }.name
                    progress.value = percent
                    onProgress(percent)
                }
            }) { result ->
                if (diagnosticGeneration == id.value) {
                    diagnostics.finish(
                        when {
                            session.value.phase == Phase.FAILED || result.isFailure -> DiagnosticOutcome.FAILED
                            diagnostics.report.value?.cancellationRequested == true -> DiagnosticOutcome.CANCELLED
                            else -> DiagnosticOutcome.COMPLETED
                        },
                    )
                }
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    val transcripts = result.getOrNull().orEmpty()
                    lastRun.value = transcripts
                    val text = transcripts.firstOrNull()?.text
                    scope.launch {
                        delay(600_000)
                        if (session.value.id == id && !session.value.busy) discard()
                    }
                    when {
                        comparing.value && result.isSuccess && transcripts.isNotEmpty() -> event(SessionEvent.ComparisonComplete(id))
                        result.isFailure ->
                            event(
                                SessionEvent.Fail(
                                    id,
                                    "Recognition failed. Open diagnostics for the backend and failure code. Try CPU if testing GPU.",
                                ),
                            )
                        text.isNullOrBlank() -> event(SessionEvent.Fail(id, "No speech was recognized. Try again or choose a language."))
                        else -> {
                            event(SessionEvent.Result(id, text))
                            val target = session.value.destination
                            if (target != null && editor.blockReason(target) == null && insertion != null) {
                                insertion?.invoke()
                            } else {
                                event(SessionEvent.AwaitUser(id, "Text ready. Focus a field and tap Insert."))
                            }
                        }
                    }
                }
                complete()
            }
        } catch (_: Exception) {
            audio.delete()
            event(SessionEvent.Fail(id, "Local recognition could not start."))
            complete()
        } catch (_: LinkageError) {
            audio.delete()
            event(SessionEvent.Fail(id, "The native runtime is unavailable for this device."))
            complete()
        }
    }

    fun importModel(
        uri: Uri,
        id: String,
    ) {
        if (!session.value.busy && !recognition.busy.value) models.importModel(uri, id)
    }

    fun deleteModel() {
        if (!session.value.busy && !recognition.busy.value) models.delete()
    }

    fun openModelDownload() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(models.selected.value.sourceUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun selectModel(id: String) {
        if (session.value.busy || recognition.busy.value) return
        models.select(id)
    }

    fun selectLanguage(value: String) {
        require(value in setOf("auto", "en", "fr", "es"))
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        language.value = value
        preferences.edit().putString("language", value).apply()
    }

    fun selectBackend(value: RecognitionBackend) {
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        backend.value = value
        preferences.edit().putString("backend", value.name).apply()
    }

    fun clearDiagnostics() {
        if (session.value.busy || recognition.busy.value) return
        diagnostics.clear()
        checkpoint.clear()
    }

    fun stop() {
        val id = session.value.id ?: return
        if (session.value.phase !in setOf(Phase.STARTING, Phase.RECORDING)) return
        event(SessionEvent.Stop(id))
        context.startService(DictationRecordingService.intent(context, DictationRecordingService.STOP, id))
    }

    fun cancel() {
        val id = session.value.id ?: return
        if (session.value.attemptConsumed) return
        event(SessionEvent.Cancel(id))
        recognition.cancel()
        context.stopService(Intent(context, DictationRecordingService::class.java))
    }

    fun discard() {
        if (session.value.busy) {
            cancel()
        } else {
            mutableSession.value = Session()
            lastRun.value = emptyList()
            runPlan = null
            comparing.value = false
        }
    }

    fun copy() {
        val text = session.value.text ?: return
        copyText(text)
    }

    fun copyText(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Altiro dictation", text))
    }

    fun processingLabel(): String {
        val step =
            diagnostics.report.value
                ?.steps
                ?.lastOrNull { it.running }
        val label = step?.stage?.label ?: "Preparing recognition"
        val seconds = (step?.durationMillis ?: 0) / 1000
        return if (step?.stage == RecognitionStage.INFERENCE) "$label · ${progress.value}% · ${seconds}s" else "$label · ${seconds}s"
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Session events must be serialized on main." }
    }
}
