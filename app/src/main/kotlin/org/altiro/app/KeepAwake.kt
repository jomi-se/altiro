package org.altiro.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Applies only while this UI is visible. A manual lock still cancels the session. */
@Composable
internal fun KeepAwake(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val previous = view.keepScreenOn
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = previous }
    }
}
