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
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.altiro.core.EditorIdentity
import org.altiro.core.EditorInputKind
import org.altiro.core.EditorLookup
import org.altiro.core.EditorNodeKind
import org.altiro.core.EditorProbeTrigger
import org.altiro.core.EditorState
import org.altiro.core.EditorVisibilityReason
import org.altiro.core.EditorVisibilitySnapshot
import org.altiro.core.EditorWindowKind
import org.altiro.core.Phase
import org.altiro.core.SessionEvent
import org.altiro.core.windowChangeInvalidatesDestination

class DictationAccessibilityService : AccessibilityService() {
    private val controller
        get() = (application as AltiroApplication).controller

    private lateinit var method: DictationInputMethod
    private var overlay: DictationOverlay? = null
    private var observer: Job? = null
    private var languageObserver: Job? = null
    private var settleJob: Job? = null
    private var settledInput: EditorMetadata? = null
    private var settleContentChanged = false

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

    override fun onCreateInputMethod(): InputMethod =
        DictationInputMethod(this).also { method = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller.editor.reconnect()
        controller.connected.value = true
        controller.insertion = { dispatch(explicit = false) }
        controller.refreshSettings = { refreshEditor() }
        overlay =
            DictationOverlay(
                this,
                controller,
                ::openRecording,
                { dispatch(explicit = true) },
                ::openApp,
            )
        observer =
            controller.scope.launch {
                combine(
                        controller.session,
                        controller.editorLabel,
                        controller.diagnostics.report,
                        controller.recognition.busy,
                        controller.models.ready,
                    ) { _, _, _, _, _ ->
                        Unit
                    }
                    .collect {
                        renderOverlay()
                    }
            }
        languageObserver =
            controller.scope.launch {
                combine(controller.language, controller.models.busy, controller.progress) { _, _, _
                        ->
                        Unit
                    }
                    .collect { renderOverlay() }
            }
        refreshEditor(EditorProbeTrigger.CONNECT)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Structural changes can expose a virtual editor after onStartInput. Ignore
        // unrelated app/overlay content; inspect only the current input's focused node.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (
                info != null &&
                    event.packageName?.toString() == info?.packageName &&
                    controller.editor.current.identity == null &&
                    settleJob?.isActive == true
            )
                settleContentChanged = true
            // Coalesce into the existing five-probe budget. Streaming replies and
            // terminal output must not turn this into permanent main-thread IPC.
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (event.packageName?.toString() == info?.packageName)
                    controller.editor.invalidate()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (windowChangeInvalidatesDestination(event.windowId, overlay?.windowId)) {
                    controller.otherWindowEvents.value++
                    controller.editor.invalidate()
                } else {
                    controller.overlayWindowEvents.value++
                    return
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (windowChangeInvalidatesDestination(event.windowId, overlay?.windowId))
                    controller.editor.invalidate()
            }
            else -> Unit
        }
        val trigger =
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> EditorProbeTrigger.FOCUS
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> EditorProbeTrigger.TEXT
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> EditorProbeTrigger.SELECTION
                else -> EditorProbeTrigger.WINDOW
            }
        refreshEditor(trigger)
        settleEditor()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.reposition()
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
        settleJob?.cancel()
        settleJob = null
        settledInput = null
        settleContentChanged = false
        observer?.cancel()
        observer = null
        languageObserver?.cancel()
        languageObserver = null
        overlay?.dispose()
        overlay = null
        controller.insertion = null
        controller.refreshSettings = null
        controller.connected.value = false
        controller.overlayStatus.value = "DISCONNECTED"
        controller.editorVisibility.record(
            EditorVisibilitySnapshot(EditorVisibilityReason.DISCONNECTED),
            EditorProbeTrigger.DISCONNECT,
            externalInput = false,
        )
        controller.editor.reconnect()
        controller.editorLabel.value = "Floating mic disconnected"
        controller.cancel()
    }

    private fun refreshEditor(trigger: EditorProbeTrigger = EditorProbeTrigger.STATE) {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val editorInfo = info
        val currentWindows = if (!locked) windows else emptyList()
        val focusedApp = currentWindows.firstOrNull {
            it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val focusedRoot = focusedApp?.getRoot(0)
        val focusedPackage = focusedRoot?.packageName?.toString()
        val packageName = editorInfo?.packageName
        val global =
            if (!locked && editorInfo != null) findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            else null
        val globalWindow = global?.window
        val globalMatches =
            global != null &&
                global.isFocused &&
                global.packageName?.toString() == packageName &&
                globalWindow?.isFocused == true &&
                globalWindow.id == focusedApp?.id &&
                globalWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION
        val fallback =
            if (!locked && editorInfo != null && !globalMatches && focusedPackage == packageName)
                focusedRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            else null
        val node = if (globalMatches) global else fallback ?: global
        val lookup =
            when {
                globalMatches -> EditorLookup.GLOBAL_INPUT_FOCUS
                fallback != null -> EditorLookup.FOCUSED_APP_WINDOW
                global != null -> EditorLookup.GLOBAL_INPUT_FOCUS
                else -> EditorLookup.NONE
            }
        val window = node?.window
        val name = node?.uniqueId ?: node?.viewIdResourceName
        val identifiable =
            node != null &&
                editorInfo != null &&
                node.isEditable &&
                node.isFocused &&
                node.isVisibleToUser &&
                window?.isFocused == true &&
                window.id == focusedApp?.id &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                node.packageName?.toString() == packageName &&
                (editorInfo.fieldId != 0 || !name.isNullOrBlank())
        val identity =
            if (identifiable && window != null) {
                EditorIdentity(
                    packageName!!,
                    node.windowId,
                    editorInfo!!.fieldId,
                    name,
                    window.displayId,
                )
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
                connectionAvailable =
                    ::method.isInitialized && method.currentInputConnection != null,
            )
        controller.editor.observe(state)
        controller.editorLabel.value =
            if (identity != null) identity.packageName else "No eligible field"
        if (locked && controller.session.value.busy) controller.cancel()
        overlay?.render(state, controller.session.value)
        // Fixed, content-free codes only: never package names, field ids or text.
        val reason =
            when {
                locked -> EditorVisibilityReason.LOCKED
                editorInfo == null -> EditorVisibilityReason.NO_INPUT_START
                state.password -> EditorVisibilityReason.PASSWORD
                state.blocked -> EditorVisibilityReason.HIDDEN_APP
                node == null -> EditorVisibilityReason.NO_FOCUS_NODE
                !node.isEditable -> EditorVisibilityReason.NOT_EDITABLE
                !node.isFocused -> EditorVisibilityReason.NOT_FOCUSED
                !node.isVisibleToUser -> EditorVisibilityReason.NOT_VISIBLE
                node.packageName?.toString() != packageName ->
                    EditorVisibilityReason.PACKAGE_MISMATCH
                editorInfo.fieldId == 0 && name.isNullOrBlank() ->
                    EditorVisibilityReason.NO_FIELD_KEY
                window == null -> EditorVisibilityReason.NO_WINDOW
                window.type != AccessibilityWindowInfo.TYPE_APPLICATION ->
                    EditorVisibilityReason.NOT_APP_WINDOW
                !window.isFocused || window.id != focusedApp?.id ->
                    EditorVisibilityReason.WINDOW_NOT_FOCUSED
                !state.connectionAvailable -> EditorVisibilityReason.NO_CONNECTION
                window.displayId != 0 -> EditorVisibilityReason.OTHER_DISPLAY
                else -> EditorVisibilityReason.ELIGIBLE
            }
        controller.overlayStatus.value = if (overlay?.visible == true) "SHOWN" else reason.name
        val snapshot =
            EditorVisibilitySnapshot(
                reason = reason,
                shown = overlay?.visible == true,
                lookup = lookup,
                input = inputKind(editorInfo?.inputType),
                connection = state.connectionAvailable,
                activeWindow = windowKind(currentWindows.firstOrNull { it.isActive }?.type),
                focusedApp = focusedApp != null,
                focusedAppMatches = focusedPackage != null && focusedPackage == packageName,
                node = node != null,
                nodeKind = nodeKind(node),
                editable = node?.isEditable == true,
                focused = node?.isFocused == true,
                visible = node?.isVisibleToUser == true,
                packageMatches = node != null && node.packageName?.toString() == packageName,
                fieldKey = editorInfo != null && (editorInfo.fieldId != 0 || !name.isNullOrBlank()),
                windowFocused = window?.isFocused == true,
            )
        // Only a positively matched, started external input can replace this snapshot.
        // INPUT_FINISH/returning to Altiro remain in the timeline but cannot erase it.
        val externalInput =
            !locked &&
                editorInfo != null &&
                focusedPackage != null &&
                focusedPackage != this.packageName &&
                editorInfo.packageName == focusedPackage
        controller.editorVisibility.record(snapshot, trigger, externalInput)
        if (externalInput) controller.lastEditorStatus.value = reason.name
    }

    private fun settleEditor() {
        val startedInput = info ?: return
        if (controller.editor.current.identity != null || settledInput === startedInput) return
        settledInput = startedInput
        settleContentChanged = false
        settleJob =
            controller.scope.launch {
                for (waitMillis in listOf(100L, 150L, 250L, 500L, 500L)) {
                    delay(waitMillis)
                    if (info !== startedInput || !controller.connected.value) break
                    val trigger =
                        if (settleContentChanged) EditorProbeTrigger.CONTENT
                        else EditorProbeTrigger.SETTLED
                    settleContentChanged = false
                    refreshEditor(trigger)
                    if (controller.editor.current.identity != null) break
                }
            }
    }

    /** Visual updates keep cached editor metadata; tree queries stay on editor events. */
    private fun renderOverlay() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked != controller.editor.current.locked) {
            refreshEditor(EditorProbeTrigger.WINDOW)
            return
        }
        overlay?.render(controller.editor.current, controller.session.value)
        controller.overlayStatus.value =
            if (overlay?.visible == true) "SHOWN"
            else
                controller.editorVisibility.report.value.events.lastOrNull()?.snapshot?.reason?.name
                    ?: "DISCONNECTED"
    }

    private fun nodeKind(node: AccessibilityNodeInfo?): EditorNodeKind {
        node ?: return EditorNodeKind.NONE
        // Class names stay local; only these fixed, generic categories are exported.
        val name = node.className?.toString().orEmpty()
        return when {
            name.endsWith("EditText") -> EditorNodeKind.EDIT_TEXT
            name == "androidx.compose.ui.platform.AndroidComposeView" -> EditorNodeKind.COMPOSE_HOST
            name == "android.webkit.WebView" -> EditorNodeKind.WEB
            name == "com.termux.view.TerminalView" -> EditorNodeKind.TERMINAL
            else -> EditorNodeKind.OTHER
        }
    }

    private fun inputKind(type: Int?): EditorInputKind =
        when {
            type == null -> EditorInputKind.NONE
            type == InputType.TYPE_NULL -> EditorInputKind.RAW
            type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT -> EditorInputKind.TEXT
            type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER ->
                EditorInputKind.NUMBER
            type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE ->
                EditorInputKind.PHONE
            else -> EditorInputKind.OTHER
        }

    private fun windowKind(type: Int?): EditorWindowKind =
        when (type) {
            null -> EditorWindowKind.NONE
            AccessibilityWindowInfo.TYPE_APPLICATION -> EditorWindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> EditorWindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> EditorWindowKind.OVERLAY
            AccessibilityWindowInfo.TYPE_SYSTEM -> EditorWindowKind.SYSTEM
            else -> EditorWindowKind.OTHER
        }

    private fun dispatch(explicit: Boolean) {
        refreshEditor()
        val session = controller.session.value
        val id = session.id ?: return
        val text = session.text?.takeIf(String::isNotBlank) ?: return
        if (session.attemptConsumed || session.phase !in setOf(Phase.READY, Phase.AWAITING_USER))
            return
        val token = if (explicit) controller.editor.capture() else session.destination
        val blocked = token?.let(controller.editor::blockReason)
        if (token == null || blocked != null) {
            val reason =
                if (
                    blocked in
                        setOf(
                            org.altiro.core.InsertionBlockReason.COMPOSING,
                            org.altiro.core.InsertionBlockReason.UNKNOWN_COMPOSITION,
                        )
                ) {
                    "Finish the word, then tap Insert"
                } else {
                    "Tap a text field, then Insert"
                }
            controller.event(SessionEvent.AwaitUser(id, reason))
            return
        }
        val connection = if (::method.isInitialized) method.currentInputConnection else null
        if (connection == null) {
            controller.event(SessionEvent.AwaitUser(id, "Can't insert here · use Copy"))
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
        refreshEditor()
        val current = controller.editor.current
        if (
            current.identity?.displayId != 0 ||
                !current.connectionAvailable ||
                current.password ||
                current.blocked ||
                current.locked
        )
            return
        if (RecordingPreferences(this).recordInPlace) {
            if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                overlay?.showMessage("Microphone not allowed", "Open Altiro", ::openApp)
                return
            }
            val id = controller.begin(explicit = false) ?: return
            try {
                startForegroundService(
                    DictationRecordingService.intent(this, DictationRecordingService.START, id)
                )
            } catch (_: ForegroundServiceStartNotAllowedException) {
                controller.event(SessionEvent.Fail(id, "Recording blocked here"))
                // The specified visible-Activity fallback, started only by an explicit tap.
                overlay?.showMessage("Recording blocked here", "Open recorder", ::openRecorder)
            } catch (_: SecurityException) {
                controller.event(SessionEvent.Fail(id, "Microphone access denied"))
                overlay?.showMessage("Microphone access denied", "Open Altiro", ::openApp)
            }
            return
        }
        openRecorder()
    }

    private fun openRecorder() {
        controller.editor.invalidate()
        try {
            startActivity(
                Intent(this, RecordingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: RuntimeException) {
            // System background-activity restrictions may suppress or reject the launch.
            // The launcher always retains the visible recording path.
            overlay?.showMessage("Couldn't open the recorder · open Altiro")
        }
    }

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: RuntimeException) {
            overlay?.showMessage("Open Altiro from your launcher")
        }
    }

    private inner class DictationInputMethod(service: AccessibilityService) : InputMethod(service) {
        override fun onStartInput(
            attribute: EditorInfo,
            restarting: Boolean,
        ) {
            super.onStartInput(attribute, restarting)
            settleJob?.cancel()
            settleJob = null
            settledInput = null
            settleContentChanged = false
            info = EditorMetadata(attribute.packageName, attribute.fieldId, attribute.inputType)
            selectionStart = attribute.initialSelStart
            selectionEnd = attribute.initialSelEnd
            composingStart = -1
            composingEnd = -1
            compositionKnown = false
            controller.editor.start(EditorState(identity = null))
            refreshEditor(EditorProbeTrigger.INPUT_START)
            settleEditor()
        }

        override fun onFinishInput() {
            // Avoid the base method's composing-text cleanup in the host keyboard.
            settleJob?.cancel()
            settleJob = null
            settledInput = null
            settleContentChanged = false
            info = null
            selectionStart = -1
            selectionEnd = -1
            composingStart = -1
            composingEnd = -1
            compositionKnown = false
            controller.editor.finish()
            refreshEditor(EditorProbeTrigger.INPUT_FINISH)
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
            refreshEditor(EditorProbeTrigger.SELECTION)
        }
    }

    companion object {
        internal fun isPassword(inputType: Int): Boolean {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return (inputClass == InputType.TYPE_CLASS_TEXT &&
                variation in
                    setOf(
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    )) ||
                (inputClass == InputType.TYPE_CLASS_NUMBER &&
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }
}
