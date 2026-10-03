package org.altiro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

class DictationController(
    private val context: Context,
) {
    val editor = EditorAuthority()
    private val mutableSession = MutableStateFlow(Session())
    val session = mutableSession.asStateFlow()
    val connected = MutableStateFlow(false)
    val editorLabel = MutableStateFlow("No eligible field")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
        val id = SessionId(++generation)
        event(SessionEvent.Start(id, if (explicit) null else editor.capture()))
        return id
    }

    fun event(event: SessionEvent) {
        checkMain()
        mutableSession.value = reduce(session.value, event)
    }

    fun finishFakeRecognition(id: SessionId) {
        event(SessionEvent.AudioReady(id))
        scope.launch {
            delay(600)
            event(SessionEvent.Result(id, "Dictation test: café, mañana, Kubernetes."))
            if (session.value.id != id || session.value.phase != Phase.READY) return@launch
            val target = session.value.destination
            if (target != null && editor.blockReason(target) == null && insertion != null) {
                insertion?.invoke()
            } else {
                event(SessionEvent.AwaitUser(id, "Text ready. Focus a field and tap Insert."))
            }
            delay(600_000)
            if (session.value.id == id && !session.value.busy) discard()
        }
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
