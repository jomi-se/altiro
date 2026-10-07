package org.altiro.core

/** Known keyboard/system geometry is not an editor change; live focus is still rechecked. */
fun windowChangeInvalidatesDestination(
    changedWindowId: Int,
    ownedOverlayId: Int?,
    knownNonApplicationWindow: Boolean = false,
): Boolean =
    changedWindowId < 0 || (changedWindowId != ownedOverlayId && !knownNonApplicationWindow)
