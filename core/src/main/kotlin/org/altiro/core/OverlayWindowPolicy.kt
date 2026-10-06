package org.altiro.core

/** Only a positively identified non-focusable overlay may be excluded. */
fun windowChangeInvalidatesDestination(changedWindowId: Int, ownedOverlayId: Int?): Boolean =
    ownedOverlayId == null || changedWindowId < 0 || changedWindowId != ownedOverlayId
