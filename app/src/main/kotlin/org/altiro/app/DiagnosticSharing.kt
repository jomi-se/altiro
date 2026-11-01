package org.altiro.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Only this private-cache subdirectory is exposed, and only with a user-initiated read grant. */
internal fun shareDiagnostics(context: Context, report: String) {
    val directory = File(context.cacheDir, "diagnostic-exports").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.delete() }
    val file = File(directory, "altiro-diagnostics-${UUID.randomUUID()}.txt")
    file.writeText(report)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Altiro diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
}
