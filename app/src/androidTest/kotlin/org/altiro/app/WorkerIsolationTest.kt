package org.altiro.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device execution is required: native failure must leave the parent app alive. */
@RunWith(AndroidJUnit4::class)
class WorkerIsolationTest {
    @Test
    fun workerFailureCanBeReportedAndWorkerTerminatedWithoutKillingParent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = ComponentName(context, RecognitionWorkerService::class.java)
        val serviceInfo =
            context.packageManager.getServiceInfo(name, PackageManager.ComponentInfoFlags.of(0))
        assertNotEquals(context.packageName, serviceInfo.processName)
        assertTrue(!serviceInfo.exported)
        val connected = CountDownLatch(1)
        val done = CountDownLatch(1)
        val died = CountDownLatch(1)
        var remote: IRecognitionWorker? = null
        var status = -1
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    binder.linkToDeath({ died.countDown() }, 0)
                    remote = IRecognitionWorker.Stub.asInterface(binder)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    died.countDown()
                }
            }
        assertTrue(
            context.bindService(
                Intent(context, RecognitionWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        var bound = true
        try {
            assertTrue(connected.await(20, TimeUnit.SECONDS))
            remote!!.run(
                1,
                "missing-model",
                "missing-audio",
                "es",
                false,
                true,
                true,
                "Ñuñoa, 𝔸ltiro",
                object : IRecognitionCallback.Stub() {
                    override fun onProgress(percent: Int) {}

                    override fun onPhase(phase: Int) {}

                    override fun onRuntime(report: String) {
                        assertTrue(!report.contains("missing-audio") && !report.contains("Ñuñoa"))
                    }

                    override fun onTextChunk(text: String): Unit =
                        throw AssertionError("Failed inference produced text")

                    override fun onComplete(value: Int) {
                        status = value
                        done.countDown()
                    }
                },
            )
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(2, status)
            context.unbindService(connection)
            bound = false
            try {
                remote!!.shutdown()
            } catch (_: Exception) {}
            assertTrue(died.await(10, TimeUnit.SECONDS))
            assertTrue(context.packageName.isNotBlank())
        } finally {
            if (bound) context.unbindService(connection)
        }
    }
}
