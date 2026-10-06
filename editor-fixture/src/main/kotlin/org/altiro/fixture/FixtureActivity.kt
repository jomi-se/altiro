package org.altiro.fixture

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.webkit.WebView
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class FixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var primary: EditText? by remember { mutableStateOf(null) }
                var passwordMode by remember {
                    mutableStateOf(
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    )
                }
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Altiro editor fixture", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Separate app. Use a real keyboard and the floating control. None of these buttons sends a message."
                    )
                    Text("Stock multiline editor")
                    AndroidView(
                        factory = { context ->
                            EditText(context).apply {
                                id = R.id.plain_editor
                                hint = "Type, select, and insert here"
                                inputType =
                                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                setText("Before  after")
                                setSelection(7)
                                primary = this
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                    Button(
                        onClick = {
                            primary?.let {
                                it.requestFocus()
                                it.setSelection(0, minOf(6, it.length()))
                            }
                        }
                    ) {
                        Text("Select first six characters")
                    }
                    Button(
                        onClick = {
                            primary?.let {
                                val changed =
                                    it.text
                                        .toString()
                                        .map { character ->
                                            if (character.isUpperCase()) character.lowercaseChar()
                                            else character.uppercaseChar()
                                        }
                                        .joinToString("")
                                it.setText(changed)
                                it.setSelection(it.length())
                            }
                        }
                    ) {
                        Text("Replace with same-length text")
                    }
                    Text("Second field in the same app")
                    AndroidView(
                        factory = { context ->
                            EditText(context).apply {
                                id = R.id.second_editor
                                hint = "Switch here while processing"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var composeText by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = composeText,
                        onValueChange = {
                            composeText = it
                        },
                        label = { Text("Compose editor") },
                        modifier = Modifier.fillMaxWidth().testTag("compose-editor"),
                    )
                    Text("Filtered editor: at most 20 characters")
                    AndroidView(
                        factory = { context ->
                            EditText(context).apply {
                                id = R.id.filtered_editor
                                filters = arrayOf(InputFilter.LengthFilter(20))
                                hint = "Expect transformed/truncated insertion"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Password editor: floating mic must hide")
                    AndroidView(
                        factory = { context ->
                            EditText(context).apply {
                                id = R.id.password_editor
                                hint = "Password test"
                            }
                        },
                        update = {
                            it.inputType = passwordMode
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            passwordMode =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        }
                    ) {
                        Text("Visible password variant")
                    }
                    Button(
                        onClick = {
                            passwordMode =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        }
                    ) {
                        Text("Web password variant")
                    }
                    Button(
                        onClick = {
                            passwordMode =
                                InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        }
                    ) {
                        Text("Number password variant")
                    }
                    Text("WebView textarea and contenteditable")
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = false
                                loadDataWithBaseURL(
                                    null,
                                    "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><textarea aria-label='Web textarea' rows='3'>Textarea</textarea><div contenteditable='true' role='textbox' aria-label='Web editable'>Editable web content</div></body></html>",
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }
        }
    }
}
