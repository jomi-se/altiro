package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHostPolicyTest {
    private val missingNode =
        RecordingHostEvidence(
            EditorInputKind.TEXT,
            connection = true,
            keyboardVisible = false,
            focusedAppMatches = true,
            defaultDisplay = true,
            appVisible = true,
            nodePresent = false,
            nodeMatches = false,
            password = false,
            locked = false,
            hidden = false,
        )

    @Test
    fun `withheld text input and matching raw host can record without insertion authority`() {
        for (evidence in
            listOf(
                missingNode,
                missingNode.copy(
                    input = EditorInputKind.RAW,
                    nodePresent = true,
                    nodeMatches = true,
                ),
            )) {
            assertTrue(supportsRecordingHost(evidence))
            val state =
                EditorState(
                    null,
                    connectionAvailable = true,
                    recordingHost = RecordingHost("org.example.editor", 1, 0),
                )
            assertTrue(state.recordingAvailable)
            val authority = EditorAuthority().apply { start(state) }
            assertEquals(InsertionBlockReason.NO_EDITOR, authority.blockReason(authority.capture()))
        }
    }

    @Test
    fun `no node cannot bypass connection window password visibility or display gates`() {
        val rejected =
            listOf(
                missingNode.copy(connection = false),
                missingNode.copy(focusedAppMatches = false),
                missingNode.copy(defaultDisplay = false),
                missingNode.copy(appVisible = false),
                missingNode.copy(password = true),
                missingNode.copy(locked = true),
                missingNode.copy(hidden = true),
                missingNode.copy(nodePresent = true, nodeMatches = false),
                missingNode.copy(input = EditorInputKind.NONE),
                missingNode.copy(input = EditorInputKind.NUMBER),
            )
        rejected.forEach { assertFalse(it.toString(), supportsRecordingHost(it)) }
    }

    @Test
    fun `keyboard window permits recording during late connection but never insertion`() {
        assertTrue(
            supportsRecordingHost(missingNode.copy(connection = false, keyboardVisible = true))
        )
        assertFalse(
            supportsRecordingHost(missingNode.copy(connection = false, keyboardVisible = false))
        )
        val state = EditorState(null, recordingHost = RecordingHost("org.example.editor", 1, 0))
        assertTrue(state.recordingAvailable)
        val authority = EditorAuthority().apply { start(state) }
        assertEquals(InsertionBlockReason.NO_EDITOR, authority.blockReason(authority.capture()))
    }

    @Test
    fun `a recording host cannot gain automatic insertion after a real field appears`() {
        val authority =
            EditorAuthority().apply {
                start(
                    EditorState(
                        null,
                        connectionAvailable = true,
                        recordingHost = RecordingHost("org.example.editor", 1, 0),
                    )
                )
            }
        val token = authority.capture()
        authority.start(
            EditorState(
                EditorIdentity("org.example.editor", 1, 2, "field", 0),
                selectionStart = 0,
                selectionEnd = 0,
                compositionKnown = true,
                connectionAvailable = true,
            )
        )
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        assertEquals(null, authority.blockReason(authority.capture()))
    }

    @Test
    fun `cached recording host stays unavailable after lock hide or host loss`() {
        val state =
            EditorState(
                null,
                connectionAvailable = true,
                recordingHost = RecordingHost("org.example.editor", 1, 0),
            )
        listOf(
                state.copy(locked = true),
                state.copy(password = true),
                state.copy(blocked = true),
                state.copy(recordingHost = null),
                state.copy(recordingHost = state.recordingHost!!.copy(displayId = 1)),
            )
            .forEach { assertFalse(it.recordingAvailable) }
    }
}
