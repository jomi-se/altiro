package org.altiro.core

import org.junit.Assert.*
import org.junit.Test

class AutomaticDeliveryTest {
    private val host =
        RecordingHostEvidence(
            EditorInputKind.TEXT,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            false,
        )
    private val textEditor =
        EditorState(
            EditorIdentity("org.example.editor", 1, 0, "input-method", 0),
            selectionStart = 2,
            selectionEnd = 2,
            compositionKnown = true,
            connectionAvailable = true,
        )

    @Test
    fun `connected custom input remains guarded without a named accessibility field`() {
        assertTrue(supportsConnectedTextDestination(host))
        assertFalse(supportsConnectedTextDestination(host.copy(connection = false)))
        assertFalse(supportsConnectedTextDestination(host.copy(nodeMatches = false)))
        assertFalse(supportsConnectedTextDestination(host.copy(input = EditorInputKind.RAW)))
        val authority = EditorAuthority().apply { start(textEditor) }
        val token = authority.capture()
        assertNull(authority.blockReason(token))
        authority.start(textEditor) // Same window, field id and metadata, different input epoch.
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
    }

    @Test
    fun `paste route does not survive composition or route changes after capture`() {
        val authority =
            EditorAuthority().apply { start(textEditor.copy(delivery = TextDelivery.PASTE)) }
        val token = authority.capture()
        authority.observe(authority.current.copy(composingStart = 0, composingEnd = 2))
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        authority.observe(textEditor)
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
    }

    @Test
    fun `paste into empty or existing text does not wait for keyboard composition callbacks`() {
        for (state in
            listOf(
                textEditor.copy(selectionStart = 0, selectionEnd = 0, compositionKnown = false),
                textEditor.copy(selectionStart = 24, selectionEnd = 24, compositionKnown = false),
                textEditor.copy(selectionStart = -1, selectionEnd = -1, compositionKnown = false),
                textEditor.copy(composingStart = 0, composingEnd = 2),
                textEditor.copy(selectionStart = 0, selectionEnd = 2),
            )) {
            val authority =
                EditorAuthority().apply { start(state.copy(delivery = TextDelivery.PASTE)) }
            val token = authority.capture()
            assertNull(authority.blockReason(token))
            authority.observe(authority.current.copy(selectionStart = 10, selectionEnd = 10))
            assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        }
    }

    @Test
    fun `terminal paste uses separate semantics but never survives a changed terminal`() {
        val state =
            textEditor.copy(
                selectionStart = -1,
                selectionEnd = -1,
                compositionKnown = false,
                delivery = TextDelivery.TERMUX_PASTE,
            )
        val authority = EditorAuthority().apply { start(state) }
        val token = authority.capture()
        assertNull(authority.blockReason(token))
        authority.finish()
        authority.start(state)
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        authority.observe(state.copy(locked = true))
        assertEquals(InsertionBlockReason.LOCKED, authority.blockReason(authority.capture()))
        authority.observe(state.copy(password = true))
        assertEquals(InsertionBlockReason.PASSWORD, authority.blockReason(authority.capture()))
    }

    @Test
    fun `late composition callback without a range change does not invalidate paste`() {
        val state = textEditor.copy(compositionKnown = false, delivery = TextDelivery.PASTE)
        val authority = EditorAuthority().apply { start(state) }
        val token = authority.capture()
        authority.observe(state.copy(compositionKnown = true))
        assertTrue(authority.current.compositionKnown)
        assertNull(authority.blockReason(token))
        authority.observe(authority.current.copy(composingStart = 0, composingEnd = 2))
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
        authority.observe(state.copy(compositionKnown = true))
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(token))
    }

    @Test
    fun `terminal payload preserves unicode and never carries enter or control codes`() {
        assertEquals(
            "café mañana 😀 next line",
            terminalPasteText("café mañana 😀\nnext\tline\r\u001b\u007f\u0085"),
        )
        assertEquals("", terminalPasteText("\n\r\u0000\u001b\u009f"))
        assertEquals("one two three", terminalPasteText("one\u2028two\u2029three"))
    }
}
