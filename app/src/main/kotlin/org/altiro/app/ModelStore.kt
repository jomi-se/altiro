package org.altiro.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.ModelSpec
import org.altiro.core.RecognitionInput
import org.altiro.core.VerifiedModel
import org.altiro.network.DownloadCancellation
import org.altiro.network.DownloadFailure
import org.altiro.network.DownloadOutcome
import org.altiro.network.ModelDownloader
import org.altiro.network.ModelSourcePolicy
import org.json.JSONObject

data class ModelProfile(
    val spec: ModelSpec,
    val name: String,
    val filename: String,
    val sourceUrl: String,
    val description: String,
    val experimental: Boolean,
    /** Null when the catalog has no pinned binary; such a profile is import-only. */
    val download: java.net.URI? = null,
    val licenseUrl: String = "",
    val attribution: String = "",
) {
    val downloadHost: String?
        get() = download?.host
}

enum class AcquisitionPhase {
    CONNECTING,
    DOWNLOADING,
    INSTALLED,
    CANCELLED,
    FAILED,
}

/** The latest explicit download request; content-free and safe to show or export. */
data class ModelAcquisition(
    val modelId: String,
    val phase: AcquisitionPhase,
    val percent: Int = 0,
    val failure: DownloadFailure? = null,
)

data class ModelCheckTiming(
    val modelId: String,
    val elapsedMillis: Long,
    val verified: Boolean,
)

class ModelStore(private val context: Context) {
    val profiles: List<ModelProfile> =
        JSONObject(
                context.assets.open("whisper-models.json").bufferedReader().use { it.readText() }
            )
            .getJSONArray("models")
            .let { models ->
                List(models.length()) { index ->
                    val item = models.getJSONObject(index)
                    ModelProfile(
                        ModelSpec(
                            item.getString("id"),
                            item.getLong("bytes"),
                            item.getString("sha256"),
                        ),
                        item.getString("displayName"),
                        item.getString("filename"),
                        item.getString("sourceUrl"),
                        item.getString("description"),
                        item.getBoolean("experimental"),
                        ModelSourcePolicy.directDownload(
                            item.getString("sourceUrl"),
                            item.getString("filename"),
                        ),
                        item.getString("licenseUrl"),
                        item.getString("attribution"),
                    )
                }
            }
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val initialId =
        preferences.getString("model", null)
            ?: if (context.filesDir.resolve("models/ggml-base.bin").isFile)
                "whisper-base-multilingual"
            else SMALL_Q8
    private val mutableSelected =
        MutableStateFlow(
            profiles.firstOrNull { it.spec.id == initialId }
                ?: profiles.first { it.spec.id == SMALL_Q8 }
        )
    val selected = mutableSelected.asStateFlow()
    private val mutableInstalled = MutableStateFlow<Set<String>>(emptySet())
    val installed = mutableInstalled.asStateFlow()
    val startupChecks = MutableStateFlow<List<ModelCheckTiming>>(emptyList())
    val startupMillis = MutableStateFlow<Long?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableBusy = MutableStateFlow(true)
    val busy = mutableBusy.asStateFlow()
    private val mutableStatus = MutableStateFlow("Checking installed models…")
    val status = mutableStatus.asStateFlow()
    private val mutableAcquisition = MutableStateFlow<ModelAcquisition?>(null)
    val acquisition = mutableAcquisition.asStateFlow()
    private val downloader = ModelDownloader()
    private var activeDownload: DownloadCancellation? = null

    init {
        worker.execute {
            val startupStarted = System.nanoTime()
            var timings = emptyList<ModelCheckTiming>()
            context.filesDir
                .resolve("models")
                .apply { mkdirs() }
                .listFiles()
                ?.filter { it.name.endsWith(".part") }
                ?.forEach { it.delete() }
            val valid =
                profiles
                    .filter {
                        val exists = file(it).isFile
                        val started = System.nanoTime()
                        if (exists) main.post { mutableStatus.value = "Checking ${it.name}…" }
                        val verified =
                            runCatching {
                                    VerifiedModel.matches(
                                        file(it),
                                        it.spec,
                                    )
                                }
                                .getOrDefault(false)
                        if (exists) {
                            timings =
                                timings +
                                    ModelCheckTiming(
                                        it.spec.id,
                                        (System.nanoTime() - started) / 1_000_000,
                                        verified,
                                    )
                            val snapshot = timings
                            main.post { startupChecks.value = snapshot }
                        }
                        verified
                    }
                    .map { it.spec.id }
                    .toSet()
            main.post {
                startupMillis.value = (System.nanoTime() - startupStarted) / 1_000_000
                mutableInstalled.value = valid
                updateReady()
                mutableBusy.value = false
                mutableStatus.value = "Select a model below. Installed models are ready offline."
            }
        }
    }

    fun file(profile: ModelProfile): java.io.File =
        context.filesDir.resolve("models/${profile.filename}")

    fun snapshot(profile: ModelProfile): RecognitionInput =
        RecognitionInput(profile.spec, file(profile))

    fun select(id: String) {
        checkMain()
        if (busy.value) return
        mutableSelected.value = profiles.first { it.spec.id == id }
        preferences.edit().putString("model", id).apply()
        updateReady()
    }

    fun importModel(
        uri: Uri,
        id: String,
    ) {
        checkMain()
        if (busy.value) return
        val profile = profiles.firstOrNull { it.spec.id == id } ?: return
        mutableBusy.value = true
        cancelled.set(false)
        mutableStatus.value = "Importing ${profile.name}…"
        worker.execute {
            var result = "Import failed. Choose ${profile.filename} and check free storage."
            var imported = false
            try {
                check(context.filesDir.usableSpace >= profile.spec.bytes + 10_000_000) {
                    "Insufficient storage"
                }
                val source = context.contentResolver.openInputStream(uri) ?: throw IOException()
                source.use {
                    VerifiedModel.install(it, file(profile), profile.spec, cancelled::get) { percent
                        ->
                        main.post { mutableStatus.value = "Importing ${profile.name} · $percent%" }
                    }
                }
                imported = true
                result = "${profile.name} installed and verified."
            } catch (_: CancellationException) {
                result = "Import cancelled. Previously installed models were preserved."
            } catch (_: Exception) {
                // No provider URI, model contents, or private path enters diagnostics.
            }
            main.post {
                if (imported) mutableInstalled.value = installed.value + id
                updateReady()
                mutableStatus.value = result
                mutableBusy.value = false
            }
        }
    }

    /**
     * Explicit user action only; never called from launch or recognition. Serialized with import,
     * deletion and startup checks on the model worker. Replacing an installed model is refused.
     */
    fun download(id: String) {
        checkMain()
        if (busy.value) return
        val profile = profiles.firstOrNull { it.spec.id == id } ?: return
        val source = profile.download
        if (source == null) {
            mutableAcquisition.value =
                ModelAcquisition(
                    id,
                    AcquisitionPhase.FAILED,
                    failure = DownloadFailure.NOT_DOWNLOADABLE,
                )
            mutableStatus.value =
                "${profile.name} has no verified download yet. Import ${profile.filename} instead."
            return
        }
        if (id in installed.value) {
            mutableStatus.value = "${profile.name} is already installed and verified."
            return
        }
        val cancellation = DownloadCancellation()
        activeDownload = cancellation
        mutableBusy.value = true
        mutableAcquisition.value = ModelAcquisition(id, AcquisitionPhase.CONNECTING)
        mutableStatus.value = "Connecting to ${source.host}…"
        worker.execute {
            val outcome =
                downloader.install(source, file(profile), profile.spec, cancellation) { percent ->
                    main.post {
                        if (activeDownload === cancellation) {
                            mutableAcquisition.value =
                                ModelAcquisition(id, AcquisitionPhase.DOWNLOADING, percent)
                            mutableStatus.value = "Downloading ${profile.name} · $percent%"
                        }
                    }
                }
            main.post {
                activeDownload = null
                val previous = acquisition.value?.percent ?: 0
                mutableAcquisition.value =
                    when (outcome) {
                        DownloadOutcome.Installed ->
                            ModelAcquisition(id, AcquisitionPhase.INSTALLED, 100)
                        DownloadOutcome.Cancelled ->
                            ModelAcquisition(id, AcquisitionPhase.CANCELLED, previous)
                        is DownloadOutcome.Failed ->
                            ModelAcquisition(id, AcquisitionPhase.FAILED, previous, outcome.reason)
                    }
                if (outcome == DownloadOutcome.Installed)
                    mutableInstalled.value = installed.value + id
                updateReady()
                mutableStatus.value =
                    when (outcome) {
                        DownloadOutcome.Installed ->
                            "${profile.name} downloaded and verified. Ready offline."
                        DownloadOutcome.Cancelled ->
                            "Download cancelled. Previously installed models were preserved."
                        is DownloadOutcome.Failed -> failureMessage(profile, outcome.reason)
                    }
                mutableBusy.value = false
            }
        }
    }

    /** Cancels the active import or download. */
    fun cancelImport() {
        cancelled.set(true)
        activeDownload?.cancel()
    }

    /** The controller blocks deletion while capture or native work owns any model. */
    fun delete() {
        checkMain()
        if (busy.value) return
        val profile = selected.value
        mutableBusy.value = true
        worker.execute {
            val deleted = !file(profile).exists() || file(profile).delete()
            main.post {
                if (deleted) mutableInstalled.value = installed.value - profile.spec.id
                updateReady()
                mutableStatus.value =
                    if (deleted) "${profile.name} deleted."
                    else "Could not delete the model. Try again."
                mutableBusy.value = false
            }
        }
    }

    private fun failureMessage(
        profile: ModelProfile,
        failure: DownloadFailure,
    ): String =
        when (failure) {
            DownloadFailure.NOT_DOWNLOADABLE ->
                "${profile.name} has no verified download yet. Import ${profile.filename} instead."
            DownloadFailure.OFFLINE ->
                "No connection. Connect and try again; installed models still work offline."
            DownloadFailure.TIMEOUT -> "The download stalled. Check the connection and try again."
            DownloadFailure.SECURE_CONNECTION ->
                "A secure connection failed. Check the network (or a sign-in page) and the date."
            DownloadFailure.UNTRUSTED_REDIRECT,
            DownloadFailure.TOO_MANY_REDIRECTS ->
                "The host redirected to an unreviewed location. Nothing was installed."
            DownloadFailure.UNAVAILABLE ->
                "The pinned file is no longer available. Import ${profile.filename} instead."
            DownloadFailure.SERVER_BUSY -> "The model host is busy. Try again later."
            DownloadFailure.REFUSED -> "The model host refused the download. Try again later."
            DownloadFailure.VERIFICATION_FAILED ->
                "The download did not match the verified model and was discarded."
            DownloadFailure.INSUFFICIENT_STORAGE ->
                "Not enough storage. ${profile.name} needs " +
                    "${(profile.spec.bytes + ModelDownloader.STORAGE_MARGIN + 999_999) / 1_000_000} MB free."
            DownloadFailure.INTERRUPTED ->
                "The download was interrupted. Nothing was installed; try again."
        }

    private fun updateReady() {
        mutableReady.value = selected.value.spec.id in installed.value
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    companion object {
        const val SMALL_Q8 = "whisper-small-q8"
        const val SMALL_FP16 = "whisper-small-fp16"
    }
}
