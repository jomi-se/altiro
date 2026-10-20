package org.altiro.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.altiro.core.RecognitionEngine

@Composable
internal fun EncoderLabTools(controller: DictationController, microphoneAllowed: Boolean) {
    val selected by controller.models.selected.collectAsState()
    val summary by controller.encoderLab.summary.collectAsState()
    val session by controller.session.collectAsState()
    val busy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val installed by controller.models.installed.collectAsState()
    var mode by rememberSaveable { mutableStateOf(1) }
    var both by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val available = !session.busy && !busy && !modelBusy && session.text == null
    Text("Encoder lab", style = MaterialTheme.typography.titleMedium)
    if (selected.engine != RecognitionEngine.TRANSCRIBEPSY) {
        Text("Select TranscribePsy in Models.", style = MaterialTheme.typography.bodySmall)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                enabled = available,
                label = { Text("Quick") },
            )
            FilterChip(
                selected = mode == 2,
                onClick = { mode = 2 },
                enabled = available,
                label = { Text("Full") },
            )
            FilterChip(
                selected = mode == 3,
                onClick = { mode = 3 },
                enabled = available,
                label = { Text("Kernel hunt") },
            )
            FilterChip(
                selected = mode == 4,
                onClick = { mode = 4 },
                enabled = available,
                label = { Text("GPU tuning") },
            )
            FilterChip(
                selected = mode == 5,
                onClick = { mode = 5 },
                enabled = available,
                label = { Text("Packed GPU") },
            )
            FilterChip(
                selected = both,
                onClick = { both = !both },
                enabled =
                    available && (both || controller.models.precisionIds().all { it in installed }),
                label = { Text("Q8 + FP16") },
            )
            FilterChip(
                selected = profile,
                onClick = { profile = !profile },
                enabled = available,
                label = { Text("GPU profiler") },
            )
        }
        Text(
            "${if (both) "Q8 + FP16" else selected.name} · ${if (mode != 1) "several minutes" else "about 2 minutes"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled =
                available &&
                    microphoneAllowed &&
                    selected.spec.id in installed &&
                    (!both || controller.models.precisionIds().all { it in installed }),
            onClick = {
                context.startActivity(
                    Intent(context, RecordingActivity::class.java)
                        .putExtra("encoder-lab", mode)
                        .putExtra("encoder-lab-both", both)
                        .putExtra("encoder-lab-profile", profile)
                )
            },
        ) {
            AltiroIcon(Glyph.MIC, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Record encoder lab")
        }
    }
    if (summary.isNotEmpty()) {
        Text(summary.lineSequence().first(), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide results" else "Results")
            }
            OutlinedButton(
                onClick = { shareDiagnostics(context, controller.encoderLab.export()) }
            ) {
                AltiroIcon(Glyph.SHARE)
                Spacer(Modifier.width(6.dp))
                Text("Share lab")
            }
        }
        if (expanded) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
    if (BuildConfig.DEBUG) LabRelayTools(controller.encoderLab, available)
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun LabRelayTools(lab: EncoderLab, available: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val status by lab.relay.status.collectAsState()
    TextButton(onClick = { expanded = !expanded }) { Text("Live results") }
    if (expanded) {
        Text("Lab timings only · Tailscale", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it.take(256)
                invalid = false
            },
            label = { Text("Receiver link") },
            singleLine = true,
            enabled = available,
            visualTransformation = PasswordVisualTransformation(),
            isError = invalid,
            supportingText = { Text(if (invalid) "Paste the receiver’s pairing link" else status) },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    invalid = !lab.relay.connect(link, lab.export().toByteArray())
                },
                enabled = available && link.isNotBlank(),
            ) {
                Text("Connect")
            }
            TextButton(
                onClick = {
                    lab.relay.disconnect()
                    link = ""
                },
                enabled = status != "Disconnected",
            ) {
                Text("Disconnect")
            }
        }
    }
}
