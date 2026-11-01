package org.altiro.network

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RemoteSpeechDecisionTest {
    @Test
    fun unavailablePreflightNeverUploads() {
        var uploads = 0
        val upload = {
            uploads++
            "unexpected"
        }
        assertEquals(
            RemoteSpeechDecision.LocalFallback("remote_not_ready"),
            remoteSpeechDecision({ false }, { false }, upload),
        )
        assertEquals(
            RemoteSpeechDecision.LocalFallback("remote_busy"),
            remoteSpeechDecision({ false }, { throw RemoteSpeechException("remote_busy") }, upload),
        )
        assertEquals(0, uploads)
    }

    @Test
    fun successfulEmptyTranscriptIsTerminalAndFailureNeverReplays() {
        var uploads = 0
        assertEquals(
            RemoteSpeechDecision.Transcript(""),
            remoteSpeechDecision(
                { false },
                { true },
                {
                    uploads++
                    ""
                },
            ),
        )
        assertEquals(1, uploads)
        assertEquals(
            RemoteSpeechDecision.LocalFallback("remote_timeout"),
            remoteSpeechDecision(
                { false },
                { true },
                {
                    uploads++
                    throw RemoteSpeechException("remote_timeout")
                },
            ),
        )
        assertEquals(2, uploads)
    }

    @Test
    fun cancellationWinsBeforeReadinessBetweenStagesAndAfterSuccessfulOrFailedPost() {
        for (stage in 0..3) {
            var cancelled = stage == 0
            var uploads = 0
            assertThrows(CancellationException::class.java) {
                remoteSpeechDecision(
                    { cancelled },
                    {
                        if (stage == 1) cancelled = true
                        true
                    },
                    {
                        uploads++
                        cancelled = true
                        if (stage == 3) throw RemoteSpeechException("remote_transport_error")
                        "late success"
                    },
                )
            }
            assertEquals(if (stage < 2) 0 else 1, uploads)
        }
    }

    @Test
    fun transportCancellationAndCoroutineCancellationNeverBecomeFallback() {
        assertThrows(CancellationException::class.java) {
            remoteSpeechDecision(
                { false },
                {
                    throw RemoteSpeechException("remote_cancelled")
                },
                { "never" },
            )
        }
        assertThrows(CancellationException::class.java) {
            remoteSpeechDecision({ false }, { true }, { throw CancellationException() })
        }
    }
}
