package org.altiro.core

data class EditorIdentity(
    val packageName: String,
    val windowId: Int,
    val fieldId: Int,
    val fieldName: String?,
    val displayId: Int,
)

data class EditorState(
    val identity: EditorIdentity?,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val composingStart: Int = -1,
    val composingEnd: Int = -1,
    val compositionKnown: Boolean = false,
    val password: Boolean = false,
    val blocked: Boolean = false,
    val locked: Boolean = false,
    val connectionAvailable: Boolean = false,
) {
    val composing: Boolean get() = composingStart >= 0 || composingEnd >= 0
}

data class DestinationToken(
    val serviceEpoch: Long,
    val editorEpoch: Long,
    val revision: Long,
    val editor: EditorState,
)

enum class InsertionBlockReason {
    NO_EDITOR,
    PASSWORD,
    DISABLED_APP,
    LOCKED,
    COMPOSING,
    UNKNOWN_COMPOSITION,
    UNKNOWN_SELECTION,
    UNSUPPORTED_DISPLAY,
    DESTINATION_CHANGED,
    ALREADY_ATTEMPTED,
}

/** Main-thread owner; stores allowlisted metadata only, never editor text. */
class EditorAuthority {
    var current = EditorState(identity = null)
        private set
    private var serviceEpoch = 0L
    private var editorEpoch = 0L
    private var revision = 0L

    fun reconnect() {
        serviceEpoch++
        finish()
    }

    fun start(state: EditorState) {
        editorEpoch++
        revision++
        current = state
    }

    fun finish() {
        editorEpoch++
        revision++
        current = EditorState(identity = null)
    }

    fun observe(state: EditorState) {
        if (state != current) revision++
        current = state
    }

    fun invalidate() {
        revision++
    }

    fun capture(): DestinationToken = DestinationToken(serviceEpoch, editorEpoch, revision, current)

    fun blockReason(token: DestinationToken): InsertionBlockReason? {
        val state = current
        return when {
            state.locked -> InsertionBlockReason.LOCKED
            state.password -> InsertionBlockReason.PASSWORD
            state.blocked -> InsertionBlockReason.DISABLED_APP
            !state.connectionAvailable || state.identity == null -> InsertionBlockReason.NO_EDITOR
            state.identity.displayId != 0 -> InsertionBlockReason.UNSUPPORTED_DISPLAY
            state.composing -> InsertionBlockReason.COMPOSING
            !state.compositionKnown -> InsertionBlockReason.UNKNOWN_COMPOSITION
            state.selectionStart < 0 || state.selectionEnd < 0 -> InsertionBlockReason.UNKNOWN_SELECTION
            token != capture() -> InsertionBlockReason.DESTINATION_CHANGED
            else -> null
        }
    }
}
