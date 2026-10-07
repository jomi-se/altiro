package org.altiro.core

/** Fixed metadata only. Missing accessibility focus grants recording, never insertion. */
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
