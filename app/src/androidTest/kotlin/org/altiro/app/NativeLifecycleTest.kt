package org.altiro.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.altiro.inference.NativeProgress
import org.altiro.inference.NativeWhisper
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLifecycleTest {
    @Test
    fun preCancelledOperationDoesNotLoadMissingFiles() {
        val runtime = NativeWhisper()
        repeat(20) {
            val handle = runtime.create()
            try {
                runtime.cancel(handle)
                assertNull(
                    runtime.transcribe(handle, "unused", "unused", "es", false, NativeProgress {})
                )
            } finally {
                runtime.release(handle)
            }
            runtime.cancel(handle) // A stale handle must be harmless, not a freed pointer.
        }
    }
}
