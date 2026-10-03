package org.altiro.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.ModelSpec
import org.altiro.core.VerifiedModel
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ModelStore(
    private val context: Context,
) {
    private val manifest =
        JSONObject(
            context.assets
                .open("whisper-model.json")
                .bufferedReader()
                .use { it.readText() },
        )
    val spec = ModelSpec(manifest.getString("id"), manifest.getLong("bytes"), manifest.getString("sha256"))
    val sourceUrl: String = manifest.getString("sourceUrl")
    val file = context.filesDir.resolve("models/ggml-base.bin")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableBusy = MutableStateFlow(true)
    val busy = mutableBusy.asStateFlow()
    private val mutableStatus = MutableStateFlow("Checking installed model…")
    val status = mutableStatus.asStateFlow()

    init {
        worker.execute {
            file.parentFile
                ?.apply { mkdirs() }
                ?.listFiles()
                ?.filter { it.name.endsWith(".part") }
                ?.forEach { it.delete() }
            val valid = runCatching { VerifiedModel.matches(file, spec) }.getOrDefault(false)
            main.post {
                mutableReady.value = valid
                mutableBusy.value = false
                mutableStatus.value =
                    if (valid) "Whisper base · multilingual · ready offline" else "Import the multilingual base model to begin."
            }
        }
    }

    fun importModel(uri: Uri) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy.value) return
        mutableBusy.value = true
        cancelled.set(false)
        mutableStatus.value = "Importing and verifying model…"
        worker.execute {
            var result = "Model import failed. Choose the supported ggml-base.bin file and check free storage."
            var imported = false
            try {
                check(context.filesDir.usableSpace >= spec.bytes + 10_000_000) { "Insufficient storage" }
                val source = context.contentResolver.openInputStream(uri) ?: throw IOException()
                source.use {
                    VerifiedModel.install(it, file, spec, cancelled::get) { percent ->
                        main.post { mutableStatus.value = "Importing and verifying · $percent%" }
                    }
                }
                imported = true
                result = "Whisper base · multilingual · ready offline"
            } catch (_: CancellationException) {
                result = "Import cancelled. The previous model was preserved."
            } catch (_: Exception) {
                // No provider URI, model contents, or private path enters diagnostics.
            }
            main.post {
                if (imported) mutableReady.value = true
                mutableStatus.value = result
                mutableBusy.value = false
            }
        }
    }

    fun cancelImport() {
        cancelled.set(true)
    }

    /** The controller only permits this once capture and native work are finished. */
    fun delete() {
        if (busy.value) return
        mutableBusy.value = true
        worker.execute {
            val deleted = !file.exists() || file.delete()
            main.post {
                if (deleted) mutableReady.value = false
                mutableStatus.value = if (deleted) "Model deleted. Import a model to begin." else "Could not delete the model. Try again."
                mutableBusy.value = false
            }
        }
    }
}
