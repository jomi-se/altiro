package org.altiro.core

import org.junit.Assert.*
import org.junit.Test

class TranscribePsyDiagnosticsTest {
    @Test
    fun fourPassIdentityAndRuntimeStaySeparateFromWhisperSettings() {
        val ids =
            listOf(
                "transcribepsy-q8",
                "transcribepsy-q8",
                "transcribepsy-fp16",
                "transcribepsy-fp16",
            )
        val processors =
            listOf(
                RecognitionBackend.CPU,
                RecognitionBackend.VULKAN,
                RecognitionBackend.CPU,
                RecognitionBackend.VULKAN,
            )
        val trace = RecognitionDiagnostics()
        trace.begin(
            "auto",
            ids,
            true,
            processors,
            windows = ids.map { RecognitionWindow.NATIVE },
            engines = ids.map { RecognitionEngine.TRANSCRIBEPSY },
        )
        ids.indices.forEach { index ->
            trace.runtime(
                RuntimeDetails(
                    ids[index],
                    processors[index],
                    status = RuntimeStatus.FINISHED,
                    window = RecognitionWindow.NATIVE,
                    engine = RecognitionEngine.TRANSCRIBEPSY,
                    gpuActive = processors[index] == RecognitionBackend.VULKAN,
                    nativeBackend =
                        if (processors[index] == RecognitionBackend.CPU) "CPU" else "Vulkan0",
                )
            )
        }
        val report = trace.report.value!!
        assertEquals(4, report.runtimes.size)
        val exported = report.export()
        assertTrue(exported.contains("TranscribePsy"))
        assertTrue(exported.contains("automatic language"))
        assertFalse(exported.contains("Runtime: whisper.cpp"))
        assertFalse(exported.contains("Requested Flash Attention"))
        assertFalse(exported.contains("GPU backend initialized in Whisper"))
    }

    @Test
    fun nativeDiagnosticsRejectArbitraryPathsAndUnboundedCounters() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDetails(
                "transcribepsy-q8",
                RecognitionBackend.CPU,
                nativeBackend = "/private/path",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDetails("transcribepsy-q8", RecognitionBackend.CPU, graphChecks = Int.MAX_VALUE)
        }
        ModelSpec("transcribepsy-fp16", 569_843_744, "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            ModelSpec("unbounded", 1_073_741_825, "a".repeat(64))
        }
    }
}
