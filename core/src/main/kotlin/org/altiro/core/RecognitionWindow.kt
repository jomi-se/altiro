package org.altiro.core

enum class RecognitionWindow(val label: String) {
    NATIVE("Native audio length · 30s cap"),
    FULL("Full 30-second window"),
    DYNAMIC("Dynamic window"),
}
