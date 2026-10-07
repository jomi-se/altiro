package org.altiro.app

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.altiro.core.DiagnosticReport
import org.altiro.core.Phase

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
    val overlay by controller.overlayStatus.collectAsState()
    val lastEditor by controller.lastEditorStatus.collectAsState()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.phase in setOf(Phase.STARTING, Phase.RECORDING)) {
            IconButton(
                onClick = controller::stop,
                modifier = Modifier.semantics { contentDescription = "Stop and transcribe" },
            ) {
                AltiroIcon(Glyph.STOP)
            }
        }
        if (session.busy || nativeBusy) {
            IconButton(
                onClick = controller::cancel,
                enabled = session.busy,
                modifier = Modifier.semantics { contentDescription = "Cancel dictation" },
            ) {
                AltiroIcon(Glyph.CLOSE)
            }
        }
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
    val current = report
    if (current == null) {
        Text("No recording yet.", style = MaterialTheme.typography.bodyMedium)
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
    }
    var details by remember { mutableStateOf(false) }
    TextButton(onClick = { details = !details }) {
        Text(if (details) "Less detail" else "Startup & saved logs")
    }
    if (details) {
        Text(
            "Startup · ${startupMillis?.let { DiagnosticReport.seconds(it) + " s" } ?: modelStatus}",
            style = MaterialTheme.typography.titleMedium,
        )
        for (check in startup) ConsoleRow(
            check.modelId,
            "${DiagnosticReport.seconds(check.elapsedMillis)} s · ${if (check.verified) "verified" else "failed"}",
        )
        Text(
            "Overlay: $overlay · last editor: $lastEditor",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Window events: $ownEvents owned · $otherEvents invalidated",
            style = MaterialTheme.typography.bodySmall,
        )
        acquisition?.let {
            Text(
                "Download: ${it.phase} · ${it.percent}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (current != null || checkpoint != null) {
            var savedLog by remember { mutableStateOf(false) }
            if (current != null && checkpoint != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !savedLog,
                        onClick = { savedLog = false },
                        label = { Text("Current log") },
                    )
                    FilterChip(
                        selected = savedLog,
                        onClick = { savedLog = true },
                        label = { Text("Saved log") },
                    )
                }
            }
            Text(
                if (savedLog || current == null) "Saved checkpoint" else "Runtime log",
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                SelectionContainer {
                    Text(
                        if (savedLog || current == null) checkpoint.orEmpty() else current.export(),
                        Modifier.padding(16.dp),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                    )
                }
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
        appendLine(
            "Overlay status: ${controller.overlayStatus.value}; last editor: ${controller.lastEditorStatus.value}; hidden apps: ${controller.hiddenApps.value.size}"
        )
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
