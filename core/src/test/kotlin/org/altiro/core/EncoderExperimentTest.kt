package org.altiro.core

import org.junit.Assert.*
import org.junit.Test

class EncoderExperimentTest {
    @Test
    fun nativeOptionsAreBoundedAndRejectCommands() {
        for (invalid in
            listOf("", "0:0", "9:0", "4:11", "4:-1", "4:0:1", "env=X", "1:2;anything")) {
            assertNull(invalid, EncoderExperiment.options(invalid))
        }
        assertEquals(4 to 0, EncoderExperiment.options("4:0"))
    }

    @Test
    fun everySuiteBracketsVariantsWithSameBaselinesAndSeparatesProfiling() {
        for (full in listOf(false, true)) for (profile in listOf(false, true)) {
            val cases = EncoderExperiment.suite(full, profile)
            assertEquals(cases.size, cases.map { it.id }.distinct().size)
            assertEquals("cpu-4-start", cases.first().id)
            val latency = cases.filterNot { it.profiled }
            assertEquals("cpu-4-end", latency.last().id)
            assertEquals(
                listOf("gpu-start", "gpu-end"),
                latency
                    .filter { it.backend == RecognitionBackend.VULKAN && it.variant == 0 }
                    .map { it.id },
            )
            assertTrue(cases.all { EncoderExperiment.options(it.wire) != null })
            assertEquals(if (profile) 1 else 0, cases.count { it.profiled })
            assertTrue(cases.size <= 21)
        }
        assertEquals(
            (0..10).toSet() - 9,
            EncoderExperiment.suite(true, false).map { it.variant }.toSet(),
        )
    }

    @Test
    fun alternateOrderPreservesBaselinesAndCacheSeedDependency() {
        val forward = EncoderExperiment.suite(true, true)
        val reverse = EncoderExperiment.suite(true, true, true)
        assertEquals(forward.map { it.id }.toSet(), reverse.map { it.id }.toSet())
        assertEquals(forward.take(2), reverse.take(2))
        assertEquals(forward.takeLast(3), reverse.takeLast(3))
        assertEquals(
            reverse.indexOfFirst { it.id == "gpu-cache-empty" } + 1,
            reverse.indexOfFirst { it.id == "gpu-cache-seeded" },
        )
        assertTrue(forward != reverse)
    }

    @Test
    fun summaryMedianDoesNotAdmitInvalidSamples() {
        assertNull(EncoderExperiment.median(emptyList()))
        assertNull(EncoderExperiment.median(listOf(Double.NaN)))
        assertNull(EncoderExperiment.median(listOf(-1.0)))
        assertEquals(2.0, EncoderExperiment.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, EncoderExperiment.median(listOf(4.0, 2.0, 1.0, 3.0))!!, 0.0)
    }
}
