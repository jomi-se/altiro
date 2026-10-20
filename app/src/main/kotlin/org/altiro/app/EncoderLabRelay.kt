package org.altiro.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.network.LabRelayClient

/** Process-memory opt-in only. Coalescing keeps network work outside measurement callbacks. */
internal class EncoderLabRelay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow("Disconnected")
    val status = mutableStatus.asStateFlow()
    private var pending: Channel<ByteArray>? = null
    private var worker: Job? = null
    private var generation = 0L
    val connected: Boolean
        @Synchronized get() = pending != null

    @Synchronized
    fun connect(link: String, initial: ByteArray): Boolean {
        if (!BuildConfig.DEBUG) return false
        val client =
            try {
                LabRelayClient(link.trim())
            } catch (_: Exception) {
                return false
            }
        disconnect()
        val epoch = generation
        val channel = Channel<ByteArray>(Channel.CONFLATED)
        pending = channel
        mutableStatus.value = "Ready · waiting for lab"
        worker = scope.launch {
            for (first in channel) {
                delay(1_500)
                var latest = first
                while (true) latest = channel.tryReceive().getOrNull() ?: break
                val sent =
                    try {
                        client.send(latest)
                    } catch (_: Exception) {
                        false
                    }
                synchronized(this@EncoderLabRelay) {
                    if (epoch == generation)
                        mutableStatus.value =
                            if (sent) "Received by ${client.host}"
                            else "Not received · check Tailscale"
                }
            }
        }
        if (initial.decodeToString().trim() != "{}") channel.trySend(initial)
        return true
    }

    @Synchronized
    fun offer(report: ByteArray) {
        pending?.trySend(report)
    }

    @Synchronized
    fun disconnect() {
        generation++
        pending?.close()
        pending = null
        worker?.cancel()
        worker = null
        mutableStatus.value = "Disconnected"
    }
}
