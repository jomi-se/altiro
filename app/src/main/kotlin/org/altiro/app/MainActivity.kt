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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    private val modelImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { (application as AltiroApplication).controller.importModel(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val controller = (application as AltiroApplication).controller
        val allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Offline dictation preview", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Speak, stop, and get a transcript from Whisper running on your phone. Accuracy and speed still need phone testing, especially conversational Chilean Spanish.",
                            )
                            Text("No account, network access, transcript history, or word allowance.")
                        }
                    }
                    ModelControls(controller) { modelImport.launch(arrayOf("*/*")) }
                    Text(if (connected) "Floating mic connected" else "Floating mic is off")
                    RecordingModeControls(RecordingPreferences(this@MainActivity))
                    Text(
                        "Accessibility access observes the selected editor, cursor and composition, shows a small control, and inserts text. It does not collect screen or clipboard contents. Your keyboard stays selected.",
                    )
                    OutlinedButton(
                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    ) { Text("Open accessibility settings") }
                    Text(if (allowed) "Microphone permission granted" else "Allow microphone access to record your voice.")
                    if (!allowed) {
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) { Text("Open app permission settings") }
                    }
                    OutlinedButton(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    }) { Text("Allow microphone and notifications") }
                    Button(onClick = {
                        startActivity(Intent(this@MainActivity, RecordingActivity::class.java))
                    }, enabled = allowed) { Text("Open recording screen") }
                    ResultControls(controller)
                    var sample by remember { mutableStateOf("") }
                    OutlinedTextField(value = sample, onValueChange = { sample = it }, label = { Text("Try inserting here") })
                    OutlinedButton(onClick = { controller.clearDisabledApps() }) { Text("Reset disabled apps") }
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
        },
    )
}

@Composable
internal fun AltiroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Color(0xFF006B60), background = Color(0xFFF7F8F2), surface = Color(0xFFF7F8F2)),
    ) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
internal fun ResultControls(controller: DictationController) {
    val session by controller.session.collectAsState()
    val progress by controller.progress.collectAsState()
    val seconds by controller.processingSeconds.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    if (session.phase == org.altiro.core.Phase.TRANSCRIBING) {
        Text("Recognizing offline · $progress% · ${seconds}s")
        Text("Loading the model can take a moment. The microphone is released.")
        LinearProgressIndicator(progress = { progress / 100f })
        OutlinedButton(onClick = controller::cancel) { Text("Cancel recognition") }
    } else if (nativeBusy) {
        Text("Finishing cancellation and releasing the model…")
    }
    session.text?.let { text ->
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        Text(text)
        session.message?.let { Text(it) }
        Text("Return to your editor for Insert. Copy changes the clipboard only when you tap it.")
        OutlinedButton(onClick = controller::copy) { Text("Copy result") }
        OutlinedButton(onClick = controller::discard) { Text("Discard result") }
    }
    if (session.text == null) session.message?.let { Text(it) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ModelControls(
    controller: DictationController,
    importModel: () -> Unit,
) {
    val ready by controller.models.ready.collectAsState()
    val busy by controller.models.busy.collectAsState()
    val status by controller.models.status.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val language by controller.language.collectAsState()
    val available = !session.busy && !nativeBusy && !busy
    Text("Speech model", style = MaterialTheme.typography.titleMedium)
    Text(status)
    Text(
        "Multilingual base · 148 MB private storage. Keep at least 158 MB free for import; the browser's Downloads copy is separate. Native working memory is additional.",
    )
    OutlinedButton(onClick = controller::openModelDownload, enabled = available) { Text("Download model in browser") }
    OutlinedButton(onClick = importModel, enabled = available) { Text(if (ready) "Replace model file" else "Import ggml-base.bin") }
    if (busy) OutlinedButton(onClick = controller.models::cancelImport) { Text("Cancel import") }
    OutlinedButton(
        onClick = controller::deleteModel,
        enabled = available && controller.models.file.exists(),
    ) { Text("Delete installed model") }
    Text("Language: ${if (language == "auto") "Automatic" else language}")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, label) in listOf("auto" to "Auto", "en" to "EN", "fr" to "FR", "es" to "ES")) {
            OutlinedButton(onClick = { controller.selectLanguage(value) }, enabled = available) { Text(label) }
        }
    }
    Text("Use ES for Spanish-only speech. This base model is multilingual; a Chilean Spanish model is being evaluated separately.")
}
