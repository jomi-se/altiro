package org.altiro.fixture

import android.content.ClipData
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FixtureTest {
    @get:Rule val compose = createAndroidComposeRule<FixtureActivity>()

    @Test
    fun composeFixtureIsEditable() {
        compose.onNodeWithTag("compose-editor").performTextInput("café, mañana")
        compose.onNodeWithTag("compose-editor").assertTextContains("café, mañana")
    }

    @Test
    fun stockEditorInputConnectionPreservesCursorAndSelectionSemantics() {
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

    @Test
    fun inputFiltersMayTransformAnAttemptedCommit() {
        compose.runOnIdle {
            val editor = compose.activity.findViewById<EditText>(R.id.filtered_editor)
            editor.requestFocus()
            val connection = editor.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection.commitText("abcdefghijklmnopqrstuvwxyz", 1)
            assertEquals("abcdefghijklmnopqrst", editor.text.toString())
        }
    }

    @Test
    fun contextPasteInsertsIntoEmptyAndPopulatedFieldsAtTheActualSelection() {
        compose.runOnIdle {
            val editor = compose.activity.findViewById<EditText>(R.id.plain_editor)
            editor.requestFocus()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            val prior = clipboard.primaryClip
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "hello test"))
                editor.setText("")
                editor.setSelection(0)
                val connection = editor.onCreateInputConnection(EditorInfo())
                assertTrue(connection.performContextMenuAction(android.R.id.paste))
                assertEquals("hello test", editor.text.toString())
                editor.setText("Before  after")
                editor.setSelection(7)
                assertTrue(connection.performContextMenuAction(android.R.id.paste))
                assertEquals("Before hello test after", editor.text.toString())
                editor.setSelection(0, 6)
                assertTrue(connection.performContextMenuAction(android.R.id.paste))
                assertEquals("hello test hello test after", editor.text.toString())
            } finally {
                if (prior != null) clipboard.setPrimaryClip(prior) else clipboard.clearPrimaryClip()
            }
        }
    }

    @Test
    fun accessibilityPastePreservesATypedWordWithAStableComposingSpan() {
        compose.runOnIdle {
            val editor = compose.activity.findViewById<EditText>(R.id.plain_editor)
            editor.requestFocus()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            val prior = clipboard.primaryClip
            try {
                editor.setText("typed")
                BaseInputConnection.setComposingSpans(editor.text)
                editor.setSelection(5)
                clipboard.setPrimaryClip(ClipData.newPlainText("fixture", " continuation"))
                assertTrue(
                    editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE, null)
                )
                assertEquals("typed continuation", editor.text.toString())
            } finally {
                if (prior != null) clipboard.setPrimaryClip(prior) else clipboard.clearPrimaryClip()
            }
        }
    }
}
