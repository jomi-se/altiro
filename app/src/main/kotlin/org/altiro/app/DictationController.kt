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
import org.altiro.core.Session
import org.altiro.core.SessionEvent
import org.altiro.core.SessionId
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

    fun begin(explicit: Boolean): SessionId? {
        checkMain()
        val state = session.value
        if (state.busy || state.phase in setOf(Phase.READY, Phase.AWAITING_USER)) return null
        if (!models.ready.value || models.busy.value || recognition.busy.value) return null
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
            recognition.transcribe(audio, models, language.value, { percent ->
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    progress.value = percent
                    onProgress(percent)
                }
            }) { result ->
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    val text = result.getOrNull()?.trim()
                    when {
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
                            scope.launch {
                                delay(600_000)
                                if (session.value.id == id && !session.value.busy) discard()
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

    fun importModel(uri: Uri) {
        if (!session.value.busy && !recognition.busy.value) models.importModel(uri)
    }

    fun deleteModel() {
        if (!session.value.busy && !recognition.busy.value) models.delete()
    }

    fun openModelDownload() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(models.sourceUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun selectLanguage(value: String) {
        require(value in setOf("auto", "en", "fr", "es"))
        if (session.value.busy || recognition.busy.value) return
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
        if (session.value.busy) cancel() else mutableSession.value = Session()
    }

    fun copy() {
        val text = session.value.text ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Altiro dictation", text))
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Session events must be serialized on main." }
    }
}
