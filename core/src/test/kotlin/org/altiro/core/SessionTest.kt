package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val id = SessionId(1)

    private fun ready(): Session {
        var state = reduce(Session(), SessionEvent.Start(id, null))
        for (event in listOf(
            SessionEvent.FirstFrame(id),
            SessionEvent.Stop(id),
            SessionEvent.AudioReady(id),
            SessionEvent.Result(id, "café"),
        )) {
            state =
                reduce(state, event)
        }
        return state
    }

    @Test fun `cancel invalidates before stale recognition result arrives`() {
        val cancelled = reduce(ready(), SessionEvent.Cancel(id))
        assertEquals(Session(), reduce(cancelled, SessionEvent.Result(id, "late")))
        val next = reduce(cancelled, SessionEvent.Start(SessionId(2), null))
        assertEquals(next, reduce(next, SessionEvent.Fail(id, "late failure")))
    }

    @Test fun `duplicate result stop and dispatch cannot cause duplicate insertion`() {
        val ready = ready()
        assertEquals(ready, reduce(ready, SessionEvent.Stop(id)))
        assertEquals(ready, reduce(ready, SessionEvent.Result(id, "duplicate")))
        val attempted = reduce(ready, SessionEvent.Dispatch(id))
        assertTrue(attempted.attemptConsumed)
        assertEquals(attempted, reduce(attempted, SessionEvent.Dispatch(id)))
        assertEquals(attempted, reduce(attempted, SessionEvent.Cancel(id)))
        assertTrue(reduce(attempted, SessionEvent.Fail(id, "exception")).attemptConsumed)
    }

    @Test fun `second start while busy or awaiting user is rejected`() {
        val starting = reduce(Session(), SessionEvent.Start(id, null))
        assertEquals(starting, reduce(starting, SessionEvent.Start(SessionId(2), null)))
        val awaiting = reduce(ready(), SessionEvent.AwaitUser(id, "Destination changed"))
        assertEquals(awaiting, reduce(awaiting, SessionEvent.Start(SessionId(2), null)))
        assertFalse(awaiting.attemptConsumed)
    }
}
