package org.altiro.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    var full by rememberSaveable { mutableStateOf(false) }
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
                selected = !full,
                onClick = { full = false },
                enabled = available,
                label = { Text("Quick") },
            )
            FilterChip(
                selected = full,
                onClick = { full = true },
                enabled = available,
                label = { Text("Full") },
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
            "${if (both) "Q8 + FP16" else selected.name} · ${if (full) "several minutes" else "about 2 minutes"}",
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
                        .putExtra("encoder-lab", if (full) 2 else 1)
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}
