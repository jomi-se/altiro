package org.altiro.fixture

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class FixtureTest {
    @get:Rule val compose = createAndroidComposeRule<FixtureActivity>()

    @Test fun composeFixtureIsEditable() {
        compose.onNodeWithTag("compose-editor").performTextInput("café, mañana")
        compose.onNodeWithTag("compose-editor").assertTextContains("café, mañana")
    }

    @Test fun stockEditorInputConnectionPreservesCursorAndSelectionSemantics() {
        compose.runOnIdle {
            val editor = compose.activity.findViewById<EditText>(R.id.plain_editor)
            editor.requestFocus()
            val connection = editor.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection.commitText("café", 1)
            assertEquals("Before café after", editor.text.toString())
            connection.setSelection(0, 6)
            connection.commitText("mañana", 1)
            assertEquals("mañana café after", editor.text.toString())
        }
    }

    @Test fun inputFiltersMayTransformAnAttemptedCommit() {
        compose.runOnIdle {
            val editor = compose.activity.findViewById<EditText>(R.id.filtered_editor)
            editor.requestFocus()
            val connection = editor.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection.commitText("abcdefghijklmnopqrstuvwxyz", 1)
            assertEquals("abcdefghijklmnopqrst", editor.text.toString())
        }
    }
}
