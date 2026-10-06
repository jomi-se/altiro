package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDiagnosticsTest {
    @Test
    fun liveAndFinalTimingsUseMonotonicTimeAndExcludeRecordingFromProcessing() {
        var now = 0L
        val trace = RecognitionDiagnostics { now }

        fun advance(millis: Long) {
            now += millis * 1_000_000
        }
        trace.begin("es", listOf("whisper-small-q8"), false)
        advance(200)
        trace.phase(RecognitionStage.RECORDING)
        advance(10_000)
        trace.phase(RecognitionStage.FINALIZING)
        advance(100)
        trace.phase(RecognitionStage.VERIFYING, "whisper-small-q8")
        advance(500)
        trace.phase(RecognitionStage.MODEL_LOAD, "whisper-small-q8")
        advance(2500)
        trace.refresh()
        val live = trace.report.value!!
        assertEquals(2500, live.steps.last().durationMillis)
        assertTrue(live.steps.last().running)
        assertEquals(3100, live.processingMillis)
        trace.phase(RecognitionStage.INFERENCE, "whisper-small-q8")
        advance(30_000)
        trace.phase(RecognitionStage.MODEL_RELEASE, "whisper-small-q8")
        advance(50)
        trace.audioDuration(10_000)
        trace.finish(DiagnosticOutcome.COMPLETED)
        val done = trace.report.value!!
        assertEquals(33_150, done.processingMillis)
        assertEquals(43_350, done.elapsedMillis)
        assertFalse(done.steps.any { it.running })
        advance(1000)
        trace.refresh()
        assertEquals(done, trace.report.value)
        assertTrue(done.export().contains("Processing/audio ratio: 3.32"))
    }

    @Test
    fun cancellationKeepsMeasuringUntilCleanupCompletes() {
        var now = 0L
        val trace = RecognitionDiagnostics { now }
        trace.begin("en", listOf("model-a", "model-b"), true)
        trace.phase(RecognitionStage.MODEL_LOAD, "model-a")
        now = 1_000_000_000
        trace.requestCancellation()
        trace.refresh()
        assertEquals(null, trace.report.value!!.outcome)
        now = 2_000_000_000
        trace.phase(RecognitionStage.MODEL_RELEASE, "model-a")
        now = 3_000_000_000
        trace.phase(RecognitionStage.AUDIO_DELETE)
        trace.finish(DiagnosticOutcome.CANCELLED)
        val report = trace.report.value!!
        assertTrue(report.cancellationRequested)
        assertEquals(
            2000,
            report.steps.first { it.stage == RecognitionStage.MODEL_LOAD }.durationMillis,
        )
        assertEquals(
            1000,
            report.steps.first { it.stage == RecognitionStage.MODEL_RELEASE }.durationMillis,
        )
        assertFalse(report.steps.any { it.modelId == "model-b" })
    }

    @Test
    fun failureStageSurvivesCleanupAndNextRunReplacesTrace() {
        val trace = RecognitionDiagnostics()
        trace.begin("auto", listOf("model-a"), false)
        trace.phase(RecognitionStage.INFERENCE, "model-a")
        trace.markFailure()
        trace.phase(RecognitionStage.MODEL_RELEASE, "model-a")
        trace.markFailure()
        trace.phase(RecognitionStage.AUDIO_DELETE)
        trace.finish(DiagnosticOutcome.FAILED)
        assertEquals(RecognitionStage.INFERENCE, trace.report.value!!.failureStage)
        val report = trace.report.value!!
        trace.phase(RecognitionStage.INFERENCE, "model-a")
        assertEquals(report, trace.report.value)
        trace.begin("es", listOf("model-b"), false)
        assertEquals(listOf("model-b"), trace.report.value!!.modelIds)
        assertEquals(null, trace.report.value!!.failureStage)
        trace.clear()
        assertEquals(null, trace.report.value)
    }

    @Test
    fun sameModelBackendPhasesAndFailureStayDistinctAndExcludeContent() {
        var clock = 0L
        val trace = RecognitionDiagnostics { clock }
        trace.begin(
            "es",
            listOf("model-a", "model-a"),
            true,
            listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN),
        )
        trace.selectBackend(RecognitionBackend.CPU)
        trace.phase(RecognitionStage.INFERENCE, "model-a")
        clock += 1_000_000_000
        trace.selectBackend(RecognitionBackend.VULKAN)
        trace.phase(RecognitionStage.INFERENCE, "model-a")
        clock += 2_000_000_000
        trace.runtime(
            RuntimeDetails(
                "model-a",
                RecognitionBackend.VULKAN,
                RuntimeStatus.WORKER_DIED,
                RuntimeFailure.WORKER_DIED,
                gpuName = "Mali-G710",
                vulkanVersion = "1.3.0",
                storage16 = true,
            )
        )
        trace.markFailure()
        trace.phase(RecognitionStage.AUDIO_DELETE)
        trace.finish(DiagnosticOutcome.FAILED)
        val report = trace.report.value!!
        val phases = report.steps.filter { it.stage == RecognitionStage.INFERENCE }
        assertEquals(
            listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN),
            phases.map { it.backend },
        )
        assertEquals(listOf(1000L, 2000L), phases.map { it.durationMillis })
        assertTrue(report.export().contains("WORKER_DIED"))
        assertTrue(report.export().contains("Mali-G710"))
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDetails("model-a", RecognitionBackend.VULKAN, gpuName = "/private/file")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDetails("model-a", RecognitionBackend.VULKAN, encodeMillis = -1)
        }
    }
}
