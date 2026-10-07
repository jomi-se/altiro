package org.altiro.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.altiro.core.RecognitionBackend

@Composable
internal fun SettingsContent(
    controller: DictationController,
    microphoneAllowed: Boolean,
    requestMicrophone: () -> Unit,
    openAccessibility: () -> Unit,
    openPermissions: () -> Unit,
) {
    val connected by controller.connected.collectAsState()
    val lastEditor by controller.lastEditorStatus.collectAsState()
    val hidden by controller.hiddenApps.collectAsState()
    val restored by controller.restoreFeedback.collectAsState()
    val backend by controller.backend.collectAsState()
    val flash by controller.flashAttention.collectAsState()
    val dynamic by controller.dynamicWindow.collectAsState()
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val available = !session.busy && !nativeBusy && !modelBusy
    val context = LocalContext.current
    val preferences = remember { RecordingPreferences(context) }
    var inPlace by remember { mutableStateOf(preferences.recordInPlace) }
    var info by rememberSaveable { mutableStateOf(false) }

    SettingsSection("Access")
    SettingsAction("Microphone", if (microphoneAllowed) "Allowed" else "Allow") {
        if (microphoneAllowed) openPermissions() else requestMicrophone()
    }
    if (!microphoneAllowed)
        Text("Records only when you tap.", style = MaterialTheme.typography.bodySmall)
    SettingsAction(
        "Floating microphone",
        if (connected) "Connected" else "Enable",
        openAccessibility,
    )
    if (!connected)
        Text("Adds the mic and inserts at your cursor.", style = MaterialTheme.typography.bodySmall)
    Text(
        "Last editor · ${overlayReasonLabel(lastEditor)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsAction(
        "Hidden apps",
        if (hidden.isEmpty()) "None" else "${hidden.size} · Restore all",
        controller::clearDisabledApps,
    )
    restored?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    for (packageName in hidden.sorted()) {
        val label =
            remember(packageName) {
                try {
                    val applicationInfo =
                        context.packageManager.getApplicationInfo(
                            packageName,
                            android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
                        )
                    context.packageManager.getApplicationLabel(applicationInfo).toString()
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    packageName
                } catch (_: SecurityException) {
                    packageName
                }
            }
        SettingsAction(label, "Restore") { controller.restoreApp(packageName) }
    }
    SettingsToggle("Record in place", inPlace, available) {
        inPlace = it
        preferences.recordInPlace = it
    }

    SettingsSection("Recognition")
    LanguageControls(controller)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in RecognitionBackend.entries) {
            FilterChip(
                selected = backend == option,
                enabled = available,
                onClick = { controller.selectBackend(option) },
                label = { Text(if (option == RecognitionBackend.CPU) "CPU" else "GPU · Vulkan") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    SettingsToggle(
        "Flash Attention",
        flash,
        available && backend == RecognitionBackend.VULKAN,
        controller::selectFlashAttention,
    )
    SettingsToggle(
        "Dynamic window · up to 30s",
        dynamic,
        available,
        controller::selectDynamicWindow,
    )
    VocabularyContent(controller)
    if (!available)
        Text("Settings locked during dictation.", style = MaterialTheme.typography.bodySmall)

    TextButton(onClick = { info = !info }) { Text(if (info) "Less" else "About these settings") }
    if (info) {
        Text(
            "Your keyboard stays selected. Accessibility supplies the floating mic and safe insertion; microphone access records only after you tap. Downloads use the Internet; recognition stays on this phone. Audio is temporary.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "GPU and Flash Attention are experimental. GPU failures do not retry on CPU. Dynamic windows reduce short-clip work. Recordings stop at five minutes. If in-place capture is blocked, record from Altiro.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun overlayReasonLabel(code: String): String =
    when (code) {
        "NONE" -> "Open a text field to check"
        "SHOWN",
        "ELIGIBLE",
        "VISIBLE" -> "Mic available"
        "NO_START_INPUT",
        "NO_INPUT_START",
        "NO_CONNECTION" -> "No editor connection"
        "FOCUS_IN_OTHER_WINDOW" -> "Editor found in another window"
        "NOT_VISIBLE" -> "Text field isn't visible"
        "NO_FOCUS_NODE",
        "NOT_FOCUSED" -> "No focused text field"
        "NOT_EDITABLE" -> "This view isn't a text field"
        "NO_FIELD_KEY",
        "NO_WINDOW",
        "PACKAGE_MISMATCH" -> "Editor identity unavailable"
        "PASSWORD" -> "Password field"
        "HIDDEN_APP" -> "Hidden in this app"
        "OTHER_DISPLAY" -> "Unsupported display"
        "LOCKED" -> "Device locked"
        "DISCONNECTED" -> "Enable the floating mic"
        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun SettingsAction(title: String, value: String, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = action),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        AltiroIcon(Glyph.ARROW)
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun ComparisonTools(
    controller: DictationController,
    microphoneAllowed: Boolean,
    compareBackends: (Boolean) -> Unit,
    compareWindows: (Boolean) -> Unit,
    compareModels: (List<String>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var extraIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val session by controller.session.collectAsState()
    val nativeBusy by controller.recognition.busy.collectAsState()
    val modelBusy by controller.models.busy.collectAsState()
    val installed by controller.models.installed.collectAsState()
    val selected by controller.models.selected.collectAsState()
    val available = !session.busy && !nativeBusy && !modelBusy && session.text == null
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Close comparison" else "Compare a recording")
    }
    if (!expanded) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, label) in listOf("Models", "CPU / GPU", "Windows").withIndex()) {
            FilterChip(
                selected = mode == index,
                enabled = available,
                onClick = { mode = index },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    val ids = listOf(ModelStore.SMALL_Q8, ModelStore.SMALL_FP16) + extraIds.sorted()
    if (mode == 0) {
        Text("Small Q8 + FP16", style = MaterialTheme.typography.bodyMedium)
        for (profile in controller.models.profiles.filter { it.experimental }) {
            SettingsToggle(profile.name, profile.spec.id in extraIds, available) {
                extraIds = if (it) extraIds + profile.spec.id else extraIds - profile.spec.id
            }
        }
    } else {
        Text(selected.name, style = MaterialTheme.typography.bodyMedium)
        SettingsToggle(if (mode == 1) "GPU first" else "Dynamic first", reverse, available) {
            reverse = it
        }
    }
    val modelsReady = if (mode == 0) ids.all { it in installed } else selected.spec.id in installed
    if (!modelsReady)
        Text("Install the selected models first.", style = MaterialTheme.typography.bodySmall)
    Text(
        "Same audio · sequential passes · no insertion",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        enabled = available && microphoneAllowed && modelsReady,
        onClick = {
            when (mode) {
                0 -> compareModels(ids)
                1 -> compareBackends(reverse)
                else -> compareWindows(reverse)
            }
        },
    ) {
        AltiroIcon(Glyph.MIC, color = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text("Record comparison")
    }
}
