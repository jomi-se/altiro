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
    private val controller get() = (application as AltiroApplication).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller.editor.invalidate()
        setContent {
            AltiroTheme {
                val session by controller.session.collectAsState()
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Recording test", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Start while this screen is visible. Once Recording appears, you may return to your editor. Stop and Cancel stay available in the notification and floating control.",
                    )
                    Text(
                        when (session.phase) {
                            Phase.STARTING -> "Starting…"
                            Phase.RECORDING -> "Recording · ${session.elapsedSeconds}s"
                            Phase.FINALIZING, Phase.TRANSCRIBING -> "Preparing the fixed test result…"
                            else -> "Ready for a microphone test"
                        },
                    )
                    if (session.elapsedSeconds >= 270) Text("Recording stops at five minutes to bound memory and storage.")
                    Button(onClick = ::startRecording, enabled = !session.busy && session.text == null) { Text("Start microphone test") }
                    Button(
                        onClick = controller::stop,
                        enabled = session.phase in setOf(Phase.STARTING, Phase.RECORDING),
                    ) { Text("Stop and get test phrase") }
                    OutlinedButton(onClick = controller::cancel, enabled = session.busy) { Text("Cancel recording") }
                    ResultControls(controller)
                    OutlinedButton(onClick = { finish() }) { Text("Return to editor") }
                }
            }
        }
    }

    private fun startRecording() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val id = controller.begin(explicit = true) ?: return
        try {
            startForegroundService(DictationRecordingService.intent(this, DictationRecordingService.START, id))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            controller.event(SessionEvent.Fail(id, "Android blocked recording startup. Keep this screen visible and try again."))
        } catch (_: SecurityException) {
            controller.event(SessionEvent.Fail(id, "Microphone access was denied. Check system permissions and microphone privacy."))
        }
    }
}
