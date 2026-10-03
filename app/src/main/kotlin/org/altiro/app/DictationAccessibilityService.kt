package org.altiro.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.altiro.core.EditorIdentity
import org.altiro.core.EditorState
import org.altiro.core.Phase
import org.altiro.core.SessionEvent

class DictationAccessibilityService : AccessibilityService() {
    private val controller get() = (application as AltiroApplication).controller
    private lateinit var method: DictationInputMethod
    private var overlay: DictationOverlay? = null
    private var observer: Job? = null

    private data class EditorMetadata(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
    )

    private var info: EditorMetadata? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private var composingStart = -1
    private var composingEnd = -1
    private var compositionKnown = false

    override fun onCreateInputMethod(): InputMethod = DictationInputMethod(this).also { method = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller.editor.reconnect()
        controller.connected.value = true
        controller.insertion = { dispatch(explicit = false) }
        controller.refreshSettings = ::refreshEditor
        overlay = DictationOverlay(this, controller, ::openRecording, { dispatch(explicit = true) })
        observer =
            controller.scope.launch {
                combine(
                    controller.session,
                    controller.editorLabel,
                    controller.progress,
                    controller.recognition.busy,
                    controller.models.ready,
                ) { _, _, _, _, _ -> Unit }.collect {
                    refreshEditor()
                }
            }
        refreshEditor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (event.packageName?.toString() == info?.packageName) controller.editor.invalidate()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> controller.editor.invalidate()
            else -> Unit
        }
        refreshEditor()
    }

    override fun onInterrupt() {
        controller.cancel()
        controller.editor.invalidate()
        refreshEditor()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        observer?.cancel()
        observer = null
        overlay?.close()
        overlay = null
        controller.insertion = null
        controller.refreshSettings = null
        controller.connected.value = false
        controller.editor.reconnect()
        controller.editorLabel.value = "Floating mic disconnected"
        controller.cancel()
    }

    private fun refreshEditor() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val editorInfo = info
        val node = if (!locked) rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null
        val packageName = editorInfo?.packageName
        val name = node?.uniqueId ?: node?.viewIdResourceName
        val identifiable =
            node != null && editorInfo != null && node.isEditable && node.isFocused && node.isVisibleToUser &&
                node.packageName?.toString() == packageName && (editorInfo.fieldId != 0 || !name.isNullOrBlank())
        val window = node?.window
        val identity =
            if (identifiable &&
                window != null
            ) {
                EditorIdentity(packageName!!, node.windowId, editorInfo!!.fieldId, name, window.displayId)
            } else {
                null
            }
        val state =
            EditorState(
                identity,
                selectionStart,
                selectionEnd,
                composingStart,
                composingEnd,
                compositionKnown = compositionKnown,
                password = node?.isPassword == true || isPassword(editorInfo?.inputType ?: 0),
                blocked = packageName?.let(controller::isDisabled) ?: false,
                locked = locked,
                connectionAvailable = ::method.isInitialized && method.currentInputConnection != null,
            )
        controller.editor.observe(state)
        controller.editorLabel.value = if (identity != null) identity.packageName else "No eligible field"
        if (locked && controller.session.value.busy) controller.cancel()
        overlay?.render(state, controller.session.value)
    }

    private fun dispatch(explicit: Boolean) {
        refreshEditor()
        val session = controller.session.value
        val id = session.id ?: return
        val text = session.text?.takeIf(String::isNotBlank) ?: return
        if (session.attemptConsumed || session.phase !in setOf(Phase.READY, Phase.AWAITING_USER)) return
        val token = if (explicit) controller.editor.capture() else session.destination
        val blocked = token?.let(controller.editor::blockReason)
        if (token == null || blocked != null) {
            val reason =
                if (blocked in
                    setOf(org.altiro.core.InsertionBlockReason.COMPOSING, org.altiro.core.InsertionBlockReason.UNKNOWN_COMPOSITION)
                ) {
                    "Finish the current word, then insert. If unavailable, use Copy."
                } else {
                    "Focus an eligible field and tap Insert, or use Copy."
                }
            controller.event(SessionEvent.AwaitUser(id, reason))
            return
        }
        val connection = if (::method.isInitialized) method.currentInputConnection else null
        if (connection == null) {
            controller.event(SessionEvent.AwaitUser(id, "This editor is not connected. Use Copy."))
            return
        }
        // Mark consumed before the cross-process mutation. Never retry on uncertainty.
        controller.event(SessionEvent.Dispatch(id))
        try {
            connection.commitText(text, 1, null)
        } catch (_: RuntimeException) {
            controller.event(SessionEvent.Fail(id, "Insertion outcome unknown."))
        }
    }

    private fun openRecording() {
        if (RecordingPreferences(this).recordInPlace) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                overlay?.showMessage("Allow the microphone in Altiro first.")
                return
            }
            val id = controller.begin(explicit = false) ?: return
            try {
                startForegroundService(DictationRecordingService.intent(this, DictationRecordingService.START, id))
            } catch (_: ForegroundServiceStartNotAllowedException) {
                controller.event(SessionEvent.Fail(id, "Recording here was blocked. Open Altiro and tap Open recording screen."))
            } catch (_: SecurityException) {
                controller.event(SessionEvent.Fail(id, "Microphone access was denied. Open Altiro and tap Open recording screen."))
            }
            return
        }
        controller.editor.invalidate()
        try {
            startActivity(Intent(this, RecordingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            // System background-activity restrictions may suppress or reject the launch.
            // The launcher always retains the visible recording path.
            overlay?.showMessage("Open Altiro and tap Open recording screen.")
        }
    }

    private inner class DictationInputMethod(
        service: AccessibilityService,
    ) : InputMethod(service) {
        override fun onStartInput(
            attribute: EditorInfo,
            restarting: Boolean,
        ) {
            super.onStartInput(attribute, restarting)
            info = EditorMetadata(attribute.packageName, attribute.fieldId, attribute.inputType)
            selectionStart = attribute.initialSelStart
            selectionEnd = attribute.initialSelEnd
            composingStart = -1
            composingEnd = -1
            compositionKnown = false
            controller.editor.start(EditorState(identity = null))
            refreshEditor()
        }

        override fun onFinishInput() {
            // Avoid the base method's composing-text cleanup in the host keyboard.
            info = null
            selectionStart = -1
            selectionEnd = -1
            composingStart = -1
            composingEnd = -1
            compositionKnown = false
            controller.editor.finish()
            refreshEditor()
        }

        override fun onUpdateSelection(
            oldSelStart: Int,
            oldSelEnd: Int,
            newSelStart: Int,
            newSelEnd: Int,
            candidatesStart: Int,
            candidatesEnd: Int,
        ) {
            selectionStart = newSelStart
            selectionEnd = newSelEnd
            composingStart = candidatesStart
            composingEnd = candidatesEnd
            compositionKnown = true
            refreshEditor()
        }
    }

    companion object {
        internal fun isPassword(inputType: Int): Boolean {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return (
                inputClass == InputType.TYPE_CLASS_TEXT &&
                    variation in
                    setOf(
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    )
            ) ||
                (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }
}
