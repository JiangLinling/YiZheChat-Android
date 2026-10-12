/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SendHandle
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableProtectedMediaUploadTest {
    @Test fun `original SDK commit completes when raw projection vanished with one native join and cleanup`() = runTest {
        var joins = 0
        var clears = 0
        var cleanupAt: Long? = null
        var acceptedFact: io.element.android.libraries.matrix.api.media.MediaSubmissionFact? = null
        val observer = io.element.android.libraries.matrix.impl.timeline.ProtectedMediaCommitObserver(
            ".yizhe-exact-carrier.yzm", backgroundScope, {}, {},
        )
        observer.onUpdate(org.matrix.rustcomponents.sdk.RoomSendQueueUpdate.SentEvent("original-txn", "\$original-event"))
        observer.bindOriginalTransaction("original-txn")
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, { joins++ }, {},
            { emptyList() }, { clears++ }, 500,
            accepted = { acceptedFact = it }, observeCommit = { observer.fact() },
            cleanupCompleted = { cleanupAt = it; observer.localCleanupCompleted(it) },
        )
        assertThat(upload.await().isSuccess).isTrue()
        assertThat(joins).isEqualTo(1)
        assertThat(clears).isEqualTo(1)
        assertThat(acceptedFact?.transactionId).isEqualTo("original-txn")
        assertThat(acceptedFact?.eventId).isEqualTo("\$original-event")
        assertThat(acceptedFact?.localCleanupAtEpochMillis).isEqualTo(cleanupAt)
    }

    @Test fun `empty projection and native join alone still time out unknown`() = runTest {
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, {}, {}, { emptyList() }, {}, 100)
        assertThat(upload.await().exceptionOrNull()).isInstanceOf(io.element.android.libraries.matrix.api.media.MediaSendOutcomeUnknownException::class.java)
    }

    @Test fun `cancel followed by original confirmation retains first cleanup time`() = runTest {
        var cleared = 0
        val times = mutableListOf<Long>()
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, {}, {}, { emptyList() }, { cleared++ }, 100,
            cleanupCompleted = { times += it },
        )
        assertThat(upload.cancelPending().getOrThrow()).isEqualTo(MediaSendCancellationResult.Unknown)
        assertThat(upload.await().exceptionOrNull()).isInstanceOf(io.element.android.libraries.matrix.api.media.MediaSendOutcomeUnknownException::class.java)
        assertThat(cleared).isEqualTo(1)
        assertThat(times).hasSize(2)
        assertThat(times.distinct()).hasSize(1)
    }

    @Test fun `join error is reconciled against original authoritative commit rather than proving absence`() = runTest {
        val committed = io.element.android.libraries.matrix.api.media.MediaSubmissionFact(
            io.element.android.libraries.matrix.api.media.MediaSubmissionPhase.ACCEPTED, ".yizhe-exact-carrier.yzm", "\$original", "original-txn")
        var cleanups = 0
        var accepted = 0
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", backgroundScope,
            { error("native join failed after enqueue") }, {}, { emptyList() }, { cleanups++ }, 100,
            accepted = { accepted++ }, observeCommit = { committed })
        assertThat(upload.await().isSuccess).isTrue()
        assertThat(cleanups).isEqualTo(1)
        assertThat(accepted).isEqualTo(1)
    }

    @Test fun `actual cleanup after caller cancellation retains late commit without another await`() = runTest {
        val joined = CompletableDeferred<Unit>()
        var accepted = 0
        val observer = io.element.android.libraries.matrix.impl.timeline.ProtectedMediaCommitObserver(
            ".yizhe-exact-carrier.yzm", backgroundScope, {}, { accepted++ })
        observer.bindOriginalTransaction("original-txn")
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", backgroundScope,
            { joined.await() }, {}, { emptyList() }, {}, 100,
            observeCommit = { observer.fact() }, cleanupCompleted = { observer.localCleanupCompleted(it) })
        val caller = async { upload.await() }
        runCurrent()
        caller.cancel()
        observer.onUpdate(org.matrix.rustcomponents.sdk.RoomSendQueueUpdate.SentEvent("original-txn", "\$original"))
        runCurrent()
        assertThat(accepted).isEqualTo(0)
        joined.complete(Unit)
        runCurrent()
        assertThat(accepted).isEqualTo(1)
        assertThat(observer.fact()?.localCleanupAtEpochMillis).isGreaterThan(0L)
    }

    @Test fun `cleanup failure cannot produce original accepted fact despite SDK commit`() = runTest {
        var accepted = 0
        val observer = io.element.android.libraries.matrix.impl.timeline.ProtectedMediaCommitObserver(
            ".yizhe-exact-carrier.yzm", backgroundScope, {}, { accepted++ })
        observer.bindOriginalTransaction("original-txn")
        observer.onUpdate(org.matrix.rustcomponents.sdk.RoomSendQueueUpdate.SentEvent("original-txn", "\$original"))
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", backgroundScope, {}, {}, { emptyList() },
            { error("one derived file is still present") }, 100, observeCommit = { observer.fact() },
            cleanupCompleted = { observer.localCleanupCompleted(it) })
        assertThat(upload.await().isFailure).isTrue()
        assertThat(accepted).isEqualTo(0)
        assertThat(observer.fact()).isNull()
    }

    private fun event(state: LocalEventSendState) = MatrixTimelineItem.Event(
        UniqueId("sdk-media"), anEventTimelineItem(localSendState = state).copy(eventId = null, isRemote = false, isOwn = true),
    )

    @Test fun `native enqueue success does not prove commit and native join is called once`() = runTest {
        var joins = 0
        var clear = 0
        var items = emptyList<MatrixTimelineItem.Event>()
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, { joins++ }, {}, { items }, { clear++ }, 5000)
        val waiting = async { upload.await() }
        runCurrent()
        assertThat(waiting.isCompleted).isFalse()
        assertThat(clear).isEqualTo(1)
        items = listOf(MatrixTimelineItem.Event(UniqueId("remote"),
            anEventTimelineItem(eventId = EventId("\$sent")).copy(isOwn = true, isRemote = true), protectedMediaTransportName = ".yizhe-exact-carrier.yzm"))
        assertThat(waiting.await().isSuccess).isTrue()
        assertThat(joins).isEqualTo(1)
        assertThat(clear).isEqualTo(1)
        assertThat(upload.cancelPending().getOrThrow()).isEqualTo(MediaSendCancellationResult.Committed(EventId("\$sent")))
        assertThat(joins).isEqualTo(1)
    }

    @Test fun `exact upload queue is aborted but progress snapshots cannot prove event absence`() = runTest {
        val calls = mutableListOf<String>()
        val handle = object : SendHandle {
            override suspend fun retry(): Result<Unit> = error("Never resend during cancel")
            override suspend fun abort(): Result<Boolean> { calls += "queue-abort"; return Result.success(true) }
        }
        val item = event(LocalEventSendState.Sending.MediaWithProgress(0, 45, 100)).let {
            it.copy(event = it.event.copy(sendHandleProvider = { handle }))
        }
        val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, { calls += "native-exit" }, { calls += "native-cancel" },
            { listOf(item) }, { calls += "files-clear" }, 5000)
        assertThat(upload.cancelPending().getOrThrow()).isEqualTo(MediaSendCancellationResult.Unknown)
        assertThat(calls).containsExactly("native-cancel", "native-exit", "files-clear", "queue-abort").inOrder()
    }

    @Test fun `in flight carrier or failed abort remains unknown without key or source destruction`() = runTest {
        for (aborted in listOf(false, true)) {
            var cleared = false
            val handle = object : SendHandle {
                override suspend fun retry(): Result<Unit> = error("No resend")
                override suspend fun abort(): Result<Boolean> = Result.success(aborted)
            }
            val item = event(LocalEventSendState.Sending.Event).let { it.copy(event = it.event.copy(sendHandleProvider = { handle })) }
            val upload = CancellableProtectedMediaUpload(".yizhe-exact-carrier.yzm", this, {}, {}, { listOf(item) },
                { cleared = true }, 5000)
            assertThat(upload.cancelPending().getOrThrow()).isEqualTo(MediaSendCancellationResult.Unknown)
            assertThat(cleared).isTrue()
        }
    }

}
