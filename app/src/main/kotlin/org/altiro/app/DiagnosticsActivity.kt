package org.altiro.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.altiro.core.DiagnosticReport

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as AltiroApplication).controller
        setContent {
            AltiroTheme {
                val report by controller.diagnostics.report.collectAsState()
                val session by controller.session.collectAsState()
                val nativeBusy by controller.recognition.busy.collectAsState()
                val checkpoint by controller.checkpoint.saved.collectAsState()
                KeepAwake(session.busy || nativeBusy)
                val startupChecks by controller.models.startupChecks.collectAsState()
                val startupMillis by controller.models.startupMillis.collectAsState()
                val modelStatus by controller.models.status.collectAsState()
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Recognition diagnostics", style = MaterialTheme.typography.headlineMedium)
                    Text("The latest recording, phase by phase. Updates while recognition runs.")
                    Text(
                        "Live timings stay in memory for ten minutes. One content-free checkpoint survives restart until you clear it or start another recording. No audio or dictated text is included."
                    )
                    Text("App startup checks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "App startup checks all installed model files. These times are separate from dictation and stay available until the process closes."
                    )
                    Text(
                        startupMillis?.let { "Ready in ${DiagnosticReport.seconds(it)} s" }
                            ?: modelStatus
                    )
                    for (check in startupChecks) {
                        Text(
                            "${controller.models.profiles.first { it.spec.id == check.modelId }.name}: " +
                                "${DiagnosticReport.seconds(
                                    check.elapsedMillis
                                )} s · ${if (check.verified) "verified" else "verification failed"}"
                        )
                    }
                    val current = report
                    if (current == null) {
                        Text("No trace yet. Record and stop a dictation, then return here.")
                        OutlinedButton(onClick = { controller.copyText(export(null)) }) {
                            Text("Copy startup diagnostics")
                        }
                        checkpoint?.let { saved ->
                            Text(
                                "Saved runtime checkpoint",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "A RUNNING checkpoint after restart means work was interrupted; it is not resumed."
                            )
                            Text(saved)
                            OutlinedButton(onClick = { controller.copyText(saved) }) {
                                Text("Copy saved checkpoint")
                            }
                            OutlinedButton(
                                onClick = controller::clearDiagnostics,
                                enabled = !session.busy && !nativeBusy,
                            ) {
                                Text("Clear diagnostics")
                            }
                        }
                    } else {
                        Text(
                            if (current.comparison) {
                                "Comparison · ${current.modelIds.size} passes, one after another"
                            } else {
                                "Everyday dictation · one model"
                            }
                        )
                        Text(
                            "Installed models stay on disk. Each run verifies, loads and releases only the model being used. No model is kept in memory between recordings."
                        )
                        Text(
                            "${current.outcome?.name ?: "RUNNING"} · after Stop: ${DiagnosticReport.seconds(current.processingMillis)} s"
                        )
                        current.steps
                            .lastOrNull { it.running }
                            ?.let { step ->
                                Text(
                                    "${step.stage.label} · ${DiagnosticReport.seconds(step.durationMillis)} s",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                step.modelId?.let { id ->
                                    Text(controller.models.profiles.first { it.spec.id == id }.name)
                                }
                            }
                        current.audioMillis?.let {
                            Text("Recorded audio: ${DiagnosticReport.seconds(it)} s")
                        }
                        if (current.cancellationRequested) {
                            Text(
                                "Cancellation requested. If native cleanup stalls for ten seconds, the recognition worker is terminated before temporary audio is deleted."
                            )
                        }
                        current.failureStage?.let { Text("Failure during: ${it.label}") }
                        for (step in current.steps) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    step.modelId?.let { id ->
                                        Text(
                                            controller.models.profiles
                                                .first { it.spec.id == id }
                                                .name,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                    Text(
                                        step.stage.label,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    step.backend?.let { Text(it.label) }
                                    step.window?.let { Text(it.label) }
                                    Text(
                                        "${DiagnosticReport.seconds(step.durationMillis)} s${if (step.running) " · running" else ""}"
                                    )
                                    Text(
                                        "Started +${DiagnosticReport.seconds(step.startMillis)} s after tapping Record",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        for (runtime in current.runtimes) {
                            Card(Modifier.fillMaxWidth()) {
                                Text(runtime.export(), Modifier.padding(12.dp))
                            }
                        }
                        Text(
                            "Recognition includes audio features, language detection in Auto, encoder and decoder. Its percentage is an upstream progress estimate, not a countdown. Timings measure wall time, including scheduling delays."
                        )
                        OutlinedButton(onClick = { controller.copyText(export(current)) }) {
                            Text("Copy diagnostics")
                        }
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, export(current)),
                                        "Share diagnostics",
                                    )
                                )
                            }
                        ) {
                            Text("Share diagnostics")
                        }
                        OutlinedButton(
                            onClick = controller::clearDiagnostics,
                            enabled = !session.busy && !nativeBusy,
                        ) {
                            Text("Clear diagnostics")
                        }
                    }
                    OutlinedButton(onClick = { finish() }) { Text("Back") }
                }
            }
        }
    }

    private fun export(report: DiagnosticReport?): String {
        val controller = (application as AltiroApplication).controller
        val microphone =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val startup = buildString {
            appendLine("App startup checks (separate from dictation):")
            appendLine(
                "Total: ${controller.models.startupMillis.value
                        ?.let { DiagnosticReport.seconds(it) + " s" } ?: "RUNNING"}"
            )
            for (check in controller.models.startupChecks.value) {
                appendLine(
                    "${check.modelId}: ${DiagnosticReport.seconds(check.elapsedMillis)} s; verified: ${check.verified}"
                )
            }
        }
        return "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); " +
            "Android: ${Build.VERSION.RELEASE}; API: ${Build.VERSION.SDK_INT}\n" +
            "Hardware: ${Build.MANUFACTURER} ${Build.MODEL}; ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "Microphone permission: $microphone; floating mic connected: ${controller.connected.value}\n" +
            startup +
            (report?.export() ?: "No recording trace yet. No text, audio or file paths included.\n")
    }
}
