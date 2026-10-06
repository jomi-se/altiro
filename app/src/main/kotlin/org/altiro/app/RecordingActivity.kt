package org.altiro.app

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.altiro.core.Phase
import org.altiro.core.SessionEvent

class RecordingActivity : ComponentActivity() {
    private val controller
        get() = (application as AltiroApplication).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller.editor.invalidate()
        setContent {
            AltiroTheme {
                val session by controller.session.collectAsState()
                val ready by controller.models.ready.collectAsState()
                val modelBusy by controller.models.busy.collectAsState()
                val nativeBusy by controller.recognition.busy.collectAsState()
                val installed by controller.models.installed.collectAsState()
                val selected by controller.models.selected.collectAsState()
                val comparing by controller.comparing.collectAsState()
                val backend by controller.backend.collectAsState()
                val compareIds = intent.getStringArrayListExtra("compare-models")
                val gpuCompare = intent.getBooleanExtra("compare-backends", false)
                val windowCompare = intent.getBooleanExtra("compare-windows", false)
                val allReady = if (compareIds != null) compareIds.all { it in installed } else ready
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        if (windowCompare) {
                            "Compare audio windows"
                        } else if (gpuCompare) {
                            "Compare CPU and GPU"
                        } else if (compareIds != null || comparing) {
                            "Compare speech models"
                        } else {
                            "Dictate offline"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (windowCompare) {
                            "One recording uses full and dynamic windows with the same model, processor and attention setting. Record less than 30 seconds. Compare the text, especially sentence endings. Nothing is inserted automatically."
                        } else if (gpuCompare) {
                            "The same ${selected.name} model processes one recording on CPU and Vulkan GPU, sequentially. Nothing is inserted automatically. Keep this screen open while testing."
                        } else if (compareIds != null || comparing) {
                            "One recording is processed by each model sequentially. Nothing is inserted automatically. Copy the result you prefer. Starting another recording clears these results."
                        } else {
                            "Using ${selected.name} · ${backend.label}. Change settings in Altiro."
                        }
                    )
                    Text(
                        "Start while this screen is visible. Once Recording appears, you may return to your editor. Stop and Cancel stay available in the notification and floating control."
                    )
                    Text(
                        when (session.phase) {
                            Phase.STARTING -> "Starting…"
                            Phase.RECORDING -> "Recording · ${session.elapsedSeconds}s"
                            Phase.FINALIZING -> "Finishing recording…"
                            Phase.TRANSCRIBING -> "Recognizing your speech…"
                            else ->
                                if (allReady) "Ready to record"
                                else "Open Altiro and import the supported model first."
                        }
                    )
                    if (session.elapsedSeconds >= 270)
                        Text("Recording stops at five minutes to bound memory and storage.")
                    Button(
                        onClick = ::startRecording,
                        enabled =
                            allReady &&
                                !modelBusy &&
                                !nativeBusy &&
                                !session.busy &&
                                session.text == null,
                    ) {
                        Text("Start recording")
                    }
                    Button(
                        onClick = controller::stop,
                        enabled = session.phase in setOf(Phase.STARTING, Phase.RECORDING),
                    ) {
                        Text("Stop and transcribe")
                    }
                    OutlinedButton(onClick = controller::cancel, enabled = session.busy) {
                        Text("Cancel recording")
                    }
                    ResultControls(controller)
                    OutlinedButton(onClick = { finish() }) { Text("Return to editor") }
                }
            }
        }
    }

    private fun startRecording() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        val id =
            controller.begin(
                explicit = true,
                compareIds = intent.getStringArrayListExtra("compare-models"),
                gpuCompare = intent.getBooleanExtra("compare-backends", false),
                gpuFirst = intent.getBooleanExtra("gpu-first", false),
                windowCompare = intent.getBooleanExtra("compare-windows", false),
                dynamicFirst = intent.getBooleanExtra("dynamic-first", false),
            ) ?: return
        try {
            startForegroundService(
                DictationRecordingService.intent(this, DictationRecordingService.START, id)
            )
        } catch (_: ForegroundServiceStartNotAllowedException) {
            controller.event(
                SessionEvent.Fail(
                    id,
                    "Android blocked recording startup. Keep this screen visible and try again.",
                )
            )
        } catch (_: SecurityException) {
            controller.event(
                SessionEvent.Fail(
                    id,
                    "Microphone access was denied. Check system permissions and microphone privacy.",
                )
            )
        }
    }
}
