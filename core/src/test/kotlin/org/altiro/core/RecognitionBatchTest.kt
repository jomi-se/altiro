package org.altiro.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionBatchTest {
    @get:Rule val folder = TemporaryFolder()

    private fun input(id: String): RecognitionInput {
        val file = folder.newFile("$id.bin").apply { writeText(id) }
        val sha =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        return RecognitionInput(ModelSpec(id, file.length(), sha), file)
    }

    @Test
    fun sameAudioIsAvailableToBothModelsThenDeleted() {
        val audio = folder.newFile("recording.wav").apply { writeText("same recording") }
        val inputs = listOf(input("q8"), input("fp16"))
        val seen = mutableListOf<String>()
        var clock = 0L
        val results =
            RecognitionBatch.run(
                audio,
                inputs,
                { false },
                { model ->
                    assertEquals("same recording", audio.readText())
                    seen += model.spec.id
                    " result ${model.spec.id} "
                },
                { clock.also { clock += 1_000_000_000 } },
            )
        assertEquals(listOf("q8", "fp16"), seen)
        assertEquals(listOf("result q8", "result fp16"), results.map { it.text })
        assertTrue(results.all { it.elapsedMillis == 1000L })
        assertFalse(audio.exists())
        assertTrue(inputs.all { it.file.exists() })
    }

    @Test
    fun cancellationAfterFirstModelPreventsSecondAndDeletesAudio() {
        val audio = folder.newFile("recording.wav")
        var cancelled = false
        val seen = mutableListOf<String>()
        val inputs = listOf(input("q8"), input("fp16"))
        assertThrows(CancellationException::class.java) {
            RecognitionBatch.run(
                audio,
                inputs,
                { cancelled },
                { model ->
                    seen += model.spec.id
                    cancelled = true
                    "must not publish"
                },
            )
        }
        assertEquals(listOf("q8"), seen)
        assertFalse(audio.exists())
    }

    @Test
    fun sameModelRunsOnEachBackendWithoutAnImplicitRetry() {
        val model = input("q8")
        val audio = folder.newFile("recording.wav")
        val inputs = listOf(model, model.copy(backend = RecognitionBackend.VULKAN))
        val seen = mutableListOf<RecognitionBackend>()
        val results =
            RecognitionBatch.run(
                audio,
                inputs,
                { false },
                {
                    seen += it.backend
                    "speech"
                },
            )
        assertEquals(listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN), seen)
        assertEquals(seen, results.map { it.backend })
        assertFalse(audio.exists())

        val failedAudio = folder.newFile("gpu-failure.wav")
        seen.clear()
        assertThrows(IllegalStateException::class.java) {
            RecognitionBatch.run(
                failedAudio,
                inputs,
                { false },
                {
                    seen += it.backend
                    if (it.backend == RecognitionBackend.VULKAN)
                        throw IllegalStateException("driver failed")
                    "unpublished CPU result"
                },
            )
        }
        assertEquals(listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN), seen)
        assertFalse(failedAudio.exists())
    }

    @Test
    fun corruptedSecondModelPreventsItsNativeLoadAndDeletesAudio() {
        val audio = folder.newFile("recording.wav")
        val inputs = listOf(input("q8"), input("fp16"))
        inputs[1].file.writeText("xxxx")
        val seen = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            RecognitionBatch.run(
                audio,
                inputs,
                { false },
                { model ->
                    seen += model.spec.id
                    "text"
                },
            )
        }
        assertEquals(listOf("q8"), seen)
        assertFalse(audio.exists())
    }

    @Test
    fun sameRecordingRunsWithBothWindowsAndResultsKeepTheirIdentity() {
        val model = input("fp16").copy(backend = RecognitionBackend.VULKAN, flashAttention = true)
        val audio =
            folder.newFile("window.wav").apply { writeText("complete recording including ending") }
        val modes = listOf(RecognitionWindow.FULL, RecognitionWindow.DYNAMIC)
        val results =
            RecognitionBatch.run(
                audio,
                modes.map { model.copy(window = it) },
                { false },
                {
                    assertEquals("complete recording including ending", audio.readText())
                    assertEquals(RecognitionBackend.VULKAN, it.backend)
                    assertTrue(it.flashAttention)
                    it.window.name
                },
            )
        assertEquals(modes, results.map { it.window })
        assertEquals(modes.map { it.name }, results.map { it.text })
        assertFalse(audio.exists())
    }

    @Test
    fun nativeFailureAndInvalidBatchBothDeleteAudio() {
        val model = input("q8")
        for (inputs in listOf(listOf(model), listOf(model, model))) {
            val audio = File(folder.root, "recording.wav").apply { writeText("audio") }
            assertThrows(RuntimeException::class.java) {
                RecognitionBatch.run(
                    audio,
                    inputs,
                    { false },
                    { throw IllegalStateException("runtime failed") },
                )
            }
            assertFalse(audio.exists())
        }
    }

    @Test
    fun failedVerificationReportsItsPhaseBeforeDeletingAudioWithoutPublishingText() {
        val model = input("q8")
        model.file.writeText("bad")
        val audio = folder.newFile("recording.wav")
        val trace = RecognitionDiagnostics()
        trace.begin("es", listOf(model.spec.id), false)
        assertThrows(IllegalStateException::class.java) {
            RecognitionBatch.run(
                audio,
                listOf(model),
                { false },
                { throw AssertionError("Native must not run") },
                phase = trace::phase,
                failed = trace::markFailure,
            )
        }
        trace.finish(DiagnosticOutcome.FAILED)
        val report = trace.report.value!!
        assertEquals(RecognitionStage.VERIFYING, report.failureStage)
        assertTrue(report.steps.any { it.stage == RecognitionStage.AUDIO_DELETE })
        assertFalse(audio.exists())
        assertFalse(report.export().contains(audio.absolutePath))
        assertFalse(report.export().contains(model.file.absolutePath))
    }
}
