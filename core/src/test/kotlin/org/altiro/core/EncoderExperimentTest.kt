package org.altiro.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EncoderExperimentTest {
    private fun input(id: String, backend: RecognitionBackend) =
        RecognitionInput(
            ModelSpec(id, 1, "0".repeat(64)),
            File("unused-$id.gguf"),
            backend = backend,
            window = RecognitionWindow.NATIVE,
            engine = RecognitionEngine.TRANSCRIBEPSY,
        )

    private fun trace(inputs: List<RecognitionInput>) =
        RecognitionDiagnostics().apply {
            begin(
                "auto",
                inputs.map { it.spec.id },
                true,
                inputs.map { it.backend },
                windows = inputs.map { it.window },
                engines = inputs.map { it.engine },
            )
        }

    @Test
    fun fullTwoFormatSuiteRetainsEveryWorkerLifecycleAndAllElapsedTime() {
        for (reverse in listOf(false, true)) {
            var nowMillis = 0L
            val inputs =
                listOf("transcribepsy-q8", "transcribepsy-fp16").map {
                    input(it, RecognitionBackend.CPU)
                }
            val registered = EncoderExperiment.diagnosticInputs(inputs)
            val diagnostics = RecognitionDiagnostics { nowMillis * 1_000_000 }
            diagnostics.begin(
                "auto",
                registered.map { it.spec.id },
                true,
                registered.map { it.backend },
                windows = registered.map { it.window },
                engines = registered.map { it.engine },
                maxPhases = EncoderExperiment.DIAGNOSTIC_PHASE_CAPACITY,
            )
            nowMillis = 100
            diagnostics.phase(RecognitionStage.FINALIZING)
            for (model in inputs) for (case in EncoderExperiment.suite(true, true, reverse)) {
                diagnostics.selectBackend(case.backend, model.window)
                diagnostics.runtime(
                    RuntimeDetails(
                        model.spec.id,
                        case.backend,
                        window = model.window,
                        engine = model.engine,
                    )
                )
                for (stage in
                    listOf(
                        RecognitionStage.WORKER_START,
                        RecognitionStage.AUDIO_READ,
                        RecognitionStage.GPU_PROBE,
                        RecognitionStage.MODEL_LOAD,
                        RecognitionStage.INFERENCE,
                        RecognitionStage.TEXT_ASSEMBLY,
                        RecognitionStage.MODEL_RELEASE,
                        RecognitionStage.WORKER_STOP,
                    )) {
                    nowMillis++
                    diagnostics.phase(stage, model.spec.id)
                    // Native repetitions can announce the same phase again.
                    if (stage == RecognitionStage.INFERENCE)
                        repeat(4) {
                            nowMillis++
                            diagnostics.phase(stage, model.spec.id)
                        }
                }
            }
            diagnostics.selectBackend(null)
            nowMillis++
            diagnostics.phase(RecognitionStage.AUDIO_DELETE)
            nowMillis++
            diagnostics.phase(RecognitionStage.WORKER_RELEASE)
            nowMillis++
            diagnostics.finish(DiagnosticOutcome.COMPLETED)
            val report = diagnostics.report.value!!
            assertTrue(report.steps.size > 64)
            assertEquals(40, report.steps.count { it.stage == RecognitionStage.WORKER_START })
            assertEquals(40, report.steps.count { it.stage == RecognitionStage.WORKER_STOP })
            assertEquals(nowMillis, report.elapsedMillis)
            assertEquals(nowMillis - 100, report.processingMillis)
            assertEquals(DiagnosticOutcome.COMPLETED, report.outcome)
        }
    }

    @Test
    fun labDiagnosticsAdmitEveryCaseForEitherSavedBackendAndBothFormats() {
        for (saved in RecognitionBackend.entries) for (count in 1..2) {
            val selected =
                listOf("transcribepsy-q8", "transcribepsy-fp16").take(count).map {
                    input(it, saved)
                }
            val registered = EncoderExperiment.diagnosticInputs(selected)
            assertEquals(count * 2, registered.size)
            val diagnostics = trace(registered)
            for (reverse in listOf(false, true)) for (model in selected) {
                for (case in EncoderExperiment.suite(true, true, reverse)) {
                    // NativeCall publishes this record before it binds the worker.
                    diagnostics.runtime(
                        RuntimeDetails(
                            model.spec.id,
                            case.backend,
                            window = model.window,
                            engine = model.engine,
                        )
                    )
                }
            }
            assertEquals(count * 2, diagnostics.report.value!!.runtimes.size)
            assertTrue(selected.all { it.backend == saved })
        }
    }

    @Test
    fun selectedBackendAloneReproducesRejectionAndLabPlanKeepsIdentityChecks() {
        val selected = listOf(input("transcribepsy-q8", RecognitionBackend.CPU))
        val gpu =
            RuntimeDetails(
                "transcribepsy-q8",
                RecognitionBackend.VULKAN,
                window = RecognitionWindow.NATIVE,
                engine = RecognitionEngine.TRANSCRIBEPSY,
            )
        assertThrows(IllegalArgumentException::class.java) { trace(selected).runtime(gpu) }
        val diagnostics = trace(EncoderExperiment.diagnosticInputs(selected))
        diagnostics.runtime(gpu)
        assertThrows(IllegalArgumentException::class.java) {
            diagnostics.runtime(gpu.copy(modelId = "unknown-model"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            diagnostics.runtime(gpu.copy(window = RecognitionWindow.FULL))
        }
    }

    @Test
    fun nativeOptionsAreBoundedAndRejectCommands() {
        for (invalid in
            listOf(
                "",
                "0:0",
                "9:0",
                "4:17",
                "4:19",
                "4:37",
                "4:39",
                "4:47",
                "4:-1",
                "4:0:1",
                "env=X",
                "1:2;anything",
            )) {
            assertNull(invalid, EncoderExperiment.options(invalid))
        }
        assertEquals(4 to 0, EncoderExperiment.options("4:0"))
    }

    @Test
    fun acceleratorSuiteFiltersUnsupportedFormatsAndRetainsMatchedControls() {
        for (profile in listOf(false, true)) {
            val forward = EncoderExperiment.suite(false, profile, accelerators = true)
            val reverse =
                EncoderExperiment.suite(false, profile, reverse = true, accelerators = true)
            val first = forward.indexOfFirst { EncoderExperiment.isAcl(it.variant) }
            val last = forward.indexOfLast { EncoderExperiment.isInt8(it.variant) }
            assertEquals(forward.take(first), reverse.take(first))
            assertEquals(forward.drop(last + 1), reverse.drop(last + 1))
            assertEquals(
                forward.subList(first, last + 1).reversed(),
                reverse.subList(first, last + 1),
            )
            val q8 = forward.filter { it.appliesTo("transcribepsy-q8") }
            val f16 = forward.filter { it.appliesTo("transcribepsy-fp16") }
            assertEquals(if (profile) 16 else 15, q8.size)
            assertEquals(if (profile) 13 else 12, f16.size)
            assertEquals(
                setOf(80, 81, 82),
                q8.filter { EncoderExperiment.isInt8(it.variant) }.map { it.variant }.toSet(),
            )
            assertFalse(f16.any { EncoderExperiment.isInt8(it.variant) })
            assertEquals(
                setOf(90, 91, 92),
                f16.filter { EncoderExperiment.isAcl(it.variant) }.map { it.variant }.toSet(),
            )
            assertTrue(
                forward
                    .filter { EncoderExperiment.isAcl(it.variant) }
                    .all {
                        it.candidateBackend == "OPENCL" && it.backend == RecognitionBackend.VULKAN
                    }
            )
            assertTrue(q8.size + f16.size <= 64)
            assertTrue((q8.size + f16.size) * 12 < EncoderExperiment.DIAGNOSTIC_PHASE_CAPACITY)
            assertTrue(forward.all { EncoderExperiment.options(it.wire) != null })
            assertFalse(forward.any { EncoderExperiment.isKernel(it.variant) })
        }
        for (variant in listOf(79, 83, 89, 93)) assertNull(EncoderExperiment.options("4:$variant"))
    }

    @Test
    fun packedSuitePreservesControlsAndBoundsDistinctMeasurementFamilies() {
        for (profile in listOf(false, true)) {
            val forward = EncoderExperiment.suite(false, profile, packed = true)
            val reverse = EncoderExperiment.suite(false, profile, reverse = true, packed = true)
            val first = forward.indexOfFirst { EncoderExperiment.isPacked(it.variant) }
            val last = forward.indexOfLast { EncoderExperiment.isPacked(it.variant) }
            assertEquals(forward.take(first), reverse.take(first))
            assertEquals(forward.drop(last + 1), reverse.drop(last + 1))
            assertEquals(
                forward.subList(first, last + 1).reversed(),
                reverse.subList(first, last + 1),
            )
            assertEquals(
                setOf(60, 61, 62, 63),
                forward
                    .filter { EncoderExperiment.isPacked(it.variant) }
                    .map { it.variant }
                    .toSet(),
            )
            assertEquals(1, forward.count { it.variant == 70 })
            assertEquals(2, forward.count { it.variant == 30 })
            assertTrue(forward.all { EncoderExperiment.options(it.wire) != null })
            assertTrue(forward.map { it.id }.distinct().size == forward.size)
            assertTrue(forward.size * 2 <= 64)
            assertTrue(forward.size * 2 * 12 < EncoderExperiment.DIAGNOSTIC_PHASE_CAPACITY)
            assertTrue((60..63).all { !EncoderExperiment.isKernel(it) })
            assertFalse(EncoderExperiment.isKernel(70))
        }
        for (variant in listOf(48, 49, 50, 59, 64, 69, 71)) assertNull(
            EncoderExperiment.options("4:$variant")
        )
    }

    @Test
    fun tuningKeepsMatchedControlsAndReversesOnlyTreatments() {
        val forward = EncoderExperiment.suite(false, true, tuning = true)
        val reverse = EncoderExperiment.suite(false, true, reverse = true, tuning = true)
        assertTrue(forward.size * 2 <= 64)
        assertEquals(forward.size, forward.map { it.id }.distinct().size)
        assertEquals(forward.take(4), reverse.take(4))
        assertEquals(forward.takeLast(5), reverse.takeLast(5))
        assertEquals(forward.subList(4, 17).reversed(), reverse.subList(4, 17))
        assertEquals(
            (30..36).toSet(),
            forward.filter { it.variant in 30..36 }.map { it.variant }.toSet(),
        )
        assertEquals(
            (40..46).toSet(),
            forward.filter { EncoderExperiment.isKernel(it.variant) }.map { it.variant }.toSet(),
        )
        assertTrue(forward.all { EncoderExperiment.options(it.wire) != null })
        assertTrue(
            forward.filter { it.variant >= 30 }.all { it.backend == RecognitionBackend.VULKAN }
        )
        assertFalse(EncoderExperiment.isKernel(30))
        assertFalse(EncoderExperiment.isKernel(36))
        assertEquals(8, EncoderExperiment.kernelCount(40))
        assertEquals(32, EncoderExperiment.kernelCount(20))
        assertTrue(forward.size * 2 * 12 < EncoderExperiment.DIAGNOSTIC_PHASE_CAPACITY)
    }

    @Test
    fun kernelHuntKeepsProcessBudgetBaselinesAndCacheDependency() {
        for (reverse in listOf(false, true)) for (profile in listOf(false, true)) {
            val cases = EncoderExperiment.suite(false, profile, reverse, hunt = true)
            assertTrue(cases.size * 2 <= 64)
            assertEquals(cases.size, cases.map { it.id }.distinct().size)
            assertEquals("cpu-4-start", cases.first().id)
            assertEquals("cpu-4-end", cases.filterNot { it.profiled }.last().id)
            assertTrue(
                cases.indexOfFirst { it.id == "gpu-cache-empty" } <
                    cases.indexOfFirst { it.id == "gpu-cache-seeded" }
            )
            assertEquals(
                (20..29).toSet(),
                cases.filter { it.variant >= 20 }.map { it.variant }.toSet(),
            )
            assertTrue(cases.all { EncoderExperiment.options(it.wire) != null })
            assertTrue(
                cases
                    .filter { it.backend == RecognitionBackend.CPU }
                    .all { it.variant in listOf(0, 27) }
            )
        }
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
