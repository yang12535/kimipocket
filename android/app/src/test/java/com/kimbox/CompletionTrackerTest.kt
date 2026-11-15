package com.kimbox

import org.junit.Assert.*
import org.junit.Test

class CompletionTrackerTest {
    @Test fun completionNotifiesOnceAfterActiveTurn() {
        val t = CompletionTracker()
        assertNull(t.observe("session_a", false, "completed")) // old session
        assertNull(t.observe("session_a", true, "completed")) // previous turn's reason
        assertEquals("completed", t.observe("session_a", false, "completed"))
        assertNull(t.observe("session_a", false, "completed"))
    }

    @Test fun cancelledAndBackgroundWorkAreNotCompletion() {
        val t = CompletionTracker()
        t.observe("session_a", true, null)
        assertNull(t.observe("session_a", false, "cancelled"))
        assertNull(t.observe("session_a", false, "completed")) // background/compaction update
    }

    @Test fun errorsAreDistinctAndSessionsIndependent() {
        val t = CompletionTracker()
        t.observe("a", true, null)
        t.observe("b", true, null)
        assertEquals("failed", t.observe("a", false, "failed"))
        assertEquals("completed", t.observe("b", false, "completed"))
    }

    @Test fun reconnectCanRecoverAnObservedTurn() {
        val t = CompletionTracker()
        t.observe("a", true, null)
        val rev = t.revision()
        assertEquals("completed", t.reconcile("a", false, "completed", rev))
        assertNull(t.reconcile("old", false, "completed", rev))
    }

    @Test fun staleSnapshotDoesNotRearmFinishedTurn() {
        val t = CompletionTracker()
        t.observe("a", true, null)
        val rev = t.revision()
        assertEquals("completed", t.observe("a", false, "completed"))
        assertNull(t.reconcile("a", true, null, rev))
        assertNull(t.observe("a", false, "completed"))
    }

    @Test fun deepLinksOnlyAcceptSessionIdentifiers() {
        for (id in listOf("", "../secret", "a?token=x", "https://example.com", "__global__", "a".repeat(161))) {
            assertFalse(id, CompletionTracker.validSessionId(id))
        }
        assertTrue(CompletionTracker.validSessionId("session_01ABC-def"))
    }
}
