package org.altiro.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.altiro.core.DiagnosticReport

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as AltiroApplication).controller
        setContent {
            AltiroTheme {
                val session by controller.session.collectAsState()
                val nativeBusy by controller.recognition.busy.collectAsState()
                KeepAwake(session.busy || nativeBusy)
                Column(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Console", style = MaterialTheme.typography.headlineLarge)
                    ConsoleContent(
                        controller,
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED,
                    )
                    TextButton(onClick = { finish() }) { Text("Back") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun ConsoleContent(controller: DictationController, microphoneAllowed: Boolean) {
    val report by controller.diagnostics.report.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val checkpoint by controller.checkpoint.saved.collectAsState()
    val startup by controller.models.startupChecks.collectAsState()
    val startupMillis by controller.models.startupMillis.collectAsState()
    val modelStatus by controller.models.status.collectAsState()
    val ownEvents by controller.overlayWindowEvents.collectAsState()
    val otherEvents by controller.otherWindowEvents.collectAsState()
    val acquisition by controller.models.acquisition.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(
        "Phase timings, live runtime and the last saved checkpoint. No dictated text, audio or editor identifiers.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { controller.copyText(exportDiagnostics(controller, microphoneAllowed)) }
        ) {
            AltiroIcon(Glyph.COPY)
            Spacer(Modifier.width(6.dp))
            Text("Copy")
        }
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                exportDiagnostics(controller, microphoneAllowed),
                            )
                        },
                        "Share diagnostics",
                    )
                )
            }
        ) {
            AltiroIcon(Glyph.SHARE)
            Spacer(Modifier.width(6.dp))
            Text("Share")
        }
        IconButton(
            onClick = controller::clearDiagnostics,
            enabled = !session.busy && !nativeBusy,
            modifier = Modifier.semantics { contentDescription = "Clear diagnostics" },
        ) {
            AltiroIcon(Glyph.DELETE)
        }
    }
    acquisition?.let {
        Text(
            "Model acquisition: ${it.modelId} · ${it.phase} · ${it.percent}%${it.failure?.let { reason -> " · $reason" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text("Startup", style = MaterialTheme.typography.titleMedium)
    Text(
        startupMillis?.let { "${DiagnosticReport.seconds(it)} s · separate from dictation" }
            ?: modelStatus,
        style = MaterialTheme.typography.bodySmall,
    )
    for (check in startup) {
        ConsoleRow(
            controller.models.profiles.first { it.spec.id == check.modelId }.name,
            "${DiagnosticReport.seconds(check.elapsedMillis)} s · ${if(check.verified) "verified" else "failed"}",
        )
    }
    Text(
        "Overlay events: $ownEvents ignored as owned · $otherEvents conservatively invalidated",
        style = MaterialTheme.typography.bodySmall,
    )
    val current = report
    if (current == null) {
        Text("Record and stop a dictation to see its trace.")
    } else {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Text(
            if (current.comparison) "Comparison · ${current.modelIds.size} passes"
            else "Latest dictation",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${current.outcome?.name ?: "RUNNING"} · ${DiagnosticReport.seconds(current.processingMillis)} s after Stop",
            style = MaterialTheme.typography.bodyLarge,
        )
        current.audioMillis?.let {
            Text(
                "${DiagnosticReport.seconds(it)} s audio",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (step in current.steps) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ConsoleRow(
                    step.stage.label,
                    "${DiagnosticReport.seconds(step.durationMillis)} s${if(step.running) " · running" else ""}",
                )
                Text(
                    listOfNotNull(
                            step.modelId,
                            step.backend?.label,
                            step.window?.label,
                            "+${DiagnosticReport.seconds(step.startMillis)} s",
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("Runtime trace", style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    current.export(),
                    Modifier.padding(16.dp),
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
    if (checkpoint != null) {
        Text("Saved checkpoint", style = MaterialTheme.typography.titleMedium)
        Text(
            "A RUNNING checkpoint after restart means interrupted work; it is not resumed. Replace it with a new recording or clear it here.",
            style = MaterialTheme.typography.bodySmall,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    checkpoint!!,
                    Modifier.padding(16.dp),
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun ConsoleRow(label: String, value: String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

internal fun exportDiagnostics(controller: DictationController, microphone: Boolean): String =
    buildString {
        appendLine(
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Android: ${Build.VERSION.RELEASE}; API: ${Build.VERSION.SDK_INT}"
        )
        appendLine(
            "Hardware: ${Build.MANUFACTURER} ${Build.MODEL}; ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"
        )
        appendLine(
            "Microphone permission: $microphone; floating mic connected: ${controller.connected.value}"
        )
        appendLine(
            "Overlay window events ignored: ${controller.overlayWindowEvents.value}; other window events invalidated: ${controller.otherWindowEvents.value}"
        )
        controller.models.acquisition.value?.let {
            appendLine(
                "Model acquisition: ${it.modelId}; ${it.phase}; ${it.percent}%; failure: ${it.failure ?: "NONE"}"
            )
        }
        appendLine("App startup checks (separate from dictation):")
        appendLine(
            "Total: ${controller.models.startupMillis.value?.let {DiagnosticReport.seconds(it)+" s"} ?: "RUNNING"}"
        )
        for (check in controller.models.startupChecks.value) appendLine(
            "${check.modelId}: ${DiagnosticReport.seconds(check.elapsedMillis)} s; verified: ${check.verified}"
        )
        append(controller.diagnostics.report.value?.export() ?: "No recording trace yet.\n")
        controller.checkpoint.saved.value?.let {
            appendLine("\nSaved checkpoint:")
            appendLine(it)
        }
    }
