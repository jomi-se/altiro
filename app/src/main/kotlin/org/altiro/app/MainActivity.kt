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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

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
                            Text("Android integration preview", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This build records a microphone test and returns a fixed phrase. Speech recognition is the next milestone after device validation.",
                            )
                            Text("No account, network access, transcript history, or word allowance.")
                        }
                    }
                    Text(if (connected) "Floating mic connected" else "Floating mic is off")
                    Text(
                        "Accessibility access observes the selected editor, cursor and composition, shows a small control, and inserts text. It does not collect screen or clipboard contents. Your keyboard stays selected.",
                    )
                    OutlinedButton(
                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    ) { Text("Open accessibility settings") }
                    Text(if (allowed) "Microphone permission granted" else "Allow the microphone to run the recording test.")
                    if (!allowed) {
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) { Text("Open app permission settings") }
                    }
                    OutlinedButton(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    }) { Text("Allow microphone and controls") }
                    Button(onClick = {
                        startActivity(Intent(this@MainActivity, RecordingActivity::class.java))
                    }, enabled = allowed) { Text("Open recording test") }
                    Text("The visible recording screen is the default while the one-tap background recording route remains unverified.")
                    if (BuildConfig.DEBUG) {
                        var probe by remember {
                            mutableStateOf(
                                getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("direct-probe", false),
                            )
                        }
                        OutlinedButton(onClick = {
                            probe = !probe
                            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("direct-probe", probe).apply()
                        }) { Text(if (probe) "Use visible recording screen" else "Enable direct-start device experiment") }
                        if (probe) {
                            Text(
                                "Debug experiment enabled: the floating mic will try direct recording. Android may refuse it; reopen Altiro for the visible recording screen.",
                            )
                        }
                    }
                    ResultControls(controller)
                    var sample by remember { mutableStateOf("") }
                    OutlinedTextField(value = sample, onValueChange = { sample = it }, label = { Text("Try inserting here") })
                    OutlinedButton(onClick = { controller.clearDisabledApps() }) { Text("Reset disabled apps") }
                    Text(
                        "Audio is deleted after the test or cancellation. Uninserted results expire after ten minutes and disappear if the process closes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
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
    session.text?.let { text ->
        Text("Fixed test result", style = MaterialTheme.typography.titleMedium)
        Text(text)
        session.message?.let { Text(it) }
        Text("Return to your editor for Insert. Copy changes the clipboard only when you tap it.")
        OutlinedButton(onClick = controller::copy) { Text("Copy result") }
        OutlinedButton(onClick = controller::discard) { Text("Discard result") }
    }
    if (session.text == null) session.message?.let { Text(it) }
    Spacer(Modifier.height(4.dp))
}
