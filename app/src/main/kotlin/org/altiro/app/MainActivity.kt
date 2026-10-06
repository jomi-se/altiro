package org.altiro.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var pendingImportId: String? = null
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    private val modelImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pendingImportId
            pendingImportId = null
            if (uri != null && id != null)
                (application as AltiroApplication).controller.importModel(uri, id)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportId = savedInstanceState?.getString("pending-import-model")
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-import-model", pendingImportId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val controller = (application as AltiroApplication).controller
        val allowed =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        setContent {
            AltiroTheme {
                val connected by controller.connected.collectAsState()
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Altiro", style = MaterialTheme.typography.headlineLarge)
                    Text("Your voice. Your keyboard.", style = MaterialTheme.typography.titleMedium)
                    Card {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Offline dictation preview",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Speak, stop, and get a transcript from Whisper running on your phone. Accuracy and speed still need phone testing, especially conversational Chilean Spanish."
                            )
                            Text(
                                "No account, network access, transcript history, or word allowance."
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            startActivity(
                                Intent(this@MainActivity, DiagnosticsActivity::class.java)
                            )
                        }
                    ) {
                        Text("Recognition diagnostics")
                    }
                    ModelControls(controller) { id ->
                        pendingImportId = id
                        modelImport.launch(arrayOf("*/*"))
                    }
                    GpuControls(controller, allowed) { gpuFirst ->
                        startActivity(
                            Intent(this@MainActivity, RecordingActivity::class.java)
                                .putExtra("compare-backends", true)
                                .putExtra("gpu-first", gpuFirst)
                        )
                    }
                    ComparisonControls(controller, allowed) { ids ->
                        startActivity(
                            Intent(
                                    this@MainActivity,
                                    RecordingActivity::class.java,
                                )
                                .putStringArrayListExtra("compare-models", ArrayList(ids))
                        )
                    }
                    Text(if (connected) "Floating mic connected" else "Floating mic is off")
                    RecordingModeControls(RecordingPreferences(this@MainActivity))
                    Text(
                        "Accessibility access observes the selected editor, cursor and composition, shows a small control, and inserts text. It does not collect screen or clipboard contents. Your keyboard stays selected."
                    )
                    OutlinedButton(
                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    ) {
                        Text("Open accessibility settings")
                    }
                    Text(
                        if (allowed) "Microphone permission granted"
                        else "Allow microphone access to record your voice."
                    )
                    if (!allowed) {
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName"),
                                    )
                                )
                            }
                        ) {
                            Text("Open app permission settings")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            permissions.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            )
                        }
                    ) {
                        Text("Allow microphone and notifications")
                    }
                    Button(
                        onClick = {
                            startActivity(Intent(this@MainActivity, RecordingActivity::class.java))
                        },
                        enabled = allowed,
                    ) {
                        Text("Open recording screen")
                    }
                    ResultControls(controller)
                    var sample by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it },
                        label = { Text("Try inserting here") },
                    )
                    OutlinedButton(onClick = { controller.clearDisabledApps() }) {
                        Text("Reset disabled apps")
                    }
                    Text(
                        "Audio is deleted after recognition or cancellation. Uninserted results expire after ten minutes and disappear if the process closes. The model stays installed until you delete it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingModeControls(preferences: RecordingPreferences) {
    var recordInPlace by remember { mutableStateOf(preferences.recordInPlace) }
    Row(
        modifier =
            Modifier.fillMaxWidth().toggleable(value = recordInPlace, role = Role.Switch) {
                recordInPlace = it
                preferences.recordInPlace = it
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Record without leaving your app", modifier = Modifier.weight(1f))
        Switch(checked = recordInPlace, onCheckedChange = null)
    }
    Text(
        if (recordInPlace) {
            "Tap the floating mic, speak, then tap Stop. If recording is blocked, use Open recording screen below."
        } else {
            "The floating mic opens a separate recording screen. Return to your text field and tap Insert after recognition."
        }
    )
}

@Composable
internal fun AltiroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Color(0xFF006B60),
                background = Color(0xFFF7F8F2),
                surface = Color(0xFFF7F8F2),
            )
    ) {
        Surface(Modifier.fillMaxSize(), content = content)
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
    KeepAwake(session.busy || nativeBusy)
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
                    Text(result.backend.label)
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
private fun GpuControls(
    controller: DictationController,
    microphoneAllowed: Boolean,
    compare: (Boolean) -> Unit,
) {
    val backend by controller.backend.collectAsState()
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
private fun ModelControls(
    controller: DictationController,
    importModel: (String) -> Unit,
) {
    val selected by controller.models.selected.collectAsState()
    val installed by controller.models.installed.collectAsState()
    val busy by controller.models.busy.collectAsState()
    val status by controller.models.status.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val language by controller.language.collectAsState()
    val available = !session.busy && !nativeBusy && !busy
    Text("Speech model", style = MaterialTheme.typography.titleMedium)
    Text(
        "Everyday dictation runs only the selected model. App startup checks all installed files; other models are not loaded for recognition. Switching needs no download."
    )
    for (profile in controller.models.profiles) {
        val chosen = selected.spec.id == profile.spec.id
        val sizeMb = (profile.spec.bytes + 500_000) / 1_000_000
        Row(
            Modifier.fillMaxWidth().selectable(
                chosen,
                enabled = available,
                role = Role.RadioButton,
            ) {
                controller.selectModel(profile.spec.id)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = chosen, onClick = null, enabled = available)
            Column(Modifier.weight(1f)) {
                Text(profile.name)
                Text(
                    "$sizeMb MB · ${if (profile.spec.id in installed) "Installed" else "Not installed"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Text(selected.description)
    if (selected.experimental) {
        Text(
            "Experimental Chilean Spanish fine-tune by Roberto Castro-Vexler. Better recognition of your speech is not yet established. Use ES when comparing Spanish recordings."
        )
        Text(
            "Download the converted file from the Altiro installation page, then import it here. The source link contains the original model and its declared Apache-2.0 license."
        )
    }
    Text(status)
    Text(
        "Import needs ${(selected.spec.bytes + 10_000_000 + 999_999) / 1_000_000} MB free. Keep each model installed to switch quickly; browser downloads use additional storage."
    )
    OutlinedButton(onClick = controller::openModelDownload, enabled = available) {
        Text(if (selected.experimental) "Open Chilean model source" else "Download selected model")
    }
    OutlinedButton(onClick = { importModel(selected.spec.id) }, enabled = available) {
        Text("Import ${selected.filename}")
    }
    if (busy) OutlinedButton(onClick = controller.models::cancelImport) { Text("Cancel import") }
    OutlinedButton(
        onClick = controller::deleteModel,
        enabled = available && selected.spec.id in installed,
    ) {
        Text("Delete selected model")
    }
    Text("Language: ${if (language == "auto") "Automatic" else language}")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, label) in listOf("auto" to "Auto", "en" to "EN", "fr" to "FR", "es" to "ES")) {
            OutlinedButton(onClick = { controller.selectLanguage(value) }, enabled = available) {
                Text(label)
            }
        }
    }
    Text(
        "Use ES for Spanish-only speech. Use the same language setting for fair model comparisons."
    )
}

@Composable
private fun ComparisonControls(
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
