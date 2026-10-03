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
import org.altiro.core.EditorAuthority
import org.altiro.core.Phase
import org.altiro.core.RecognitionInput
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
    val recognition = LocalRecognition()
    val progress = MutableStateFlow(0)
    val activeModelName = MutableStateFlow("")
    val lastRun = MutableStateFlow<List<TimedTranscript>>(emptyList())
    val comparing = MutableStateFlow(false)

    private data class RunPlan(
        val inputs: List<RecognitionInput>,
        val language: String,
    )

    private var runPlan: RunPlan? = null
    val processingSeconds = MutableStateFlow(0)
    val language =
        MutableStateFlow(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("language", "auto") ?: "auto")
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
    ): SessionId? {
        checkMain()
        val state = session.value
        if (state.busy || state.phase in setOf(Phase.READY, Phase.AWAITING_USER)) return null
        if (models.busy.value || recognition.busy.value) return null
        if (compareIds != null && (!explicit || compareIds.size !in 2..4 || compareIds.distinct().size != compareIds.size)) return null
        val profiles =
            (compareIds ?: listOf(models.selected.value.spec.id)).map { id ->
                models.profiles.firstOrNull { it.spec.id == id } ?: return null
            }
        if (profiles.any { it.spec.id !in models.installed.value }) return null
        runPlan = RunPlan(profiles.map(models::snapshot), language.value)
        comparing.value = compareIds != null
        lastRun.value = emptyList()
        activeModelName.value = profiles.first().name
        val id = SessionId(++generation)
        event(SessionEvent.Start(id, if (explicit) null else editor.capture()))
        return id
    }

    fun event(event: SessionEvent) {
        checkMain()
        mutableSession.value = reduce(session.value, event)
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
            recognition.transcribe(audio, plan.inputs, plan.language, { percent, modelId ->
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    activeModelName.value = models.profiles.first { it.spec.id == modelId }.name
                    progress.value = percent
                    onProgress(percent)
                }
            }) { result ->
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
                        result.isFailure -> event(SessionEvent.Fail(id, "Local recognition failed. Check the model and try again."))
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

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Session events must be serialized on main." }
    }
}
