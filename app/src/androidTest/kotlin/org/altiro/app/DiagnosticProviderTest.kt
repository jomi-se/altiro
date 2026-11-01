package org.altiro.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProviderTest {
    @Test
    fun exportProviderCannotExposeRecordingsOrOtherPrivateFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.diagnostics"
        val info = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val directory = File(context.cacheDir, "diagnostic-exports").apply { mkdirs() }
        val report = File(directory, "provider-test.txt").apply { writeText("Content-free test") }
        try {
            val uri = FileProvider.getUriForFile(context, authority, report)
            assertEquals(
                "Content-free test",
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                },
            )
            for (privateFile in
                listOf(
                    File(context.cacheDir, "recording.wav"),
                    File(context.filesDir, "private.txt"),
                )) {
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, authority, privateFile)
                }
            }
        } finally {
            report.delete()
        }
    }
}
