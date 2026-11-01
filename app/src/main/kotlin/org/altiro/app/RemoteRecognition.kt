package org.altiro.app

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.altiro.core.RecognitionDiagnostics
import org.altiro.core.RecognitionRoute
import org.altiro.core.RecognitionStage
import org.altiro.core.SessionId
import org.altiro.network.RemoteSpeechClient
import org.altiro.network.RemoteSpeechDecision
import org.altiro.network.RemoteSpeechEndpoint
import org.altiro.network.RemoteSpeechException
import org.altiro.network.remoteSpeechDecision

/** A configured server is the only remote destination. Each recording freezes its endpoint. */
internal class RemoteRecognition(private val context: Context, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val endpoint =
        MutableStateFlow(
            RemoteSpeechEndpoint.parse(preferences.getString("speech-endpoint", "").orEmpty())
                ?.normalized
                .orEmpty()
        )
    val enabled = MutableStateFlow(preferences.getBoolean("remote-first", true))
    val route = MutableStateFlow(RecognitionRoute.LOCAL)
    val status = MutableStateFlow<String?>(null)
    val checking = MutableStateFlow(false)
    val configured: Boolean
        get() = enabled.value && endpoint.value.isNotEmpty()

    private class Attempt(
        val id: SessionId,
        val endpoint: RemoteSpeechEndpoint,
        val client: RemoteSpeechClient = RemoteSpeechClient(),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    ) {
        lateinit var readiness: Deferred<Result<Boolean>>
    }

    private var active: Attempt? = null
    private var testClient: RemoteSpeechClient? = null
    private var testJob: Job? = null
    private var testCancelled: AtomicBoolean? = null

    fun save(value: String): Boolean {
        val parsed = if (value.isBlank()) null else RemoteSpeechEndpoint.parse(value)
        if (value.isNotBlank() && parsed == null) {
            status.value = "Use your server’s HTTPS address."
            return false
        }
        cancel()
        endpoint.value = parsed?.normalized.orEmpty()
        preferences.edit().putString("speech-endpoint", endpoint.value).apply()
        route.value = RecognitionRoute.LOCAL
        status.value =
            if (parsed == null) "Server removed · local only" else "Saved · ready to test"
        return true
    }

    fun enable(value: Boolean) {
        cancel()
        enabled.value = value
        preferences.edit().putBoolean("remote-first", value).apply()
        route.value = RecognitionRoute.LOCAL
        status.value = null
    }

    fun test() {
        val target = RemoteSpeechEndpoint.parse(endpoint.value) ?: return
        cancelTest()
        val client = RemoteSpeechClient()
        val cancelled = AtomicBoolean(false)
        testCancelled = cancelled
        testClient = client
        route.value = RecognitionRoute.CHECKING
        checking.value = true
        status.value = "Checking server…"
        testJob = scope.launch {
            try {
                val ready = withContext(Dispatchers.IO) { client.health(target, cancelled::get) }
                if (testClient === client) {
                    route.value =
                        if (ready && enabled.value) RecognitionRoute.REMOTE_GPU
                        else RecognitionRoute.LOCAL
                    status.value =
                        if (ready) "GPU ready · large-v3-turbo" else "Unavailable · using local"
                }
            } catch (_: CancellationException) {
                // Superseded configuration/test; no stale UI result.
            } catch (_: RemoteSpeechException) {
                if (testClient === client) {
                    route.value = RecognitionRoute.LOCAL
                    status.value = "Unavailable · using local"
                }
            } finally {
                if (testClient === client) {
                    testClient = null
                    testCancelled = null
                    checking.value = false
                }
            }
        }
    }

    fun prepare(id: SessionId, target: RemoteSpeechEndpoint, diagnostics: RecognitionDiagnostics) {
        cancel()
        val attempt = Attempt(id, target)
        active = attempt
        route.value = RecognitionRoute.CHECKING
        diagnostics.route(RecognitionRoute.CHECKING)
        attempt.readiness =
            scope.async(Dispatchers.IO) {
                val started = System.nanoTime()
                val result = runCatching { attempt.client.health(target, attempt.cancelled::get) }
                withContext(Dispatchers.Main.immediate) {
                    if (active === attempt && !attempt.cancelled.get()) {
                        diagnostics.remoteProbe(
                            ((System.nanoTime() - started) / 1_000_000).coerceIn(0, 60_000)
                        )
                        route.value =
                            if (result.getOrNull() == true) RecognitionRoute.REMOTE_GPU
                            else RecognitionRoute.LOCAL
                        diagnostics.route(route.value)
                    }
                }
                result
            }
    }

    suspend fun transcribe(
        id: SessionId,
        audio: File,
        language: String,
        prompt: String,
        diagnostics: RecognitionDiagnostics,
    ): RemoteSpeechDecision {
        val attempt = active?.takeIf { it.id == id } ?: throw CancellationException()
        diagnostics.phase(RecognitionStage.REMOTE_CHECK)
        val readiness = attempt.readiness.await()
        if (attempt.cancelled.get() || active !== attempt) throw CancellationException()
        if (readiness.getOrNull() == true) {
            route.value = RecognitionRoute.REMOTE_GPU
            diagnostics.route(RecognitionRoute.REMOTE_GPU)
            diagnostics.phase(RecognitionStage.REMOTE_REQUEST)
        }
        val decision =
            withContext(Dispatchers.IO) {
                remoteSpeechDecision(
                    cancelled = attempt.cancelled::get,
                    readiness = { readiness.getOrThrow() },
                    transcribe = {
                        attempt.client.transcribe(
                            attempt.endpoint,
                            audio,
                            language,
                            prompt,
                            attempt.cancelled::get,
                        )
                    },
                )
            }
        if (active !== attempt || attempt.cancelled.get()) throw CancellationException()
        if (decision is RemoteSpeechDecision.LocalFallback) {
            route.value = RecognitionRoute.LOCAL
            diagnostics.route(RecognitionRoute.LOCAL, decision.code)
        }
        active = null
        return decision
    }

    fun local(diagnostics: RecognitionDiagnostics) {
        route.value = RecognitionRoute.LOCAL
        diagnostics.route(RecognitionRoute.LOCAL)
    }

    fun cancel() {
        active?.let {
            it.cancelled.set(true)
            it.client.cancel()
            it.readiness.cancel()
        }
        active = null
        cancelTest()
    }

    private fun cancelTest() {
        testCancelled?.set(true)
        testCancelled = null
        testClient?.cancel()
        testClient = null
        testJob?.cancel()
        testJob = null
        checking.value = false
    }
}
