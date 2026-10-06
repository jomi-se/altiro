package org.altiro.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.altiro.core.Phase
import org.altiro.core.SessionEvent
import org.altiro.core.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Framework regression: these paths never capture audio or require installed weights. */
@RunWith(AndroidJUnit4::class)
class ServiceStartupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val controller = (context.applicationContext as AltiroApplication).controller

    @Test
    fun cancelBeforeServiceDeliveryDoesNotCrashOrCapture() {
        exercise(SessionId(9001), Phase.IDLE) { controller.cancel() }
    }

    @Test
    fun stopBeforeServiceDeliveryReportsFailureWithoutCrashing() {
        exercise(SessionId(9002), Phase.FAILED) { controller.stop() }
    }

    @Test
    fun missingMicrophonePermissionReportsFailureWithoutCrashing() {
        // Fresh CI installs have no runtime grant. Never revoke a user's existing permission.
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        )
        exercise(SessionId(9003), Phase.FAILED) {}
    }

    private fun exercise(id: SessionId, expected: Phase, beforeDelivery: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                instrumentation.runOnMainSync {
                    controller.discard()
                    // Inject only the actor's STARTING state. No model or audio is involved.
                    controller.event(SessionEvent.Start(id, null))
                    context.startForegroundService(
                        DictationRecordingService.intent(
                            context,
                            DictationRecordingService.START,
                            id,
                        )
                    )
                    // onStartCommand cannot run until this main-thread block returns.
                    beforeDelivery()
                }
                // Allow asynchronous startup/teardown failures to reach the parent process.
                Thread.sleep(8_000)
                instrumentation.waitForIdleSync()
                assertEquals(Lifecycle.State.RESUMED, activity.state)
                assertEquals(expected, controller.session.value.phase)
                assertNull(controller.session.value.text)
            } finally {
                instrumentation.runOnMainSync { controller.discard() }
            }
        }
    }
}
