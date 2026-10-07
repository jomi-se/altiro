package org.altiro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** User-requested paste transport. No transcript, prior clip or label is logged or persisted. */
internal class PasteClipboard(context: Context, private val scope: CoroutineScope) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    fun stage(text: String): Boolean {
        val prior = runCatching { clipboard.primaryClip }.getOrNull()
        val label = "Altiro paste ${UUID.randomUUID()}"
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras =
            android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        if (runCatching { clipboard.setPrimaryClip(clip) }.isFailure) return false
        if (prior != null)
            scope.launch {
                delay(60_000)
                runCatching {
                    if (clipboard.primaryClipDescription?.label?.toString() == label) {
                        clipboard.setPrimaryClip(prior)
                    }
                }
            }
        return true
    }
}
