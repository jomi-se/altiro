package org.altiro.app

import android.content.Context

internal class RecordingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    // Start the product setting afresh: the old debug probe was off by default.
    var recordInPlace: Boolean
        get() = preferences.getBoolean("record-in-place", true)
        set(value) {
            preferences.edit().putBoolean("record-in-place", value).apply()
        }

    // Termux can disable the shortcut and interpret it as control input instead.
    // Enable only after the user confirms its hardware shortcuts are available.
    var termuxPaste: Boolean
        get() = preferences.getBoolean("termux-paste-confirmed", false)
        set(value) {
            preferences.edit().putBoolean("termux-paste-confirmed", value).apply()
        }
}
