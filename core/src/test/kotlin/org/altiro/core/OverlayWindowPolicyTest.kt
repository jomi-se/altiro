package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowPolicyTest {
    @Test
    fun `moving our overlay preserves destination but editor changes do not`() {
        val editor =
            EditorState(
                EditorIdentity("org.example.editor", 9, 3, "field", 0),
                4,
                4,
                compositionKnown = true,
                connectionAvailable = true,
            )
        val authority = EditorAuthority().apply { start(editor) }
        val original = authority.capture()
        if (windowChangeInvalidatesDestination(42, 42)) authority.invalidate()
        assertNull(authority.blockReason(original))
        if (windowChangeInvalidatesDestination(9, 42)) authority.invalidate()
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(original))
        // Returning to the same metadata after window removal cannot revive the token.
        authority.observe(editor)
        assertEquals(InsertionBlockReason.DESTINATION_CHANGED, authority.blockReason(original))
    }

    @Test
    fun `unknown windows are never assumed to be our overlay`() {
        assertTrue(windowChangeInvalidatesDestination(-1, 42))
        assertTrue(windowChangeInvalidatesDestination(42, null))
        assertTrue(windowChangeInvalidatesDestination(-1, -1))
    }
}
