package org.altiro.core

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = "verified test model".toByteArray()
    private val spec =
        ModelSpec(
            "test",
            payload.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                "%02x".format(it)
            },
        )

    @Test
    fun validImportAndLaterCorruption() {
        val file = File(folder.root, "model.bin")
        VerifiedModel.install(ByteArrayInputStream(payload), file, spec)
        assertTrue(VerifiedModel.matches(file, spec))
        file.writeBytes(ByteArray(payload.size))
        assertFalse(VerifiedModel.matches(file, spec))
    }

    @Test
    fun failedImportPreservesPreviousModelAndCleansPartial() {
        val file = folder.newFile("model.bin").apply { writeBytes(payload) }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedModel.install(ByteArrayInputStream(ByteArray(payload.size)), file, spec)
        }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(listOf("model.bin"), folder.root.list()?.toList())
    }

    @Test
    fun shortAndOversizedSourcesNeverInstall() {
        val file = File(folder.root, "model.bin")
        for (bytes in listOf(payload.dropLast(1).toByteArray(), payload + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedModel.install(ByteArrayInputStream(bytes), file, spec)
            }
            assertFalse(file.exists())
            assertTrue(folder.root.list().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationBeforeRenameDoesNotPublishModel() {
        val file = File(folder.root, "model.bin")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            VerifiedModel.install(
                ByteArrayInputStream(payload),
                file,
                spec,
                { cancelled },
                { cancelled = true },
            )
        }
        assertFalse(file.exists())
        assertTrue(folder.root.list().orEmpty().isEmpty())
    }
}
