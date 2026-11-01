package org.altiro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.altiro.core.DiagnosticOutcome
import org.altiro.core.EditorAuthority
import org.altiro.core.EditorVisibilityDiagnostics
import org.altiro.core.Phase
import org.altiro.core.RecognitionBackend
import org.altiro.core.RecognitionDiagnostics
import org.altiro.core.RecognitionEngine
import org.altiro.core.RecognitionInput
import org.altiro.core.RecognitionRoute
import org.altiro.core.RecognitionStage
import org.altiro.core.RecognitionWindow
import org.altiro.core.Session
import org.altiro.core.SessionEvent
import org.altiro.core.SessionId
import org.altiro.core.TimedTranscript
import org.altiro.core.Vocabulary
import org.altiro.core.VocabularyValidation
import org.altiro.core.reduce
import org.altiro.network.RemoteSpeechDecision
import org.altiro.network.RemoteSpeechEndpoint

class DictationController(private val context: Context) {
    val editor = EditorAuthority()
    private val mutableSession = MutableStateFlow(Session())
    val session = mutableSession.asStateFlow()
    val connected = MutableStateFlow(false)
    val overlayWindowEvents = MutableStateFlow(0)
    val otherWindowEvents = MutableStateFlow(0)
    val editorLabel = MutableStateFlow("No eligible field")
    // Fixed content-free reason codes only; never editor names or identifiers.
    val overlayStatus = MutableStateFlow("DISCONNECTED")
    val lastEditorStatus = MutableStateFlow("NONE")
    val deliveryStatus = MutableStateFlow("NONE")
    val editorVisibility = EditorVisibilityDiagnostics()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val models = ModelStore(context)
    val checkpoint = RuntimeCheckpoint(context)
    val recognition = LocalRecognition(context, checkpoint)
    val encoderLab = EncoderLab(context)
    val progress = MutableStateFlow(0)
    val activeModelName = MutableStateFlow("")
    val lastRun = MutableStateFlow<List<TimedTranscript>>(emptyList())
    val comparing = MutableStateFlow(false)
    val diagnostics = RecognitionDiagnostics()
    private var diagnosticGeneration = 0L
    private val remoteRecognition = RemoteRecognition(context, scope)
    val remoteEndpoint = remoteRecognition.endpoint
    val remoteEnabled = remoteRecognition.enabled
    val recognitionRoute = remoteRecognition.route
    val remoteStatus = remoteRecognition.status
    val remoteChecking = remoteRecognition.checking
    val remoteConfigured: Boolean
        get() = remoteRecognition.configured

    val canRecognize: Boolean
        get() = models.ready.value || remoteConfigured

    private fun settingsAvailable() =
        !session.value.busy && !recognition.busy.value && !models.busy.value

    fun saveRemoteEndpoint(value: String): Boolean {
        checkMain()
        if (!settingsAvailable()) return false
        return remoteRecognition.save(value).also { refreshSettings?.invoke() }
    }

    fun enableRemote(value: Boolean) {
        checkMain()
        if (!settingsAvailable()) return
        remoteRecognition.enable(value)
        refreshSettings?.invoke()
    }

    fun testRemote() {
        checkMain()
        if (settingsAvailable()) remoteRecognition.test()
    }

    fun routeLabel(): String =
        when (recognitionRoute.value) {
            RecognitionRoute.LOCAL -> "Local"
            RecognitionRoute.CHECKING -> "Checking server"
            RecognitionRoute.REMOTE_GPU -> "Remote GPU"
        }

    private data class RunPlan(
        val inputs: List<RecognitionInput>,
        val language: String,
        val labMode: Int = 0,
        val labProfile: Boolean = false,
        val remoteEndpoint: RemoteSpeechEndpoint? = null,
        val localReady: Boolean = true,
        val remoteLanguage: String = "auto",
        val remotePrompt: String = "",
    )

    private var runPlan: RunPlan? = null
    val encoderLabActive: Boolean
        get() = runPlan?.labMode != null && runPlan?.labMode != 0

    val processingSeconds = MutableStateFlow(0)
    val language =
        MutableStateFlow(
            context
                .getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getString("language", "auto") ?: "auto"
        )
    val backend =
        MutableStateFlow(
            RecognitionBackend.entries.firstOrNull {
                it.name ==
                    context
                        .getSharedPreferences("preferences", Context.MODE_PRIVATE)
                        .getString("backend", "CPU")
            } ?: RecognitionBackend.CPU
        )
    val flashAttention =
        MutableStateFlow(
            context
                .getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getBoolean("flash-attention", true)
        )
    val dynamicWindow =
        MutableStateFlow(
            context
                .getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getBoolean("dynamic-window", true)
        )
    private val mutableVocabulary =
        MutableStateFlow(
            (Vocabulary.parse(
                    context
                        .getSharedPreferences("preferences", Context.MODE_PRIVATE)
                        .getString("vocabulary", "")
                        .orEmpty()
                ) as? VocabularyValidation.Valid)
                ?.vocabulary ?: Vocabulary.EMPTY
        )
    val vocabulary = mutableVocabulary.asStateFlow()
    var insertion: (() -> Unit)? = null
    var refreshSettings: (() -> Unit)? = null
    private var generation = 0L
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val mutableHiddenApps =
        MutableStateFlow(preferences.getStringSet("disabled", emptySet()).orEmpty().toSet())
    val hiddenApps = mutableHiddenApps.asStateFlow()
    val restoreFeedback = MutableStateFlow<String?>(null)

    fun isDisabled(packageName: String): Boolean = packageName in mutableHiddenApps.value

    fun disableCurrentApp() {
        val packageName =
            editor.current.identity?.packageName
                ?: editor.current.recordingHost?.packageName
                ?: return
        val disabled = mutableHiddenApps.value + packageName
        preferences.edit().putStringSet("disabled", disabled).apply()
        mutableHiddenApps.value = disabled
        restoreFeedback.value = null
        editor.observe(editor.current.copy(blocked = true))
        editorLabel.value = "Disabled for this app"
        refreshSettings?.invoke()
    }

    fun clearDisabledApps() {
        val count = mutableHiddenApps.value.size
        preferences.edit().remove("disabled").apply()
        mutableHiddenApps.value = emptySet()
        restoreFeedback.value =
            when {
                count == 0 -> "No apps were hidden."
                !connected.value -> "Restored $count apps. Enable the floating mic to use it."
                else -> "Restored $count apps. Return to your editor."
            }
        editor.invalidate()
        refreshSettings?.invoke()
    }

    fun restoreApp(packageName: String) {
        val remaining = mutableHiddenApps.value - packageName
        preferences.edit().putStringSet("disabled", remaining).apply()
        mutableHiddenApps.value = remaining
        restoreFeedback.value = "Restored. Return to your editor."
        editor.invalidate()
        refreshSettings?.invoke()
    }

    fun begin(
        explicit: Boolean,
        compareIds: List<String>? = null,
        gpuCompare: Boolean = false,
        gpuFirst: Boolean = false,
        windowCompare: Boolean = false,
        dynamicFirst: Boolean = false,
        precisionCompare: Boolean = false,
        encoderLabMode: Int = 0,
        encoderLabBoth: Boolean = false,
        encoderLabProfile: Boolean = false,
    ): SessionId? {
        checkMain()
        if (encoderLabMode !in 0..6) return null
        if (
            encoderLabMode != 0 &&
                (!explicit ||
                    compareIds != null ||
                    gpuCompare ||
                    windowCompare ||
                    precisionCompare ||
                    models.selected.value.engine != RecognitionEngine.TRANSCRIBEPSY)
        )
            return null
        val state = session.value
        if (state.busy || state.phase in setOf(Phase.READY, Phase.AWAITING_USER)) return null
        if (models.busy.value || recognition.busy.value) return null
        if (
            compareIds != null &&
                (!explicit ||
                    compareIds.size !in 2..4 ||
                    compareIds.distinct().size != compareIds.size)
        )
            return null
        if (gpuCompare && (!explicit || compareIds != null)) return null
        if (windowCompare && (!explicit || compareIds != null || gpuCompare)) return null
        if (precisionCompare && (!explicit || compareIds != null || gpuCompare || windowCompare))
            return null
        if (windowCompare && models.selected.value.engine != RecognitionEngine.WHISPER) return null
        val profiles =
            (if (precisionCompare || (encoderLabMode != 0 && encoderLabBoth)) models.precisionIds()
                else compareIds ?: listOf(models.selected.value.spec.id))
                .map { id ->
                    models.profiles.firstOrNull { it.spec.id == id } ?: return null
                }
        val remoteTarget =
            if (
                compareIds == null &&
                    !gpuCompare &&
                    !windowCompare &&
                    !precisionCompare &&
                    encoderLabMode == 0 &&
                    remoteConfigured
            )
                RemoteSpeechEndpoint.parse(remoteEndpoint.value)
            else null
        val localReady = profiles.all {
            it.spec.id in models.installed.value && models.supported(it)
        }
        if (!localReady && remoteTarget == null) return null
        if (profiles.map { it.engine }.distinct().size != 1) return null
        val inputs =
            if (precisionCompare) {
                val order =
                    listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN).let {
                        if (gpuFirst) it.reversed() else it
                    }
                profiles.flatMap { profile ->
                    order.map { models.snapshot(profile).copy(backend = it) }
                }
            } else if (windowCompare) {
                val order =
                    listOf(RecognitionWindow.FULL, RecognitionWindow.DYNAMIC).let {
                        if (dynamicFirst) it.reversed() else it
                    }
                order.map {
                    models.snapshot(profiles.single()).copy(backend = backend.value, window = it)
                }
            } else if (gpuCompare) {
                val order =
                    listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN).let {
                        if (gpuFirst) it.reversed() else it
                    }
                order.map { models.snapshot(profiles.single()).copy(backend = it) }
            } else {
                profiles.map { models.snapshot(it).copy(backend = backend.value) }
            }
        val configuredInputs = inputs.map {
            it.copy(
                vocabulary =
                    if (it.engine == RecognitionEngine.WHISPER) vocabulary.value
                    else Vocabulary.EMPTY,
                flashAttention =
                    it.engine == RecognitionEngine.WHISPER &&
                        it.backend == RecognitionBackend.VULKAN &&
                        flashAttention.value,
                window =
                    if (it.engine == RecognitionEngine.TRANSCRIBEPSY) RecognitionWindow.NATIVE
                    else if (windowCompare) it.window
                    else if (dynamicWindow.value) RecognitionWindow.DYNAMIC
                    else RecognitionWindow.FULL,
            )
        }
        runPlan =
            RunPlan(
                configuredInputs,
                if (profiles.first().engine == RecognitionEngine.TRANSCRIBEPSY) "auto"
                else language.value,
                encoderLabMode,
                encoderLabProfile,
                remoteTarget,
                localReady,
                language.value,
                vocabulary.value.text,
            )
        comparing.value =
            encoderLabMode != 0 ||
                compareIds != null ||
                gpuCompare ||
                windowCompare ||
                precisionCompare
        lastRun.value = emptyList()
        activeModelName.value = profiles.first().name
        val id = SessionId(++generation)
        deliveryStatus.value = "PENDING"
        diagnosticGeneration = id.value
        checkpoint.beginSession()
        val diagnosticInputs =
            if (encoderLabMode != 0)
                org.altiro.core.EncoderExperiment.diagnosticInputs(configuredInputs)
            else configuredInputs
        diagnostics.begin(
            if (remoteTarget != null) language.value else runPlan!!.language,
            diagnosticInputs.map { it.spec.id },
            comparing.value,
            diagnosticInputs.map { it.backend },
            diagnosticInputs.map { it.flashAttention },
            diagnosticInputs.map { it.window },
            vocabularyTerms =
                if (remoteTarget != null) vocabulary.value.count
                else diagnosticInputs.first().vocabulary.count,
            engines = diagnosticInputs.map { it.engine },
            maxPhases =
                if (encoderLabMode != 0) org.altiro.core.EncoderExperiment.DIAGNOSTIC_PHASE_CAPACITY
                else 64,
        )
        checkpoint.save(diagnostics.report.value)
        scope.launch {
            while (
                diagnosticGeneration == id.value &&
                    diagnostics.report.value?.let { it.outcome == null } == true
            ) {
                delay(1000)
                if (diagnosticGeneration == id.value) diagnostics.refresh()
            }
            delay(600_000)
            if (diagnosticGeneration == id.value && !recognition.busy.value) diagnostics.clear()
        }
        event(SessionEvent.Start(id, if (explicit) null else editor.capture()))
        if (remoteTarget != null) remoteRecognition.prepare(id, remoteTarget, diagnostics)
        else {
            remoteRecognition.cancel()
            remoteRecognition.local(diagnostics)
        }
        return id
    }

    fun event(event: SessionEvent) {
        checkMain()
        val previous = session.value
        mutableSession.value = reduce(previous, event)
        if (previous.id != event.id || diagnosticGeneration != event.id.value) return
        if (event is SessionEvent.Cancel && !previous.attemptConsumed) {
            remoteRecognition.cancel()
            diagnostics.requestCancellation()
            if (!recognition.busy.value) diagnostics.finish(DiagnosticOutcome.CANCELLED)
            checkpoint.save(diagnostics.report.value)
        } else if (event is SessionEvent.Fail && !previous.attemptConsumed) {
            remoteRecognition.cancel()
            diagnostics.markFailure()
            if (!recognition.busy.value) diagnostics.finish(DiagnosticOutcome.FAILED)
            checkpoint.save(diagnostics.report.value)
        } else if (previous.phase != session.value.phase) {
            when (session.value.phase) {
                Phase.RECORDING -> diagnostics.phase(RecognitionStage.RECORDING)
                Phase.FINALIZING -> diagnostics.phase(RecognitionStage.FINALIZING)
                else -> Unit
            }
        }
    }

    fun transcribe(
        id: SessionId,
        audio: File,
        onProgress: (Int) -> Unit,
        complete: () -> Unit,
    ) {
        event(SessionEvent.AudioReady(id))
        if (session.value.id != id || session.value.phase != Phase.TRANSCRIBING) {
            audio.delete()
            complete()
            return
        }
        progress.value = 0
        processingSeconds.value = 0
        scope.launch {
            while (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                delay(1000)
                if (session.value.id != id || session.value.phase != Phase.TRANSCRIBING) break
                processingSeconds.value += 1
                if (processingSeconds.value >= if (encoderLabActive) 2400 else 600) {
                    recognition.cancel()
                    event(
                        SessionEvent.Fail(
                            id,
                            if (encoderLabActive)
                                "Encoder lab reached its time limit. Partial results are in Console."
                            else "Recognition exceeded ten minutes. Try a shorter recording.",
                        )
                    )
                    context.stopService(Intent(context, DictationRecordingService::class.java))
                    break
                }
            }
        }
        try {
            val plan = checkNotNull(runPlan)
            diagnostics.audioDuration(
                ((audio.length() - 44).coerceAtLeast(0) / 32).coerceAtMost(300_000)
            )
            if (plan.labMode != 0) {
                recognition.benchmark(
                    audio,
                    plan.inputs,
                    diagnostics,
                    encoderLab,
                    plan.labMode,
                    plan.labProfile,
                    { percent, case ->
                        if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                            progress.value = percent
                            activeModelName.value = case
                            onProgress(percent)
                        }
                    },
                ) { result ->
                    if (diagnosticGeneration == id.value)
                        diagnostics.finish(
                            if (diagnostics.report.value?.cancellationRequested == true)
                                DiagnosticOutcome.CANCELLED
                            else if (result.isFailure) DiagnosticOutcome.FAILED
                            else DiagnosticOutcome.COMPLETED
                        )
                    if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                        if (result.isSuccess) {
                            progress.value = 100
                            event(SessionEvent.ComparisonComplete(id))
                        } else
                            event(
                                SessionEvent.Fail(
                                    id,
                                    "Encoder lab stopped. Open Console for partial results.",
                                )
                            )
                    }
                    complete()
                }
                return
            }
            if (plan.remoteEndpoint == null) {
                startLocalRecognition(id, audio, plan, onProgress, complete)
            } else {
                activeModelName.value = "Your speech server · large-v3-turbo"
                scope.launch {
                    var handedToLocal = false
                    try {
                        val started = System.nanoTime()
                        val decision =
                            remoteRecognition.transcribe(
                                id,
                                audio,
                                plan.remoteLanguage,
                                plan.remotePrompt,
                                diagnostics,
                            )
                        if (session.value.id != id || session.value.phase != Phase.TRANSCRIBING)
                            return@launch
                        when (decision) {
                            is RemoteSpeechDecision.Transcript -> {
                                activeModelName.value = "Remote GPU · large-v3-turbo"
                                diagnostics.phase(RecognitionStage.AUDIO_DELETE)
                                audio.delete()
                                recognitionComplete(
                                    id,
                                    Result.success(
                                        listOf(
                                            TimedTranscript(
                                                "remote-large-v3-turbo",
                                                decision.text.trim(),
                                                (System.nanoTime() - started) / 1_000_000,
                                                route = RecognitionRoute.REMOTE_GPU,
                                            )
                                        )
                                    ),
                                    complete,
                                )
                            }
                            is RemoteSpeechDecision.LocalFallback -> {
                                if (!plan.localReady) {
                                    event(
                                        SessionEvent.Fail(
                                            id,
                                            "Server unavailable. Install a local fallback model in Models.",
                                        )
                                    )
                                    complete()
                                } else {
                                    startLocalRecognition(id, audio, plan, onProgress, complete)
                                    handedToLocal = true
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING)
                            event(SessionEvent.Cancel(id))
                        complete()
                    } catch (_: Exception) {
                        if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING)
                            event(
                                SessionEvent.Fail(id, "Recognition could not complete. Try again.")
                            )
                        complete()
                    } catch (_: LinkageError) {
                        if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING)
                            event(
                                SessionEvent.Fail(
                                    id,
                                    "The local runtime is unavailable. Check your speech server or choose another model.",
                                )
                            )
                        complete()
                    } finally {
                        if (!handedToLocal) audio.delete()
                    }
                }
            }
        } catch (_: Exception) {
            audio.delete()
            event(SessionEvent.Fail(id, "Local recognition could not start."))
            complete()
        } catch (_: LinkageError) {
            audio.delete()
            event(SessionEvent.Fail(id, "The native runtime is unavailable for this device."))
            complete()
        }
    }

    private fun recognitionComplete(
        id: SessionId,
        result: Result<List<TimedTranscript>>,
        complete: () -> Unit,
    ) {
        if (diagnosticGeneration == id.value) {
            diagnostics.finish(
                when {
                    session.value.phase == Phase.FAILED || result.isFailure ->
                        DiagnosticOutcome.FAILED
                    diagnostics.report.value?.cancellationRequested == true ->
                        DiagnosticOutcome.CANCELLED
                    else -> DiagnosticOutcome.COMPLETED
                }
            )
            checkpoint.save(diagnostics.report.value)
        }
        if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
            val transcripts = result.getOrNull().orEmpty()
            lastRun.value = transcripts
            val text = transcripts.firstOrNull()?.text
            scope.launch {
                delay(600_000)
                if (session.value.id == id && !session.value.busy) discard()
            }
            when {
                comparing.value && result.isSuccess && transcripts.isNotEmpty() ->
                    event(SessionEvent.ComparisonComplete(id))
                result.isFailure ->
                    event(
                        SessionEvent.Fail(
                            id,
                            "Recognition failed. Open diagnostics for the backend and failure code. Try CPU if testing GPU.",
                        )
                    )
                text.isNullOrBlank() ->
                    event(
                        SessionEvent.Fail(
                            id,
                            "No speech was recognized. Try again or choose a language.",
                        )
                    )
                else -> {
                    event(SessionEvent.Result(id, text))
                    if (session.value.destination != null && insertion != null) {
                        insertion?.invoke()
                    } else {
                        event(
                            SessionEvent.AwaitUser(
                                id,
                                if (
                                    editor.current.identity == null &&
                                        editor.current.recordingHost != null
                                )
                                    "Not inserted · tap Copy"
                                else "Text ready in Altiro.",
                            )
                        )
                    }
                }
            }
        }
        complete()
    }

    private fun startLocalRecognition(
        id: SessionId,
        audio: File,
        plan: RunPlan,
        onProgress: (Int) -> Unit,
        complete: () -> Unit,
    ) {
        remoteRecognition.local(diagnostics)
        activeModelName.value =
            models.profiles.first { it.spec.id == plan.inputs.first().spec.id }.name
        recognition.transcribe(
            audio,
            plan.inputs,
            plan.language,
            diagnostics,
            { percent, modelId ->
                if (session.value.id == id && session.value.phase == Phase.TRANSCRIBING) {
                    activeModelName.value = models.profiles.first { it.spec.id == modelId }.name
                    progress.value = percent
                    onProgress(percent)
                }
            },
        ) { result ->
            recognitionComplete(id, result, complete)
        }
    }

    fun importModel(
        uri: Uri,
        id: String,
    ) {
        if (!session.value.busy && !recognition.busy.value) models.importModel(uri, id)
    }

    fun downloadModel(id: String) {
        if (!session.value.busy && !recognition.busy.value) models.download(id)
    }

    fun deleteModel() {
        if (!session.value.busy && !recognition.busy.value) models.delete()
    }

    val modelLinkNotice = MutableStateFlow<String?>(null)

    fun openModelSource() {
        val profile = models.selected.value
        val url =
            if (profile.download != null)
                profile.sourceUrl.replace("/resolve/", "/tree/").substringBeforeLast("/")
            else profile.sourceUrl
        openModelLink(url)
    }

    fun openModelLicense() = openModelLink(models.selected.value.licenseUrl)

    private fun openModelLink(url: String) {
        modelLinkNotice.value = null
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            modelLinkNotice.value = "Install or enable a browser to open the source and license."
        }
    }

    fun selectModel(id: String) {
        if (session.value.busy || recognition.busy.value) return
        models.select(id)
        refreshSettings?.invoke()
    }

    fun recognitionLanguage(): String =
        if (
            models.selected.value.engine == RecognitionEngine.TRANSCRIBEPSY &&
                (!remoteConfigured ||
                    comparing.value ||
                    (session.value.busy && recognitionRoute.value == RecognitionRoute.LOCAL))
        )
            "auto"
        else language.value

    fun selectLanguage(value: String) {
        require(value in setOf("auto", "en", "fr", "es"))
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        language.value = value
        preferences.edit().putString("language", value).apply()
    }

    fun selectBackend(value: RecognitionBackend) {
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        backend.value = value
        preferences.edit().putString("backend", value.name).apply()
    }

    fun selectFlashAttention(value: Boolean) {
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        flashAttention.value = value
        preferences.edit().putBoolean("flash-attention", value).apply()
    }

    fun selectDynamicWindow(value: Boolean) {
        if (session.value.busy || recognition.busy.value || models.busy.value) return
        dynamicWindow.value = value
        preferences.edit().putBoolean("dynamic-window", value).apply()
    }

    fun saveVocabulary(value: Vocabulary): Boolean {
        checkMain()
        if (session.value.busy || recognition.busy.value) return false
        preferences.edit().putString("vocabulary", value.text).apply()
        mutableVocabulary.value = value
        return true
    }

    fun clearDiagnostics() {
        if (session.value.busy || recognition.busy.value) return
        diagnostics.clear()
        encoderLab.clear()
        editorVisibility.clear()
        lastEditorStatus.value = "NONE"
        checkpoint.clear()
    }

    fun stop() {
        val id = session.value.id ?: return
        if (session.value.phase !in setOf(Phase.STARTING, Phase.RECORDING)) return
        event(SessionEvent.Stop(id))
        context.startService(
            DictationRecordingService.intent(context, DictationRecordingService.STOP, id)
        )
    }

    fun cancel() {
        val id = session.value.id ?: return
        if (session.value.attemptConsumed) return
        val starting = session.value.phase == Phase.STARTING
        event(SessionEvent.Cancel(id))
        recognition.cancel()
        if (starting) {
            // Let the pending START acknowledge foreground startup before it stops itself.
            // Invalidating the session first ensures it cannot start microphone capture.
            try {
                context.startService(
                    DictationRecordingService.intent(context, DictationRecordingService.CANCEL, id)
                )
            } catch (_: IllegalStateException) {
                // If this control command is refused, the already-pending stale START drains
                // itself. Do not bring down a service that still owes startForeground().
            }
        } else context.stopService(Intent(context, DictationRecordingService::class.java))
    }

    fun discard() {
        if (session.value.busy) {
            cancel()
        } else {
            mutableSession.value = Session()
            lastRun.value = emptyList()
            runPlan = null
            comparing.value = false
        }
    }

    fun copy() {
        val text = session.value.text ?: return
        copyText(text)
    }

    fun copyText(text: String) {
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Altiro dictation", text))
    }

    fun processingLabel(): String {
        val step = diagnostics.report.value?.steps?.lastOrNull { it.running }
        val label = step?.stage?.label ?: "Preparing recognition"
        val seconds = (step?.durationMillis ?: 0) / 1000
        return if (step?.stage == RecognitionStage.INFERENCE)
            "$label · ${progress.value}% · ${seconds}s"
        else "$label · ${seconds}s"
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Session events must be serialized on main."
        }
    }
}
