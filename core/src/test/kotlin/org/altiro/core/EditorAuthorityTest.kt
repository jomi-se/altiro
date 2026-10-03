package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorAuthorityTest {
    private val editor =
        EditorState(EditorIdentity("org.example.editor", 2, 3, "field", 0), 4, 4, compositionKnown = true, connectionAvailable = true)

    @Test fun `cursor returning to original position never revives authority`() {
        val authority = EditorAuthority().apply { start(editor) }
        val token = authority.capture()
        authority.observe(editor.copy(selectionStart = 5, selectionEnd = 5))
        authority.observe(editor)
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        assertNull(authority.blockReason(authority.capture()))
    }

    @Test fun `same length text changes invalidate even when metadata is identical`() {
        val authority = EditorAuthority().apply { start(editor) }
        val token = authority.capture()
        authority.invalidate()
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
    }

    @Test fun `restart reconnect and field switch invalidate original token`() {
        for (operation in listOf<
            (
                EditorAuthority,
            ) -> Unit,
        >({ it.start(editor) }, {
            it.reconnect()
            it.start(editor)
        }, { it.observe(editor.copy(identity = editor.identity!!.copy(fieldId = 99))) })) {
            val authority = EditorAuthority().apply { start(editor) }
            val token = authority.capture()
            operation(authority)
            assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        }
    }

    @Test fun `password composition locks and ambiguous metadata block even explicit insertion`() {
        val cases =
            listOf(
                editor.copy(password = true) to InsertionBlockReason.PASSWORD,
                editor.copy(composingStart = 0, composingEnd = 2) to InsertionBlockReason.COMPOSING,
                editor.copy(compositionKnown = false) to InsertionBlockReason.UNKNOWN_COMPOSITION,
                editor.copy(locked = true) to InsertionBlockReason.LOCKED,
                editor.copy(blocked = true) to InsertionBlockReason.DISABLED_APP,
                editor.copy(identity = null) to InsertionBlockReason.NO_EDITOR,
                editor.copy(connectionAvailable = false) to InsertionBlockReason.NO_EDITOR,
                editor.copy(selectionStart = -1) to InsertionBlockReason.UNKNOWN_SELECTION,
                editor.copy(identity = editor.identity!!.copy(displayId = 1)) to InsertionBlockReason.UNSUPPORTED_DISPLAY,
            )
        for ((state, expected) in cases) {
            val authority = EditorAuthority().apply { start(state) }
            assertEquals(expected, authority.blockReason(authority.capture()))
        }
    }
}
