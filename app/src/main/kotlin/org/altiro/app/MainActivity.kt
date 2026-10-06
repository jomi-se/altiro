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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.altiro.core.Phase
import org.altiro.core.SessionEvent

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
                var settings by rememberSaveable { mutableStateOf(false) }
                val session by controller.session.collectAsState()
                val nativeBusy by controller.recognition.busy.collectAsState()
                KeepAwake(session.busy || nativeBusy)
                BackHandler(page != 0 || settings) {
                    page = 0
                    settings = false
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
                                        Glyph.CONSOLE to "Console",
                                    )
                                    .withIndex()) {
                                NavigationBarItem(
                                    selected = page == index && !settings,
                                    onClick = {
                                        page = index
                                        settings = false
                                    },
                                    icon = { AltiroIcon(item.first) },
                                    label = { Text(item.second) },
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
                    val pageScroll = key(page, settings) { rememberScrollState() }
                    Column(
                        Modifier.fillMaxSize()
                            .padding(insets)
                            .verticalScroll(pageScroll)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (settings) "Setup"
                                else listOf("Altiro", "Models", "Console")[page],
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                            )
                            IconButton(
                                onClick = { settings = !settings },
                                modifier =
                                    Modifier.semantics {
                                        contentDescription =
                                            if (settings) "Close setup" else "Open setup"
                                    },
                            ) {
                                AltiroIcon(if (settings) Glyph.CLOSE else Glyph.SETTINGS)
                            }
                        }
                        when {
                            settings ->
                                SetupContent(
                                    controller,
                                    microphoneAllowed,
                                    ::requestPermission,
                                    ::openAccessibility,
                                    ::openPermissionSettings,
                                )
                            page == 0 ->
                                HomeContent(
                                    controller,
                                    microphoneAllowed,
                                    ::startRecording,
                                    { page = 1 },
                                    { settings = true },
                                )
                            page == 1 ->
                                ModelsContent(controller) { id ->
                                    pendingImportId = id
                                    modelImport.launch(arrayOf("*/*"))
                                }
                            else -> {
                                ConsoleContent(controller, microphoneAllowed)
                                var experiments by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick = { experiments = !experiments }) {
                                    Text(
                                        if (experiments) "Close experiments"
                                        else "Runtime & comparisons"
                                    )
                                }
                                if (experiments) {
                                    LanguageControls(controller)
                                    GpuControls(controller, microphoneAllowed) { first ->
                                        launchComparison("compare-backends", "gpu-first", first)
                                    }
                                    WindowControls(controller, microphoneAllowed) { first ->
                                        launchComparison("compare-windows", "dynamic-first", first)
                                    }
                                    ComparisonControls(controller, microphoneAllowed) { ids ->
                                        startActivity(
                                            Intent(this@MainActivity, RecordingActivity::class.java)
                                                .putStringArrayListExtra(
                                                    "compare-models",
                                                    ArrayList(ids),
                                                )
                                        )
                                    }
                                    ResultControls(controller)
                                }
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
                recording -> "Recording · ${session.elapsedSeconds}s"
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
    var more by rememberSaveable { mutableStateOf(false) }
    val available = !session.busy && !nativeBusy && !modelBusy
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in
            listOf("en" to "EN", "es" to "ES") +
                if (more || language in setOf("auto", "fr")) listOf("auto" to "Auto", "fr" to "FR")
                else emptyList()) {
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
        if (!more && language !in setOf("auto", "fr"))
            IconButton(
                onClick = { more = true },
                modifier = Modifier.semantics { contentDescription = "More languages" },
            ) {
                AltiroIcon(Glyph.GLOBE)
            }
    }
}

@Composable
private fun SetupContent(
    controller: DictationController,
    microphoneAllowed: Boolean,
    request: () -> Unit,
    accessibility: () -> Unit,
    permissions: () -> Unit,
) {
    val connected by controller.connected.collectAsState()
    Text("Your keyboard stays yours.", style = MaterialTheme.typography.titleLarge)
    Text(
        "Microphone access records only when you start. Accessibility adds the floating mic and inserts at the selected cursor; it does not collect screen or clipboard contents."
    )
    SetupStep(
        "Microphone",
        microphoneAllowed,
        if (microphoneAllowed) "Allowed" else "Record when you tap the mic",
        request,
    )
    if (!microphoneAllowed)
        TextButton(onClick = permissions) { Text("Android permission settings") }
    SetupStep(
        "Floating mic",
        connected,
        if (connected) "Connected" else "Enable Altiro in Accessibility settings",
        accessibility,
    )
    Text(
        "Model downloads contact the listed host only when you choose Download. Recording and recognition stay on this phone. Audio is deleted after recognition or cancellation; results are temporary.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val context = LocalContext.current
    val preferences = remember { RecordingPreferences(context) }
    var inPlace by remember { mutableStateOf(preferences.recordInPlace) }
    Row(
        Modifier.fillMaxWidth().toggleable(inPlace, role = Role.Switch) {
            inPlace = it
            preferences.recordInPlace = it
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Record in place", Modifier.weight(1f))
        Switch(inPlace, onCheckedChange = null)
    }
    Text(
        "If Android blocks recording from the overlay, start from Altiro, then return to your editor.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = controller::clearDisabledApps) { Text("Restore mic in hidden apps") }
}

@Composable
private fun SetupStep(title: String, done: Boolean, detail: String, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AltiroIcon(
            if (done) Glyph.CHECK else Glyph.ARROW,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
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
    Text(
        "One model runs at a time. Keep several installed to switch instantly.",
        style = MaterialTheme.typography.bodyMedium,
    )
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
    Text(selected.description)
    Text(selected.attribution, style = MaterialTheme.typography.bodySmall)
    Text(
        "Needs ${(selected.spec.bytes+10_999_999)/1_000_000} MB free. Source: ${Uri.parse(selected.sourceUrl).host}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (selected.experimental)
        Text(
            "Chilean fine-tune by Roberto Castro-Vexler. Import the audited converted file; a direct download is not published yet. Its training label does not establish better accuracy.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
}

@Composable
internal fun ResultControls(controller: DictationController) {
    val session by controller.session.collectAsState()
    val progress by controller.progress.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val modelName by controller.activeModelName.collectAsState()
    val results by controller.lastRun.collectAsState()
    val comparing by controller.comparing.collectAsState()
    val diagnostic by controller.diagnostics.report.collectAsState()
    val context = LocalContext.current
    if (session.phase == org.altiro.core.Phase.TRANSCRIBING) {
        Text("$modelName · ${(diagnostic?.processingMillis ?: 0) / 1000}s after Stop")
        Text(controller.processingLabel())
        diagnostic?.steps?.lastOrNull { it.running }?.backend?.let { Text(it.label) }
        val trace = diagnostic
        if (trace?.comparison == true) {
            val step = trace.steps.lastOrNull { it.modelId != null }
            val index =
                trace.modelIds.zip(trace.backends).indexOf(step?.modelId to step?.backend) + 1
            Text(
                "Comparison · pass ${index.coerceAtLeast(1)} of ${trace.modelIds.size}, running sequentially"
            )
        }
        Text("The microphone is released.")
        if (diagnostic?.steps?.lastOrNull()?.stage == org.altiro.core.RecognitionStage.INFERENCE) {
            LinearProgressIndicator(progress = { progress / 100f })
        } else {
            LinearProgressIndicator()
        }
        OutlinedButton(onClick = controller::cancel) { Text("Cancel recognition") }
    } else if (nativeBusy) {
        Text("Cancellation requested · ${controller.processingLabel()}")
    }
    if (diagnostic != null) {
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }
        ) {
            Text("View phase timings")
        }
    }
    if (comparing && results.isNotEmpty()) {
        Text(
            "Same recording · ${results.size} passes",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Times include verification, cold model loading and recognition. Models run one after another; these are not accuracy scores."
        )
        for (result in results) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        controller.models.profiles.first { it.spec.id == result.modelId }.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${"%.1f".format(java.util.Locale.ROOT, result.elapsedMillis / 1000.0)} seconds"
                    )
                    Text(
                        result.backend.label +
                            if (result.flashAttention) " · Flash Attention" else ""
                    )
                    Text(result.window.label)
                    Text(result.text.ifBlank { "No speech recognized." })
                    OutlinedButton(
                        onClick = { controller.copyText(result.text) },
                        enabled = result.text.isNotBlank(),
                    ) {
                        Text("Copy this result")
                    }
                }
            }
        }
        OutlinedButton(onClick = controller::discard) { Text("Clear comparison") }
    }
    if (!comparing) {
        session.text?.let { text ->
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
            results.firstOrNull()?.let {
                Text(
                    "$modelName · ${"%.1f".format(java.util.Locale.ROOT, it.elapsedMillis / 1000.0)} seconds"
                )
            }
            Text(text)
            session.message?.let { Text(it) }
            Text(
                "Return to your editor for Insert. Copy changes the clipboard only when you tap it."
            )
            OutlinedButton(onClick = controller::copy) { Text("Copy result") }
            OutlinedButton(onClick = controller::discard) { Text("Discard result") }
        }
    }
    if (session.text == null && !(comparing && results.isNotEmpty()))
        session.message?.let { Text(it) }
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun GpuControls(
    controller: DictationController,
    microphoneAllowed: Boolean,
    compare: (Boolean) -> Unit,
) {
    val backend by controller.backend.collectAsState()
    val flashAttention by controller.flashAttention.collectAsState()
    val session by controller.session.collectAsState()
    val busy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val ready by controller.models.ready.collectAsState()
    val selected by controller.models.selected.collectAsState()
    val available = !session.busy && !busy && !modelBusy
    var gpuFirst by remember { mutableStateOf(false) }
    Text("Recognition processor", style = MaterialTheme.typography.titleMedium)
    Text(
        "Selected: ${backend.label}. CPU is the reference; GPU speed and compatibility need testing on your phone."
    )
    for (option in org.altiro.core.RecognitionBackend.entries) {
        Row(
            Modifier.fillMaxWidth().selectable(
                backend == option,
                enabled = available,
                role = Role.RadioButton,
            ) {
                controller.selectBackend(option)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = backend == option, onClick = null, enabled = available)
            Text(option.label)
        }
    }
    Row(
        Modifier.fillMaxWidth().toggleable(
            flashAttention,
            enabled = available,
            role = Role.Checkbox,
        ) {
            controller.selectFlashAttention(it)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = flashAttention, onCheckedChange = null, enabled = available)
        Text("Flash Attention for GPU (experimental)")
    }
    Text(
        "Applies to GPU runs, including comparison. Turn it off to return to the previous attention method."
    )
    Text("GPU failures are reported in diagnostics. The app does not silently retry on CPU.")
    Text(
        "CPU/GPU comparison uses ${selected.name} twice with the same recording and language. Nothing is inserted automatically."
    )
    Row(
        Modifier.fillMaxWidth().toggleable(gpuFirst, enabled = available, role = Role.Checkbox) {
            gpuFirst = it
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = gpuFirst, onCheckedChange = null, enabled = available)
        Text("Run GPU first (reverse the comparison order)")
    }
    Button(
        onClick = { compare(gpuFirst) },
        enabled = available && ready && microphoneAllowed && session.text == null,
    ) {
        Text("Compare CPU and GPU")
    }
}

@Composable
internal fun WindowControls(
    controller: DictationController,
    microphoneAllowed: Boolean,
    compare: (Boolean) -> Unit,
) {
    val dynamic by controller.dynamicWindow.collectAsState()
    val session by controller.session.collectAsState()
    val busy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val ready by controller.models.ready.collectAsState()
    var dynamicFirst by remember { mutableStateOf(false) }
    val available = !session.busy && !busy && !modelBusy
    Text("Audio window", style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().toggleable(dynamic, enabled = available, role = Role.Checkbox) {
            controller.selectDynamicWindow(it)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = dynamic, onCheckedChange = null, enabled = available)
        Text("Dynamic window for short recordings (experimental)")
    }
    Text(
        "Short recordings use a smaller window with padding. Recordings of 30 seconds or more keep full windows. This may affect accuracy; turn it off to use the full window."
    )
    Text(
        "Compare one short recording using both windows. Your model, processor and Flash Attention setting stay the same. Choose EN or ES to measure without automatic language detection's extra full window."
    )
    Row(
        Modifier.fillMaxWidth().toggleable(
            dynamicFirst,
            enabled = available,
            role = Role.Checkbox,
        ) {
            dynamicFirst = it
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = dynamicFirst, onCheckedChange = null, enabled = available)
        Text("Run dynamic window first")
    }
    Button(
        onClick = { compare(dynamicFirst) },
        enabled = available && ready && microphoneAllowed && session.text == null,
    ) {
        Text("Compare full and dynamic windows")
    }
}

@Composable
internal fun ComparisonControls(
    controller: DictationController,
    microphoneAllowed: Boolean,
    open: (List<String>) -> Unit,
) {
    val installed by controller.models.installed.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val experimental = controller.models.profiles.filter { it.experimental }
    var extraIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val available = !session.busy && !nativeBusy && !modelBusy && session.text == null
    val ids =
        listOf(ModelStore.SMALL_Q8, ModelStore.SMALL_FP16) +
            experimental.filter { it.spec.id in extraIds }.map { it.spec.id }
    Text("Compare one recording", style = MaterialTheme.typography.titleMedium)
    Text(
        "Record once, then compare Small Q8 and FP16 on exactly the same audio. Try 10–20 seconds of natural speech. Results stay in memory for ten minutes."
    )
    for (profile in experimental) {
        val checked = profile.spec.id in extraIds
        Row(
            Modifier.fillMaxWidth().toggleable(checked, enabled = available, role = Role.Checkbox) {
                extraIds = if (it) extraIds + profile.spec.id else extraIds - profile.spec.id
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = available)
            Text("Also compare ${profile.name}", Modifier.weight(1f))
        }
    }
    if (ids.any { it !in installed })
        Text("Install both Small models and any checked Chilean models first.")
    Button(
        onClick = { open(ids) },
        enabled = available && microphoneAllowed && ids.all { it in installed },
    ) {
        Text("Record a comparison")
    }
    if (session.text != null) Text("Discard the previous result before starting another recording.")
}
