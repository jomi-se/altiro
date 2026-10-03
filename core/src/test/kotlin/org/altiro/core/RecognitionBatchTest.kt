package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class RecognitionBatchTest {
    @get:Rule val folder = TemporaryFolder()

    private fun input(id: String): RecognitionInput {
        val file = folder.newFile("$id.bin").apply { writeText(id) }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return RecognitionInput(ModelSpec(id, file.length(), sha), file)
    }

    @Test fun sameAudioIsAvailableToBothModelsThenDeleted() {
        val audio = folder.newFile("recording.wav").apply { writeText("same recording") }
        val inputs = listOf(input("q8"), input("fp16"))
        val seen = mutableListOf<String>()
        var clock = 0L
        val results =
            RecognitionBatch.run(audio, inputs, { false }, { model ->
                assertEquals("same recording", audio.readText())
                seen += model.spec.id
                " result ${model.spec.id} "
            }, { clock.also { clock += 1_000_000_000 } })
        assertEquals(listOf("q8", "fp16"), seen)
        assertEquals(listOf("result q8", "result fp16"), results.map { it.text })
        assertTrue(results.all { it.elapsedMillis == 1000L })
        assertFalse(audio.exists())
        assertTrue(inputs.all { it.file.exists() })
    }

    @Test fun cancellationAfterFirstModelPreventsSecondAndDeletesAudio() {
        val audio = folder.newFile("recording.wav")
        var cancelled = false
        val seen = mutableListOf<String>()
        val inputs = listOf(input("q8"), input("fp16"))
        assertThrows(CancellationException::class.java) {
            RecognitionBatch.run(audio, inputs, { cancelled }, { model ->
                seen += model.spec.id
                cancelled = true
                "must not publish"
            })
        }
        assertEquals(listOf("q8"), seen)
        assertFalse(audio.exists())
    }

    @Test fun corruptedSecondModelPreventsItsNativeLoadAndDeletesAudio() {
        val audio = folder.newFile("recording.wav")
        val inputs = listOf(input("q8"), input("fp16"))
        inputs[1].file.writeText("xxxx")
        val seen = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            RecognitionBatch.run(audio, inputs, { false }, { model ->
                seen += model.spec.id
                "text"
            })
        }
        assertEquals(listOf("q8"), seen)
        assertFalse(audio.exists())
    }

    @Test fun nativeFailureAndInvalidBatchBothDeleteAudio() {
        val model = input("q8")
        for (inputs in listOf(listOf(model), listOf(model, model))) {
            val audio = File(folder.root, "recording.wav").apply { writeText("audio") }
            assertThrows(RuntimeException::class.java) {
                RecognitionBatch.run(audio, inputs, { false }, { throw IllegalStateException("runtime failed") })
            }
            assertFalse(audio.exists())
        }
    }
}
