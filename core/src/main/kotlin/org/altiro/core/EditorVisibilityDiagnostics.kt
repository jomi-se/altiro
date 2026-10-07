package org.altiro.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EditorVisibilityReason {
    DISCONNECTED,
    LOCKED,
    NO_INPUT_START,
    NO_FOCUS_NODE,
    NOT_EDITABLE,
    NOT_FOCUSED,
    NOT_VISIBLE,
    PACKAGE_MISMATCH,
    NO_FIELD_KEY,
    NO_WINDOW,
    WINDOW_NOT_FOCUSED,
    NOT_APP_WINDOW,
    NO_CONNECTION,
    OTHER_DISPLAY,
    PASSWORD,
    HIDDEN_APP,
    ELIGIBLE,
}

enum class EditorLookup {
    NONE,
    GLOBAL_INPUT_FOCUS,
    FOCUSED_APP_WINDOW,
}

enum class EditorWindowKind {
    NONE,
    APPLICATION,
    INPUT_METHOD,
    OVERLAY,
    SYSTEM,
    OTHER,
}

enum class EditorInputKind {
    NONE,
    RAW,
    TEXT,
    NUMBER,
    PHONE,
    OTHER,
}

enum class EditorNodeKind {
    NONE,
    EDIT_TEXT,
    COMPOSE_HOST,
    WEB,
    TERMINAL,
    OTHER,
}

enum class EditorProbeTrigger {
    CONNECT,
    DISCONNECT,
    INPUT_START,
    INPUT_FINISH,
    SELECTION,
    TEXT,
    FOCUS,
    WINDOW,
    CONTENT,
    SETTLED,
    STATE,
}

/** No text, packages, identifiers, cursor offsets or arbitrary strings can enter this trace. */
data class EditorVisibilitySnapshot(
    val reason: EditorVisibilityReason,
    val shown: Boolean = false,
    val lookup: EditorLookup = EditorLookup.NONE,
    val input: EditorInputKind = EditorInputKind.NONE,
    val connection: Boolean = false,
    val activeWindow: EditorWindowKind = EditorWindowKind.NONE,
    val focusedApp: Boolean = false,
    val focusedAppMatches: Boolean = false,
    val node: Boolean = false,
    val nodeKind: EditorNodeKind = EditorNodeKind.NONE,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val visible: Boolean = false,
    val packageMatches: Boolean = false,
    val fieldKey: Boolean = false,
    val windowFocused: Boolean = false,
) {
    fun export(): String =
        "$reason; shown=$shown; lookup=$lookup; input=$input; connection=$connection; " +
            "active=$activeWindow; focusedApp=$focusedApp; appMatches=$focusedAppMatches; " +
            "node=$node; nodeKind=$nodeKind; editable=$editable; focused=$focused; visible=$visible; " +
            "packageMatches=$packageMatches; fieldKey=$fieldKey; windowFocused=$windowFocused"
}

data class EditorVisibilityEvent(
    val elapsedMillis: Long,
    val trigger: EditorProbeTrigger,
    val snapshot: EditorVisibilitySnapshot,
)

data class EditorVisibilityReport(
    val events: List<EditorVisibilityEvent> = emptyList(),
    val lastExternal: EditorVisibilitySnapshot? = null,
) {
    fun export(): String = buildString {
        appendLine("Bubble visibility trace · content-free · latest ${events.size} transitions")
        lastExternal?.let { appendLine("Last external input: ${it.export()}") }
        for (event in events) {
            appendLine(
                "+${DiagnosticReport.seconds(event.elapsedMillis)} s · ${event.trigger} · ${event.snapshot.export()}"
            )
        }
    }
}

/** Main-thread owner. A short, in-memory transition trace also works before any recording. */
class EditorVisibilityDiagnostics(private val clockNanos: () -> Long = System::nanoTime) {
    private var started = clockNanos()
    private val mutableReport = MutableStateFlow(EditorVisibilityReport())
    val report = mutableReport.asStateFlow()

    fun record(
        snapshot: EditorVisibilitySnapshot,
        trigger: EditorProbeTrigger,
        externalInput: Boolean,
    ) {
        val previous = mutableReport.value
        val lastExternal = if (externalInput) snapshot else previous.lastExternal
        // State refreshes happen for progress/timers too; retain changes, not a polling flood.
        val last = previous.events.lastOrNull()
        if (
            last?.snapshot == snapshot &&
                last.trigger == trigger &&
                lastExternal == previous.lastExternal
        )
            return
        val event =
            EditorVisibilityEvent(
                (clockNanos() - started).coerceAtLeast(0) / 1_000_000,
                trigger,
                snapshot,
            )
        mutableReport.value =
            EditorVisibilityReport((previous.events + event).takeLast(48), lastExternal)
    }

    fun clear() {
        started = clockNanos()
        mutableReport.value = EditorVisibilityReport()
    }
}
