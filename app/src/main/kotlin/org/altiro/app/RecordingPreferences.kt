package org.altiro.app

import android.content.Context

internal class RecordingPreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    // Start the product setting afresh: the old debug probe was off by default.
    var recordInPlace: Boolean
        get() = preferences.getBoolean("record-in-place", true)
        set(value) {
            preferences.edit().putBoolean("record-in-place", value).apply()
        }
}
