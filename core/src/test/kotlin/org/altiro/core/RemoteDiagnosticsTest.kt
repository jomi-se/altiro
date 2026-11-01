package org.altiro.core

import org.junit.Assert.*
import org.junit.Test

class RemoteDiagnosticsTest {
    @Test
    fun remoteTraceDoesNotClaimTheConfiguredFallbackWasExecuted() {
        val trace = RecognitionDiagnostics()
        trace.begin(
            "es",
            listOf("transcribepsy-q8"),
            false,
            engines = listOf(RecognitionEngine.TRANSCRIBEPSY),
        )
        trace.route(RecognitionRoute.CHECKING)
        trace.remoteProbe(123)
        trace.route(RecognitionRoute.REMOTE_GPU)
        trace.phase(RecognitionStage.REMOTE_REQUEST)
        trace.finish(DiagnosticOutcome.COMPLETED)
        val report = trace.report.value!!
        val exported = report.export()
        assertTrue(exported.contains("Configured local fallback models: transcribepsy-q8"))
        assertTrue(exported.contains("Remote model: large-v3-turbo"))
        assertFalse(exported.contains("cold load"))
        assertFalse(exported.contains("4 CPU threads"))
        assertEquals(123L, report.remoteProbeMillis)
        trace.route(RecognitionRoute.LOCAL, "remote_timeout")
        assertEquals(report, trace.report.value)
    }

    @Test
    fun localFallbackExportsOnlyBoundedReasonAndActualRouteHistory() {
        val trace = RecognitionDiagnostics()
        trace.begin("en", listOf("model-a"), false)
        trace.route(RecognitionRoute.CHECKING)
        trace.route(RecognitionRoute.REMOTE_GPU)
        trace.route(RecognitionRoute.LOCAL, "remote_timeout")
        trace.route(RecognitionRoute.LOCAL)
        assertEquals(
            listOf(RecognitionRoute.CHECKING, RecognitionRoute.REMOTE_GPU, RecognitionRoute.LOCAL),
            trace.report.value!!.routes,
        )
        assertTrue(trace.report.value!!.export().contains("Remote fallback reason: remote_timeout"))
        assertThrows(IllegalArgumentException::class.java) {
            trace.route(RecognitionRoute.LOCAL, "https://speech.example.net/secret")
        }
        assertThrows(IllegalArgumentException::class.java) { trace.remoteProbe(60_001) }
        trace.begin("auto", listOf("model-a"), false)
        assertTrue(trace.report.value!!.routes.isEmpty())
        assertNull(trace.report.value!!.remoteFailure)
        assertNull(trace.report.value!!.remoteProbeMillis)
    }
}
