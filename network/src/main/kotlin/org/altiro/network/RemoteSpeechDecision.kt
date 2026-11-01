package org.altiro.network

import java.util.concurrent.CancellationException

sealed interface RemoteSpeechDecision {
    data class Transcript(val text: String) : RemoteSpeechDecision

    data class LocalFallback(val code: String) : RemoteSpeechDecision
}

/** One remote attempt, with a local-only fallback decision. Never owns or deletes audio. */
fun remoteSpeechDecision(
    cancelled: () -> Boolean,
    readiness: () -> Boolean,
    transcribe: () -> String,
): RemoteSpeechDecision {
    fun checkCancellation() {
        if (cancelled()) throw CancellationException("remote_cancelled")
    }
    checkCancellation()
    return try {
        val ready = readiness()
        checkCancellation()
        if (!ready) RemoteSpeechDecision.LocalFallback("remote_not_ready")
        else {
            val text = transcribe()
            checkCancellation()
            RemoteSpeechDecision.Transcript(text)
        }
    } catch (error: RemoteSpeechException) {
        checkCancellation()
        if (error.code == "remote_cancelled") throw CancellationException("remote_cancelled")
        RemoteSpeechDecision.LocalFallback(error.code)
    }
}
