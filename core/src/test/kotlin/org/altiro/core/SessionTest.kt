package org.altiro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val id = SessionId(1)

    private fun ready(): Session {
        var state = reduce(Session(), SessionEvent.Start(id, null))
        for (event in
            listOf(
                SessionEvent.FirstFrame(id),
                SessionEvent.Stop(id),
                SessionEvent.AudioReady(id),
                SessionEvent.Result(id, "café"),
            )) {
            state = reduce(state, event)
        }
        return state
    }

    @Test
    fun `copy only never retains destination or dispatches even after a normal field opens`() {
        val authority =
            EditorAuthority().apply {
                start(
                    EditorState(
                        EditorIdentity("org.example.editor", 1, 2, "field", 0),
                        selectionStart = 0,
                        selectionEnd = 0,
                        compositionKnown = true,
                        connectionAvailable = true,
                    )
                )
            }
        var session =
            reduce(Session(), SessionEvent.Start(id, authority.capture(), ResultDelivery.COPY_ONLY))
        assertEquals(null, session.destination)
        for (event in
            listOf(
                SessionEvent.FirstFrame(id),
                SessionEvent.Stop(id),
                SessionEvent.AudioReady(id),
                SessionEvent.Result(id, "echo hello\n"),
            )) session = reduce(session, event)
        authority.start(
            authority.current.copy(identity = authority.current.identity!!.copy(fieldId = 3))
        )
        assertEquals(null, authority.blockReason(authority.capture()))
        assertEquals(ResultDelivery.COPY_ONLY, session.delivery)
        assertEquals(session, reduce(session, SessionEvent.Dispatch(id)))
        val pending = reduce(session, SessionEvent.AwaitUser(id, "Copy"))
        assertEquals(pending, reduce(pending, SessionEvent.Dispatch(id)))
        assertFalse(pending.attemptConsumed)
        assertEquals("echo hello\n", pending.text)
        assertEquals(Session(), reduce(pending, SessionEvent.Cancel(id)))
    }

    @Test
    fun `cancel invalidates before stale recognition result arrives`() {
        val cancelled = reduce(ready(), SessionEvent.Cancel(id))
        assertEquals(Session(), reduce(cancelled, SessionEvent.Result(id, "late")))
        val next = reduce(cancelled, SessionEvent.Start(SessionId(2), null))
        assertEquals(next, reduce(next, SessionEvent.Fail(id, "late failure")))
    }

    @Test
    fun `duplicate result stop and dispatch cannot cause duplicate insertion`() {
        val ready = ready()
        assertEquals(ready, reduce(ready, SessionEvent.Stop(id)))
        assertEquals(ready, reduce(ready, SessionEvent.Result(id, "duplicate")))
        val attempted = reduce(ready, SessionEvent.Dispatch(id))
        assertTrue(attempted.attemptConsumed)
        assertEquals(attempted, reduce(attempted, SessionEvent.Dispatch(id)))
        assertEquals(attempted, reduce(attempted, SessionEvent.Cancel(id)))
        val failed = reduce(attempted, SessionEvent.Fail(id, "exception"))
        assertTrue(failed.attemptConsumed)
        assertTrue(failed.dispatchFailed)
    }

    @Test
    fun `consumed insertion permits a fresh recording without reviving old text`() {
        val dispatched = reduce(ready(), SessionEvent.Dispatch(id))
        val nextId = SessionId(2)
        val next = reduce(dispatched, SessionEvent.Start(nextId, null))
        assertEquals(Phase.STARTING, next.phase)
        assertEquals(null, next.text)
        assertFalse(next.attemptConsumed)
        assertEquals(next, reduce(next, SessionEvent.Dispatch(id)))
        assertEquals(next, reduce(next, SessionEvent.Result(id, "old payload")))
    }

    @Test
    fun `second start while busy or awaiting user is rejected`() {
        val starting = reduce(Session(), SessionEvent.Start(id, null))
        assertEquals(starting, reduce(starting, SessionEvent.Start(SessionId(2), null)))
        val awaiting = reduce(ready(), SessionEvent.AwaitUser(id, "Destination changed"))
        assertEquals(awaiting, reduce(awaiting, SessionEvent.Start(SessionId(2), null)))
        assertFalse(awaiting.attemptConsumed)
    }

    @Test
    fun `comparison has no insertion payload and stale completion is ignored`() {
        var state = reduce(Session(), SessionEvent.Start(id, null))
        for (event in
            listOf(
                SessionEvent.FirstFrame(id),
                SessionEvent.Stop(id),
                SessionEvent.AudioReady(id),
            )) state = reduce(state, event)
        val completed = reduce(state, SessionEvent.ComparisonComplete(id))
        assertEquals(Phase.IDLE, completed.phase)
        assertEquals(null, completed.text)
        assertEquals(null, completed.destination)
        assertEquals(completed, reduce(completed, SessionEvent.Dispatch(id)))
        val cancelled = reduce(state, SessionEvent.Cancel(id))
        assertEquals(cancelled, reduce(cancelled, SessionEvent.ComparisonComplete(id)))
        assertEquals(ready(), reduce(ready(), SessionEvent.ComparisonComplete(id)))
    }
}
