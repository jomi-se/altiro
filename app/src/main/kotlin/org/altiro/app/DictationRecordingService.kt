package org.altiro.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.altiro.core.Phase
import org.altiro.core.SessionEvent
import org.altiro.core.SessionId
import org.altiro.core.Wav
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class DictationRecordingService : Service() {
    private val controller get() = (application as AltiroApplication).controller
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val captureLock = Any()

    @Volatile private var requested = START
    private var recorder: AudioRecord? = null
    private var activeId: SessionId? = null
    private var captureCompleted = false
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) controller.cancel()
            }
        }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording controls", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val id = intent?.getLongExtra(SESSION, -1)?.takeIf { it >= 0 }?.let(::SessionId)
        if (id == null || controller.session.value.id != id) {
            if (activeId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            START ->
                if (activeId == null) {
                    if (controller.session.value.phase == Phase.STARTING) {
                        beginCapture(id)
                    } else if (controller.session.value.phase == Phase.FINALIZING) {
                        fail(id, "Stopped before audio arrived. Start another microphone test.")
                    }
                }
            STOP ->
                if (activeId == id) {
                    controller.event(SessionEvent.Stop(id))
                    stopCapture(STOP)
                }
            CANCEL -> if (activeId == id) controller.cancel()
        }
        if (activeId == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun beginCapture(id: SessionId) {
        activeId = id
        requested = START
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startForeground(NOTIFICATION, notification(id), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: SecurityException) {
            fail(id, "Microphone startup was denied. Open the recording screen and check permissions.")
            return
        } catch (_: IllegalStateException) {
            fail(id, "The microphone is not available. Check permissions and try again.")
            return
        }
        controller.scope.launch {
            delay(10_000)
            if (controller.session.value.id == id && controller.session.value.phase == Phase.STARTING) {
                stopCapture(CANCEL)
                fail(id, "The microphone did not start. Check its privacy switch and try again.")
            }
        }
        executor.execute { capture(id) }
    }

    private fun capture(id: SessionId) {
        val directory = cacheDir.resolve("dictation").apply { mkdirs() }
        var file: java.io.File? = null
        var frames = 0L
        var error: String? = null
        var lastSecond = -1
        try {
            file = java.io.File.createTempFile("capture-", ".wav", directory)
            val minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Microphone permission unavailable")
            }
            val capture =
                AudioRecord
                    .Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(
                                Wav.SAMPLE_RATE,
                            ).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(maxOf(minimum * 2, 4096))
                    .build()
            synchronized(captureLock) {
                recorder = capture
                check(capture.state == AudioRecord.STATE_INITIALIZED)
                if (requested == START) capture.startRecording()
            }
            val buffer = ShortArray(640)
            val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            RandomAccessFile(file, "rw").use { output ->
                output.write(Wav.header(0))
                while (requested == START && frames < Wav.MAX_FRAMES) {
                    val count =
                        capture.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), Wav.MAX_FRAMES - frames).toInt(),
                            AudioRecord.READ_BLOCKING,
                        )
                    if (requested != START) break
                    check(count > 0 && capture.activeRecordingConfiguration?.isClientSilenced != true)
                    bytes.clear()
                    for (index in 0 until count) bytes.putShort(buffer[index])
                    output.write(bytes.array(), 0, count * 2)
                    if (frames == 0L) main.post { controller.event(SessionEvent.FirstFrame(id)) }
                    frames += count
                    val second = (frames / Wav.SAMPLE_RATE).toInt()
                    if (second != lastSecond) {
                        lastSecond = second
                        main.post { controller.event(SessionEvent.Tick(id, second)) }
                    }
                }
                if (frames >= Wav.MAX_FRAMES) requested = STOP
                output.seek(0)
                output.write(Wav.header(frames))
            }
            if (frames == 0L && requested != CANCEL) error = "No audio was captured. Check microphone access and try again."
        } catch (_: Exception) {
            if (requested != CANCEL) error = "Recording was interrupted or storage is unavailable. The temporary recording was discarded."
        } finally {
            synchronized(captureLock) {
                recorder?.let {
                    runCatching { it.stop() }
                    it.release()
                }
                recorder = null
            }
            // Fake recognition does not need audio. The worker owns deletion after release.
            file?.delete()
            val stopped = requested == STOP
            main.post {
                captureCompleted = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (controller.session.value.id == id) {
                    if (error != null) {
                        controller.event(SessionEvent.Fail(id, error!!))
                    } else if (stopped) {
                        controller.event(SessionEvent.Stop(id))
                        controller.finishFakeRecognition(id)
                    }
                }
                stopSelf()
            }
        }
    }

    private fun notification(id: SessionId): Notification {
        fun action(
            command: String,
            offset: Int,
        ): PendingIntent =
            PendingIntent.getService(
                this,
                (id.value * 3 + offset).toInt(),
                intent(this, command, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RecordingActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Altiro microphone test")
            .setContentText("Fixed test phrase · maximum 5 minutes")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", action(STOP, 0)).build())
            .addAction(Notification.Action.Builder(null, "Cancel", action(CANCEL, 1)).build())
            .build()
    }

    private fun stopCapture(command: String) {
        synchronized(captureLock) {
            requested = command
            runCatching { recorder?.stop() }
        }
    }

    private fun fail(
        id: SessionId,
        reason: String,
    ) {
        controller.event(SessionEvent.Fail(id, reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture(CANCEL)
        unregisterReceiver(screenOff)
        executor.shutdown()
        activeId?.let { id ->
            if (!captureCompleted && controller.session.value.id == id &&
                controller.session.value.busy
            ) {
                controller.event(SessionEvent.Cancel(id))
            }
        }
        super.onDestroy()
    }

    companion object {
        const val START = "org.altiro.START"
        const val STOP = "org.altiro.STOP"
        const val CANCEL = "org.altiro.CANCEL"
        private const val SESSION = "session"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1

        fun intent(
            context: Context,
            action: String,
            id: SessionId,
        ): Intent = Intent(context, DictationRecordingService::class.java).setAction(action).putExtra(SESSION, id.value)
    }
}
