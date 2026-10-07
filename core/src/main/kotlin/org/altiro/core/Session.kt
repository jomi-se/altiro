package org.altiro.core

@JvmInline value class SessionId(val value: Long)

enum class Phase {
    IDLE,
    STARTING,
    RECORDING,
    FINALIZING,
    TRANSCRIBING,
    READY,
    AWAITING_USER,
    DISPATCHED_UNCONFIRMED,
    FAILED,
}

data class Session(
    val id: SessionId? = null,
    val phase: Phase = Phase.IDLE,
    val destination: DestinationToken? = null,
    val text: String? = null,
    val attemptConsumed: Boolean = false,
    val dispatchFailed: Boolean = false,
    val message: String? = null,
    val elapsedSeconds: Int = 0,
) {
    val busy: Boolean
        get() =
            phase in setOf(Phase.STARTING, Phase.RECORDING, Phase.FINALIZING, Phase.TRANSCRIBING)
}

sealed interface SessionEvent {
    val id: SessionId

    data class Start(
        override val id: SessionId,
        val destination: DestinationToken?,
    ) : SessionEvent

    data class FirstFrame(override val id: SessionId) : SessionEvent

    data class Tick(
        override val id: SessionId,
        val seconds: Int,
    ) : SessionEvent

    data class Stop(override val id: SessionId) : SessionEvent

    data class AudioReady(override val id: SessionId) : SessionEvent

    data class Result(
        override val id: SessionId,
        val text: String,
    ) : SessionEvent

    data class ComparisonComplete(override val id: SessionId) : SessionEvent

    data class AwaitUser(
        override val id: SessionId,
        val reason: String,
    ) : SessionEvent

    data class Dispatch(override val id: SessionId) : SessionEvent

    data class Fail(
        override val id: SessionId,
        val reason: String,
    ) : SessionEvent

    data class Cancel(override val id: SessionId) : SessionEvent
}

/** Events are serialized by the application; old generations cannot mutate a new session. */
fun reduce(
    session: Session,
    event: SessionEvent,
): Session {
    if (event is SessionEvent.Start) {
        return if (session.busy || session.phase in setOf(Phase.READY, Phase.AWAITING_USER)) {
            session
        } else {
            Session(id = event.id, phase = Phase.STARTING, destination = event.destination)
        }
    }
    if (event.id != session.id) return session
    return when (event) {
        is SessionEvent.Start -> session
        is SessionEvent.FirstFrame ->
            if (session.phase == Phase.STARTING) session.copy(phase = Phase.RECORDING) else session
        is SessionEvent.Tick ->
            if (session.phase == Phase.RECORDING) session.copy(elapsedSeconds = event.seconds)
            else session
        is SessionEvent.Stop ->
            if (session.phase in setOf(Phase.STARTING, Phase.RECORDING)) {
                session.copy(phase = Phase.FINALIZING)
            } else {
                session
            }
        is SessionEvent.AudioReady ->
            if (session.phase == Phase.FINALIZING) session.copy(phase = Phase.TRANSCRIBING)
            else session
        is SessionEvent.Result ->
            if (session.phase == Phase.TRANSCRIBING)
                session.copy(phase = Phase.READY, text = event.text)
            else session
        is SessionEvent.ComparisonComplete ->
            if (session.phase == Phase.TRANSCRIBING) {
                session.copy(
                    phase = Phase.IDLE,
                    destination = null,
                    text = null,
                    message = "Comparison ready in Altiro.",
                )
            } else {
                session
            }
        is SessionEvent.AwaitUser ->
            if (session.phase in setOf(Phase.READY, Phase.AWAITING_USER)) {
                session.copy(phase = Phase.AWAITING_USER, message = event.reason)
            } else {
                session
            }
        is SessionEvent.Dispatch ->
            if (
                session.phase in setOf(Phase.READY, Phase.AWAITING_USER) && !session.attemptConsumed
            ) {
                session.copy(
                    phase = Phase.DISPATCHED_UNCONFIRMED,
                    attemptConsumed = true,
                    message = "Check whether the text was inserted.",
                )
            } else {
                session
            }
        is SessionEvent.Fail ->
            if (session.attemptConsumed) {
                session.copy(
                    message = "Insertion outcome unknown. Check the field before copying.",
                    dispatchFailed = true,
                )
            } else {
                session.copy(phase = Phase.FAILED, message = event.reason)
            }
        is SessionEvent.Cancel -> if (session.attemptConsumed) session else Session()
    }
}
