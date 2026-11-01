package org.altiro.core

/** Fixed metadata only; nodeMatches checks package/window, not an unreliable host focus bit. */
data class RecordingHostEvidence(
    val input: EditorInputKind,
    val connection: Boolean,
    val keyboardVisible: Boolean,
    val focusedAppMatches: Boolean,
    val defaultDisplay: Boolean,
    val appVisible: Boolean,
    val nodePresent: Boolean,
    val nodeMatches: Boolean,
    val password: Boolean,
    val locked: Boolean,
    val hidden: Boolean,
)

fun supportsRecordingHost(evidence: RecordingHostEvidence): Boolean =
    with(evidence) {
        input in setOf(EditorInputKind.TEXT, EditorInputKind.RAW) &&
            (connection || keyboardVisible) &&
            focusedAppMatches &&
            defaultDisplay &&
            appVisible &&
            (!nodePresent || nodeMatches) &&
            !password &&
            !locked &&
            !hidden
    }

/** Input epochs and selection/composition are checked separately by EditorAuthority. */
fun supportsConnectedTextDestination(evidence: RecordingHostEvidence): Boolean =
    evidence.input == EditorInputKind.TEXT && evidence.connection && supportsRecordingHost(evidence)
