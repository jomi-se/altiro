package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorVisibilityDiagnosticsTest {
    @Test
    fun `leaving input and opening console preserves external failure details`() {
        val trace = EditorVisibilityDiagnostics()
        val failure =
            EditorVisibilitySnapshot(
                EditorVisibilityReason.NO_FOCUS_NODE,
                input = EditorInputKind.TEXT,
                connection = true,
                focusedApp = true,
                focusedAppMatches = true,
            )
        trace.record(failure, EditorProbeTrigger.INPUT_START, true)
        trace.record(
            EditorVisibilitySnapshot(EditorVisibilityReason.NO_INPUT_START),
            EditorProbeTrigger.INPUT_FINISH,
            false,
        )
        trace.record(
            EditorVisibilitySnapshot(EditorVisibilityReason.NO_INPUT_START),
            EditorProbeTrigger.STATE,
            false,
        )
        assertEquals(failure, trace.report.value.lastExternal)
        assertTrue(trace.report.value.export().contains("Last external input: NO_FOCUS_NODE"))
        assertTrue(trace.report.value.export().contains("INPUT_FINISH"))
    }

    @Test
    fun `identical refreshes are deduplicated and event memory is bounded`() {
        var now = 0L
        val trace = EditorVisibilityDiagnostics { now }
        val snapshot = EditorVisibilitySnapshot(EditorVisibilityReason.ELIGIBLE)
        repeat(100) {
            now += 1_000_000
            trace.record(snapshot, EditorProbeTrigger.STATE, true)
        }
        assertEquals(1, trace.report.value.events.size)
        repeat(100) {
            now += 1_000_000
            trace.record(
                snapshot.copy(connection = it % 2 == 0),
                EditorProbeTrigger.SELECTION,
                true,
            )
        }
        assertEquals(48, trace.report.value.events.size)
        assertEquals(153L, trace.report.value.events.first().elapsedMillis)
        assertEquals(200L, trace.report.value.events.last().elapsedMillis)
    }

    @Test
    fun `clear removes retained editor diagnostics and resets relative clock`() {
        var now = 0L
        val trace = EditorVisibilityDiagnostics { now }
        trace.record(
            EditorVisibilitySnapshot(
                EditorVisibilityReason.NOT_EDITABLE,
                input = EditorInputKind.RAW,
            ),
            EditorProbeTrigger.INPUT_START,
            true,
        )
        now = 3_000_000_000
        trace.clear()
        assertNull(trace.report.value.lastExternal)
        assertTrue(trace.report.value.events.isEmpty())
        trace.record(
            EditorVisibilitySnapshot(EditorVisibilityReason.DISCONNECTED),
            EditorProbeTrigger.DISCONNECT,
            false,
        )
        assertEquals(0L, trace.report.value.events.single().elapsedMillis)
        assertFalse(trace.report.value.export().contains("RAW"))
    }
}
