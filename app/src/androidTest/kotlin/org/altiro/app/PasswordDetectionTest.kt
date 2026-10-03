package org.altiro.app

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordDetectionTest {
    @Test fun passwordVariantsCannotBecomeDictationDestinations() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            assertTrue(DictationAccessibilityService.isPassword(InputType.TYPE_CLASS_TEXT or variation))
        }
        assertTrue(DictationAccessibilityService.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(DictationAccessibilityService.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }
}
