/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate

class ProtectedMediaCommitObserverTest {
    private val name = ".yizhe-original-content.yzm"

    @Test fun `sent event before delayed local projection is retained for exact original transaction`() = runTest {
        val facts = mutableListOf<MediaSubmissionFact>()
        val observer = ProtectedMediaCommitObserver(name, backgroundScope, { facts += it }, { facts += it })
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("other", "\$other"))
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original-sdk", "\$sent"))
        observer.localCleanupCompleted(1100)
        assertNull(observer.fact())
        observer.bindOriginalTransaction("original-sdk")
        val result = observer.fact()!!
        assertEquals("\$sent", result.eventId)
        assertEquals("original-sdk", result.transactionId)
        assertEquals(1100L, result.localCleanupAtEpochMillis)
        assertEquals(1, facts.count { it.phase == MediaSubmissionPhase.ACCEPTED })
    }

    @Test fun `local echo and unrelated sent event cannot prove this original committed`() = runTest {
        var accepted = false
        val observer = ProtectedMediaCommitObserver(name, backgroundScope, {}, { accepted = true })
        observer.bindOriginalTransaction("original")
        observer.localCleanupCompleted(1100)
        observer.onUpdate(RoomSendQueueUpdate.NewLocalEvent("original"))
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("other", "\$foreign"))
        runCurrent()
        assertNull(observer.fact())
        assertFalse(accepted)
    }

    @Test fun `positive commit cannot complete before actual local cleanup`() = runTest {
        val order = mutableListOf<String>()
        val observer = ProtectedMediaCommitObserver(name, backgroundScope, { order += "retain" }, { order += "accepted" })
        observer.closeSubscription = { order += "close" }
        observer.bindOriginalTransaction("original")
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original", "\$sent"))
        runCurrent()
        assertNull(observer.fact())
        assertFalse("close" in order)
        observer.localCleanupCompleted(1200)
        assertEquals(listOf("retain", "retain", "accepted", "close"), order)
        observer.localCleanupCompleted(1500)
        assertEquals(1200L, observer.fact()!!.localCleanupAtEpochMillis)
    }

    @Test fun `transaction rebinding and ambiguous original events stay fail closed`() = runTest {
        val observer = ProtectedMediaCommitObserver(name, backgroundScope, {}, { fail("Ambiguous commit") })
        observer.bindOriginalTransaction("original")
        try { observer.bindOriginalTransaction("replacement"); fail("Transaction replaced") } catch (_: IllegalArgumentException) { }
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original", "\$first"))
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original", "\$second"))
        observer.localCleanupCompleted(1100)
        assertNull(observer.fact())
    }

    @Test fun `durable fact failure leaves same original retryable without another dispatch`() = runTest {
        var writes = 0
        var accepted = 0
        val observer = ProtectedMediaCommitObserver(name, backgroundScope,
            { if (++writes == 1) throw IllegalStateException("storage unavailable") }, { accepted++ })
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original", "\$sent"))
        try { observer.bindOriginalTransaction("original"); fail("Storage failure") } catch (_: IllegalStateException) { }
        assertNull(observer.fact())
        observer.bindOriginalTransaction("original")
        observer.localCleanupCompleted(1100)
        assertEquals("\$sent", observer.fact()!!.eventId)
        assertEquals(1, accepted)
    }

    @Test fun `late SDK success after finite waiter still persists original acceptance`() = runTest {
        var accepted: MediaSubmissionFact? = null
        val observer = ProtectedMediaCommitObserver(name, backgroundScope, {}, { accepted = it })
        observer.bindOriginalTransaction("original")
        observer.localCleanupCompleted(1100)
        assertNull(observer.fact())
        observer.onUpdate(RoomSendQueueUpdate.SentEvent("original", "\$late"))
        runCurrent()
        assertEquals("\$late", accepted!!.eventId)
        assertEquals("original", accepted!!.transactionId)
    }
}
