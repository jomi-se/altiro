package org.altiro.core

/** A paste must not send Enter or terminal control codes, even without bracketed-paste mode. */
fun terminalPasteText(text: String): String =
    buildString {
            for (character in text) {
                when {
                    character == '\n' ||
                        character == '\r' ||
                        character == '\t' ||
                        character == '\u2028' ||
                        character == '\u2029' -> append(' ')
                    character.code in 0..31 || character.code in 127..159 -> Unit
                    else -> append(character)
                }
            }
        }
        .trim()
