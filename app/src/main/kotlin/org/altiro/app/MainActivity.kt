package org.altiro.app

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.altiro.core.Phase
import org.altiro.core.SessionEvent
import org.altiro.core.Vocabulary
import org.altiro.core.VocabularyError
import org.altiro.core.VocabularyValidation
import org.altiro.core.Wav

class MainActivity : ComponentActivity() {
    private val controller
        get() = (application as AltiroApplication).controller

    private var microphoneAllowed by mutableStateOf(false)
    private var pendingImportId: String? = null
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermission()
        }
    private val modelImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pendingImportId
            pendingImportId = null
            if (uri != null && id != null) controller.importModel(uri, id)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportId = savedInstanceState?.getString("pending-import-model")
        refreshPermission()
        setContent {
            AltiroTheme {
                var page by rememberSaveable { mutableIntStateOf(0) }
                val session by controller.session.collectAsState()
                val nativeBusy by controller.recognition.busy.collectAsState()
                KeepAwake(session.busy || nativeBusy)
                BackHandler(page != 0) {
                    page = 0
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 0.dp,
                        ) {
                            for ((index, item) in
                                listOf(
                                        Glyph.HOME to "Home",
                                        Glyph.MODEL to "Models",
                                        Glyph.SETTINGS to "Settings",
                                        Glyph.CONSOLE to "Console",
                                    )
                                    .withIndex()) {
                                NavigationBarItem(
                                    selected = page == index,
                                    onClick = {
                                        page = index
                                    },
                                    icon = { AltiroIcon(item.first) },
                                    label = { Text(item.second) },
                                    alwaysShowLabel = false,
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            indicatorColor =
                                                MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                )
                            }
                        }
                    },
                ) { insets ->
                    val pageScroll = key(page) { rememberScrollState() }
                    Column(
                        Modifier.fillMaxSize()
                            .padding(insets)
                            .consumeWindowInsets(insets)
                            .imePadding()
                            .verticalScroll(pageScroll)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (page == 0) 20.dp else 8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                listOf("Altiro", "Models", "Settings", "Console")[page],
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                            )
                        }
                        when {
                            page == 0 ->
                                HomeContent(
                                    controller,
                                    microphoneAllowed,
                                    ::startRecording,
                                    { page = 1 },
                                    { page = 2 },
                                )
                            page == 1 ->
                                ModelsContent(controller) { id ->
                                    pendingImportId = id
                                    modelImport.launch(arrayOf("*/*"))
                                }
                            page == 2 ->
                                SettingsContent(
                                    controller,
                                    microphoneAllowed,
                                    ::requestPermission,
                                    ::openAccessibility,
                                    ::openPermissionSettings,
                                )
                            else -> {
                                ConsoleContent(controller, microphoneAllowed)
                                if (controller.comparing.collectAsState().value)
                                    ResultControls(controller)
                                ComparisonTools(
                                    controller,
                                    microphoneAllowed,
                                    { first ->
                                        launchComparison("compare-backends", "gpu-first", first)
                                    },
                                    { first ->
                                        launchComparison("compare-windows", "dynamic-first", first)
                                    },
                                    { ids ->
                                        startActivity(
                                            Intent(this@MainActivity, RecordingActivity::class.java)
                                                .putStringArrayListExtra(
                                                    "compare-models",
                                                    ArrayList(ids),
                                                )
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-import-model", pendingImportId)
        super.onSaveInstanceState(outState)
    }

    private fun refreshPermission() {
        microphoneAllowed =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun launchComparison(key: String, order: String, first: Boolean) {
        startActivity(
            Intent(this, RecordingActivity::class.java).putExtra(key, true).putExtra(order, first)
        )
    }

    private fun startRecording() {
        if (!microphoneAllowed) {
            requestPermission()
            return
        }
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val id = controller.begin(explicit = true) ?: return
        try {
            startForegroundService(
                DictationRecordingService.intent(this, DictationRecordingService.START, id)
            )
        } catch (_: ForegroundServiceStartNotAllowedException) {
            controller.event(
                SessionEvent.Fail(id, "Android blocked recording. Keep Altiro open and try again.")
            )
        } catch (_: SecurityException) {
            controller.event(
                SessionEvent.Fail(id, "Allow the microphone in Android settings and try again.")
            )
        }
    }
}

@Composable
internal fun HomeContent(
    controller: DictationController,
    microphoneAllowed: Boolean,
    record: () -> Unit,
    models: () -> Unit,
    setup: () -> Unit,
) {
    val selected by controller.models.selected.collectAsState()
    val ready by controller.models.ready.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val modelStatus by controller.models.status.collectAsState()
    val connected by controller.connected.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val diagnostic by controller.diagnostics.report.collectAsState()
    val busy = session.busy || nativeBusy
    val recording = session.phase in setOf(Phase.STARTING, Phase.RECORDING)
    val pending = session.text != null && !session.attemptConsumed
    val interrupted by controller.checkpoint.interrupted.collectAsState()
    if (interrupted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The last dictation was interrupted. Its audio and text could not be recovered. Details are in Console.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = controller.checkpoint::dismissInterruption,
                modifier =
                    Modifier.semantics { contentDescription = "Dismiss interruption notice" },
            ) {
                AltiroIcon(Glyph.CLOSE)
            }
        }
    }
    Surface(
        onClick = models,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AltiroIcon(Glyph.MODEL)
            Text(
                selected.name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f, false),
            )
            AltiroIcon(
                if (ready) Glyph.CHECK else Glyph.DOWNLOAD,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(if (ready) "Offline" else "Install", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (!microphoneAllowed || !connected || !ready) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!microphoneAllowed || !connected)
                TextButton(onClick = setup) {
                    Text(
                        "${if(!microphoneAllowed) "Allow microphone" else "Enable floating mic"} to finish setup"
                    )
                }
            if (!ready) {
                Text(
                    if (modelBusy) modelStatus
                    else "Install ${selected.name} once. Dictate offline after that.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (modelBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = controller.models::cancelImport) { Text("Cancel") }
                } else if (selected.download != null)
                    Button(
                        onClick = { controller.downloadModel(selected.spec.id) },
                        enabled = !busy,
                    ) {
                        AltiroIcon(Glyph.DOWNLOAD, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Download · ${(selected.spec.bytes+500_000)/1_000_000} MB")
                    }
                else TextButton(onClick = models) { Text("Import Chilean model") }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MicrophoneDial(
            recording = recording,
            working = busy && !recording,
            enabled = recording || (!busy && !modelBusy && ready && !pending),
            onClick = { if (recording) controller.stop() else record() },
        )
        Text(
            when {
                recording ->
                    if (session.elapsedSeconds >= Wav.MAX_SECONDS - 30)
                        "Stops at 5:00 · ${(Wav.MAX_SECONDS - session.elapsedSeconds).coerceAtLeast(0)}s left"
                    else "Recording · ${session.elapsedSeconds}s"
                busy ->
                    if (nativeBusy && !session.busy) "Cancelling…" else controller.processingLabel()
                pending -> "Text ready"
                !ready -> "Your voice, on your phone"
                else -> "Ready when you are"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (busy) {
            diagnostic
                ?.takeIf { session.phase == Phase.TRANSCRIBING }
                ?.let {
                    Text(
                        "${org.altiro.core.DiagnosticReport.seconds(it.processingMillis)} s after Stop",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            OutlinedButton(onClick = controller::cancel) {
                AltiroIcon(Glyph.CLOSE)
                Spacer(Modifier.width(8.dp))
                Text("Cancel")
            }
        }
        LanguageControls(controller)
    }
    if (session.phase == Phase.FAILED && session.text == null)
        session.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!busy)
        session.text?.let { text ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (session.attemptConsumed) "Insertion attempted. Check your editor."
                else "Return to your editor to insert, or copy here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = controller::copy) {
                    AltiroIcon(Glyph.COPY)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
                TextButton(onClick = controller::discard) { Text("Clear") }
            }
        }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = models).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AltiroIcon(Glyph.MODEL)
        Text("Chilean Small", modifier = Modifier.weight(1f))
        Text("Experimental", style = MaterialTheme.typography.labelSmall)
        AltiroIcon(Glyph.ARROW)
    }
    if (connected)
        Text(
            "The floating mic follows your editor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

@Composable
internal fun MicrophoneDial(
    recording: Boolean,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    val animate = android.animation.ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(recording, working, animate) {
        if (animate && (recording || working)) {
            while (true) {
                delay(32)
                angle = (angle + 8f) % 360f
            }
        } else angle = 0f
    }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val rule = colors.outline.copy(alpha = 0.3f)
    Box(
        Modifier.size(248.dp)
            .shadow(
                10.dp,
                CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.1f),
            )
            .clip(CircleShape)
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val r = size.minDimension / 2
            drawCircle(rule, r - 2.dp.toPx(), style = Stroke(0.7.dp.toPx()))
            drawCircle(rule.copy(alpha = 0.14f), r - 9.dp.toPx(), style = Stroke(0.7.dp.toPx()))
            for (i in 0..7) {
                val a = i * Math.PI / 4
                val inner = r - 18.dp.toPx()
                val outer = r - 26.dp.toPx()
                drawLine(
                    rule,
                    androidx.compose.ui.geometry.Offset(
                        center.x + (kotlin.math.sin(a) * inner).toFloat(),
                        center.y + (kotlin.math.cos(a) * inner).toFloat(),
                    ),
                    androidx.compose.ui.geometry.Offset(
                        center.x + (kotlin.math.sin(a) * outer).toFloat(),
                        center.y + (kotlin.math.cos(a) * outer).toFloat(),
                    ),
                    0.8.dp.toPx(),
                )
            }
            if (recording || working)
                drawArc(
                    accent,
                    angle - 90,
                    if (recording) 28f else 48f,
                    false,
                    topLeft = androidx.compose.ui.geometry.Offset(3.dp.toPx(), 3.dp.toPx()),
                    size =
                        androidx.compose.ui.geometry.Size(
                            size.width - 6.dp.toPx(),
                            size.height - 6.dp.toPx(),
                        ),
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                )
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (recording) accent else colors.onSurface,
            modifier =
                Modifier.size(116.dp).semantics {
                    contentDescription =
                        when {
                            recording -> "Stop and transcribe"
                            working -> "Microphone stopped; recognizing speech"
                            else -> "Start recording"
                        }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                AltiroIcon(
                    if (recording) Glyph.STOP else Glyph.MIC,
                    Modifier.size(46.dp),
                    color = colors.surfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LanguageControls(controller: DictationController) {
    val language by controller.language.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val available = !session.busy && !nativeBusy && !modelBusy
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in listOf("en" to "EN", "es" to "ES", "auto" to "Auto", "fr" to "FR")) {
            FilterChip(
                selected = language == value,
                onClick = { controller.selectLanguage(value) },
                label = { Text(label) },
                enabled = available,
                modifier =
                    Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Dictation language $label"
                    },
            )
        }
    }
}

@Composable
internal fun VocabularyContent(controller: DictationController) {
    val saved by controller.vocabulary.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val editable = !session.busy && !nativeBusy
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var oversizedPaste by rememberSaveable { mutableStateOf(false) }
    if (!expanded) {
        OutlinedButton(
            enabled = editable,
            onClick = {
                draft = saved.text
                oversizedPaste = false
                expanded = true
            },
        ) {
            AltiroIcon(Glyph.MODEL)
            Spacer(Modifier.width(8.dp))
            Text(if (saved.count == 0) "Names & terms" else "Names & terms · ${saved.count}")
        }
        return
    }
    val validation = remember(draft) { Vocabulary.parse(draft) }
    val validated = (validation as? VocabularyValidation.Valid)?.vocabulary
    val error =
        when {
            oversizedPaste -> "Up to 4 KiB of words. Paste a shorter list."
            validation is VocabularyValidation.Invalid ->
                when (validation.error) {
                    VocabularyError.TOO_MANY_TERMS -> "Keep up to 100 terms. Remove a few to save."
                    VocabularyError.TOO_LARGE -> "Up to 4 KiB of words. Shorten the list to save."
                    VocabularyError.INVALID_CHARACTERS ->
                        "Remove control characters or incomplete Unicode to save."
                }
            else -> null
        }
    Text("Names & terms", style = MaterialTheme.typography.titleMedium)
    Text(
        "Optional spelling hints, one per line. Saved only on this phone. Short lists work best; Whisper may use only the end of a long list. Clear and Save to turn hints off.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            if (next.length <= Vocabulary.MAX_DRAFT_CHARS) {
                draft = next
                oversizedPaste = false
            } else oversizedPaste = true
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = editable,
        label = { Text("One term per line") },
        placeholder = { Text("Kubernetes\nAltiro") },
        minLines = 4,
        maxLines = 8,
        isError = error != null,
        supportingText = {
            Text(
                error
                    ?: "${validated?.count ?: 0} / 100 terms · ${validated?.prompt?.toByteArray(Charsets.UTF_8)?.size ?: 0} / 4096 bytes"
            )
        },
    )
    if (!editable)
        Text(
            "Finish or cancel dictation to edit hints.",
            style = MaterialTheme.typography.bodySmall,
        )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                draft = ""
                oversizedPaste = false
            },
            enabled = editable,
        ) {
            Text("Clear")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { expanded = false }) { Text("Cancel") }
        Button(
            enabled = editable && error == null && validated != null,
            onClick = {
                if (validated != null && controller.saveVocabulary(validated)) expanded = false
            },
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun ModelsContent(controller: DictationController, importModel: (String) -> Unit) {
    val selected by controller.models.selected.collectAsState()
    val installed by controller.models.installed.collectAsState()
    val busy by controller.models.busy.collectAsState()
    val status by controller.models.status.collectAsState()
    val linkNotice by controller.modelLinkNotice.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val available = !busy && !session.busy && !nativeBusy
    for (profile in controller.models.profiles) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(
                    profile.spec.id == selected.spec.id,
                    enabled = available,
                    role = Role.RadioButton,
                ) {
                    controller.selectModel(profile.spec.id)
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(profile.spec.id == selected.spec.id, onClick = null, enabled = available)
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${(profile.spec.bytes+500_000)/1_000_000} MB · ${if(profile.spec.id in installed) "Verified offline" else "Not installed"}${if(profile.experimental) " · Experimental" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
    var info by rememberSaveable { mutableStateOf(false) }
    if (selected.experimental && selected.spec.id !in installed)
        Text("Experimental · verified file import only", style = MaterialTheme.typography.bodySmall)
    Text(Uri.parse(selected.sourceUrl).host.orEmpty(), style = MaterialTheme.typography.bodySmall)
    if (selected.download != null && selected.spec.id !in installed)
        Button(onClick = { controller.downloadModel(selected.spec.id) }, enabled = available) {
            AltiroIcon(Glyph.DOWNLOAD, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Download model")
        }
    OutlinedButton(onClick = { importModel(selected.spec.id) }, enabled = available) {
        Text("Import file")
    }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = controller.models::cancelImport) { Text("Cancel transfer") }
    }
    Text(status, style = MaterialTheme.typography.bodySmall)
    linkNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { info = !info }) { Text(if (info) "Less" else "Details") }
        TextButton(onClick = controller::openModelSource, enabled = available) {
            Text("Source")
        }
        TextButton(onClick = controller::openModelLicense, enabled = available) { Text("License") }
        TextButton(
            onClick = controller::deleteModel,
            enabled = available && selected.spec.id in installed,
        ) {
            Text("Delete model")
        }
    }
    if (info) {
        Text(selected.description, style = MaterialTheme.typography.bodySmall)
        Text(selected.attribution, style = MaterialTheme.typography.bodySmall)
        Text(
            "${Uri.parse(selected.sourceUrl).host} · needs ${(selected.spec.bytes+10_999_999)/1_000_000} MB free",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun ResultControls(controller: DictationController) {
    val session by controller.session.collectAsState()
    val progress by controller.progress.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val results by controller.lastRun.collectAsState()
    val comparing by controller.comparing.collectAsState()
    val diagnostic by controller.diagnostics.report.collectAsState()
    if (session.phase == Phase.TRANSCRIBING) {
        Text(controller.processingLabel(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "${(diagnostic?.processingMillis ?: 0) / 1000}s after Stop",
            style = MaterialTheme.typography.bodySmall,
        )
        if (diagnostic?.steps?.lastOrNull()?.stage == org.altiro.core.RecognitionStage.INFERENCE)
            LinearProgressIndicator(progress = { progress / 100f })
        else LinearProgressIndicator()
        OutlinedButton(onClick = controller::cancel) { Text("Cancel") }
    } else if (nativeBusy) {
        Text("Cancelling…", style = MaterialTheme.typography.bodyMedium)
    }
    if (comparing && results.isNotEmpty()) {
        for (result in results) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Text(
                controller.models.profiles.first { it.spec.id == result.modelId }.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${result.backend.label} · ${result.window.label} · ${"%.1f".format(java.util.Locale.ROOT, result.elapsedMillis / 1000.0)}s${if (result.flashAttention) " · FA" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(result.text.ifBlank { "No speech recognized." })
            TextButton(
                onClick = { controller.copyText(result.text) },
                enabled = result.text.isNotBlank(),
            ) {
                AltiroIcon(Glyph.COPY)
                Spacer(Modifier.width(8.dp))
                Text("Copy")
            }
        }
        TextButton(onClick = controller::discard) { Text("Clear comparison") }
    } else if (!comparing) {
        session.text?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::copy) { Text("Copy") }
                TextButton(onClick = controller::discard) { Text("Clear") }
            }
        }
    }
    session.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
