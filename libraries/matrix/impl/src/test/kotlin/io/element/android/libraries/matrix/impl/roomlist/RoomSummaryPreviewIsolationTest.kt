/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoom
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomListService
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedMessageDisplayState
import io.element.android.libraries.matrix.impl.timeline.TimelineReleaseScopeOwnerRegistry
import io.element.android.libraries.matrix.impl.timeline.withProtectedDisplayState
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID_3
import io.element.android.libraries.matrix.test.A_ROOM_ID_4
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineReleaseScopeOwnerToken
import io.element.android.services.analytics.test.FakeAnalyticsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.LatestEventValue as RustLatestEventValue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSummaryPreviewIsolationTest {
    @Test
    fun `two lists previewing the same room do not retire each other`() = runTest {
        assertConcurrentOwners(separateFactories = false)
    }

    @Test
    fun `independent factories sharing a release service do not collide`() = runTest {
        assertConcurrentOwners(separateFactories = true)
    }

    private suspend fun TestScope.assertConcurrentOwners(separateFactories: Boolean) {
        val firstMayFinish = CompletableDeferred<Unit>()
        val secondMayFinish = CompletableDeferred<Unit>()
        var calls = 0
        val adapter = PreviewAdapter { message, intent ->
            if (++calls == 1) firstMayFinish.await() else secondMayFinish.await()
            checkNotNull(registry.resolve(intent.scopeId)) { "Another preview retired this active owner" }
            authorized(message)
        }
        val firstFactory = factory(adapter)
        val secondFactory = if (separateFactories) factory(adapter) else firstFactory
        val first = async { firstFactory.create(room()) }
        runCurrent()
        val second = async { secondFactory.create(room()) }
        runCurrent()
        assertThat(adapter.activeHandles).hasSize(2)
        assertThat(adapter.rawScopes.distinct()).hasSize(2)

        firstMayFinish.complete(Unit)
        assertThat(first.await().message().isContentProtectionAuthorized).isTrue()
        assertThat(adapter.activeHandles).hasSize(1)
        secondMayFinish.complete(Unit)
        assertThat(second.await().message().isContentProtectionAuthorized).isTrue()
        assertThat(adapter.activeHandles).isEmpty()
    }

    @Test
    fun `request cancellation preserves the room with a safe preview and closes its owner`() = runTest {
        val adapter = PreviewAdapter { _, _ -> throw CancellationException("Expired request owner") }
        val summary = factory(adapter).create(room())
        assertThat(summary.roomId).isEqualTo(A_ROOM_ID)
        assertSafe(summary.message(), ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
        assertThat(adapter.activeHandles).isEmpty()
    }

    @Test
    fun `preview failure before owner creation cannot abort the structural room`() = runTest {
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun observedRoomSummaryEventIdentity(timestamp: Long, sender: String, message: MessageContent): String? {
                throw CancellationException("Carrier lookup retired")
            }
        }
        val summary = factory(adapter).create(room())
        assertThat(summary.roomId).isEqualTo(A_ROOM_ID)
        assertSafe(summary.message(), ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
    }

    @Test
    fun `real parent cancellation is propagated and does not publish a preview`() = runTest {
        val adapter = PreviewAdapter { _, _ -> awaitCancellation() }
        var published: RoomSummary? = null
        val parent = async { factory(adapter).create(room()).also { published = it } }
        runCurrent()
        assertThat(adapter.activeHandles).hasSize(1)
        parent.cancel()
        parent.join()
        assertThat(parent.isCancelled).isTrue()
        assertThat(published).isNull()
        assertThat(adapter.activeHandles).isEmpty()
    }

    @Test
    fun `reset append set and following indexed diffs survive individual preview failures`() = runTest {
        var calls = 0
        val adapter = PreviewAdapter { message, _ ->
            when (++calls) {
                2 -> throw CancellationException("Retired preview")
                3 -> throw ContentProtectionException.Expired()
                4 -> throw ContentProtectionException.Revoked()
                else -> authorized(message)
            }
        }
        val summaries = MutableSharedFlow<List<RoomSummary>>(replay = 1)
        val processor = RoomSummaryListProcessor(
            summaries, FakeFfiRoomListService(), StandardTestDispatcher(testScheduler), factory(adapter), FakeAnalyticsService(),
        )
        val snapshots = mutableListOf<List<RoomSummary>>()
        // Matches the downstream RoomListFactory.onEach boundary; failed previews must not skip a diff.
        listOf(
            listOf(RoomListEntriesUpdate.Reset(listOf(room(A_ROOM_ID), room(A_ROOM_ID_2)))),
            listOf(RoomListEntriesUpdate.Append(listOf(room(A_ROOM_ID_3)))),
            listOf(RoomListEntriesUpdate.Set(1u, room(A_ROOM_ID_4))),
            listOf(RoomListEntriesUpdate.Remove(0u)),
            listOf(RoomListEntriesUpdate.PushBack(room(A_ROOM_ID))),
        ).asFlow().onEach { updates ->
            processor.postUpdate(updates)
            snapshots += summaries.replayCache.last()
        }.collect()

        assertThat(snapshots).hasSize(5)
        assertThat(snapshots[0].map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2).inOrder()
        assertSafe(snapshots[0][1].message(), ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
        assertThat(snapshots[1].map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3).inOrder()
        assertSafe(snapshots[1][2].message(), ProtectedMessageStatus.EXPIRED)
        assertThat(snapshots[2].map { it.roomId }).containsExactly(A_ROOM_ID, A_ROOM_ID_4, A_ROOM_ID_3).inOrder()
        assertSafe(snapshots[2][1].message(), ProtectedMessageStatus.REVOKED)
        assertThat(snapshots.last().map { it.roomId }).containsExactly(A_ROOM_ID_4, A_ROOM_ID_3, A_ROOM_ID).inOrder()
        assertThat(snapshots.last().last().message().isContentProtectionAuthorized).isTrue()
        assertThat(adapter.activeHandles).isEmpty()
    }

    @Test
    fun `failed rebuild replaces an old authorized preview without restoring its plaintext`() = runTest {
        var fail = false
        val adapter = PreviewAdapter { message, _ ->
            if (fail) throw ContentProtectionException.AccessDenied() else authorized(message)
        }
        val summaries = MutableSharedFlow<List<RoomSummary>>(replay = 1)
        val processor = RoomSummaryListProcessor(
            summaries, FakeFfiRoomListService(), StandardTestDispatcher(testScheduler), factory(adapter), FakeAnalyticsService(),
        )
        processor.postUpdate(listOf(RoomListEntriesUpdate.Reset(listOf(room()))))
        assertThat(summaries.replayCache.last().single().message().isContentProtectionAuthorized).isTrue()
        fail = true
        processor.postUpdate(listOf(RoomListEntriesUpdate.Set(0u, room())))
        assertSafe(summaries.replayCache.last().single().message(), ProtectedMessageStatus.ACCESS_DENIED)
    }

    @Test
    fun `safe failure projection removes existing plaintext and clipboard permission`() {
        val previouslyAuthorized = aMessageContent(body = "synthetic QA plaintext").copy(
            isContentProtectionAuthorized = true, isBurnAfterRead = true, clipboardSource = MessageClipboardSource.Unprotected,
        )
        val shell = previouslyAuthorized.withProtectedDisplayState(ProtectedMessageDisplayState.VAULT_LOCKED)
        assertSafe(shell, ProtectedMessageStatus.VAULT_LOCKED)
        assertThat(shell.isBurnAfterRead).isTrue()
    }

    @Test
    fun `slow previews do not block the first twenty structural rooms or leak owners`() = runTest {
        val adapter = PreviewAdapter { _, _ -> awaitCancellation() }
        val summaries = MutableSharedFlow<List<RoomSummary>>(replay = 1)
        val processor = RoomSummaryListProcessor(
            summaries, FakeFfiRoomListService(), StandardTestDispatcher(testScheduler), factory(adapter), FakeAnalyticsService(),
        )
        val rooms = (1..20).map { room(RoomId("!qa-$it:next")) }
        val startedAt = testScheduler.currentTime
        withTimeout(2_000L) { processor.postUpdate(listOf(RoomListEntriesUpdate.Reset(rooms))) }
        assertThat(testScheduler.currentTime - startedAt).isAtMost(2_000L)
        assertThat(summaries.replayCache.last().map { it.roomId }).containsExactlyElementsIn(
            (1..20).map { RoomId("!qa-$it:next") },
        ).inOrder()
        summaries.replayCache.last().forEach { assertSafe(it.message(), ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE) }
        assertThat(adapter.rawScopes).hasSize(20)
        assertThat(adapter.activeHandles).isEmpty()
    }

    @Test
    fun `parent cancellation during first population publishes no structural or plaintext snapshot`() = runTest {
        val adapter = PreviewAdapter { _, _ -> awaitCancellation() }
        val summaries = MutableSharedFlow<List<RoomSummary>>(replay = 1)
        val processor = RoomSummaryListProcessor(
            summaries, FakeFfiRoomListService(), StandardTestDispatcher(testScheduler), factory(adapter), FakeAnalyticsService(),
        )
        val parent = async { processor.postUpdate(listOf(RoomListEntriesUpdate.Reset(listOf(room())))) }
        runCurrent()
        assertThat(adapter.activeHandles).hasSize(1)
        parent.cancel()
        parent.join()
        assertThat(parent.isCancelled).isTrue()
        assertThat(summaries.replayCache).isEmpty()
        assertThat(adapter.activeHandles).isEmpty()
    }

    private class PreviewAdapter(
        private val read: suspend PreviewAdapter.(MessageContent, TimelineContentReleaseIntent) -> MessageContent,
    ) : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
        val registry = TimelineReleaseScopeOwnerRegistry()
        val activeHandles = mutableSetOf<String>()
        val rawScopes = mutableListOf<String>()
        private var sequence = 0L

        override suspend fun observedRoomSummaryEventIdentity(timestamp: Long, sender: String, message: MessageContent): String = "\$qa-carrier"

        override fun openTimelineReleaseScope(scopeId: String): String {
            rawScopes += scopeId
            return registry.register(scopeId, "qa-room:$scopeId", TimelineReleaseScopeOwnerToken(++sequence)).also { activeHandles += it }
        }

        override fun closeTimelineReleaseScope(scopeId: String) {
            registry.remove(scopeId)
            activeHandles.remove(scopeId)
        }

        override suspend fun decryptMessageContent(
            eventIdentity: String, message: MessageContent, timelineReleaseIntent: TimelineContentReleaseIntent,
        ): MessageContent = read(message, timelineReleaseIntent)
        override suspend fun decryptMessageContent(
            eventIdentity: String, message: MessageContent, timelineReleaseIntent: TimelineContentReleaseIntent,
            senderAccountIdHash: String,
        ): MessageContent {
            assertThat(senderAccountIdHash).isEqualTo(java.security.MessageDigest.getInstance("SHA-256")
                .digest("@qa:next".encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) })
            return read(message, timelineReleaseIntent)
        }
    }

    private fun factory(adapter: MatrixProtectedContentAdapter) = RoomSummaryFactory(
        sessionId = A_SESSION_ID, deviceId = A_DEVICE_ID,
        protectedContentAdapterFactory = object : MatrixProtectedContentAdapterFactory {
            override fun create(
                sessionId: SessionId, deviceId: DeviceId, roomId: RoomId, activeMemberAccountIdHashes: suspend () -> Set<String>,
                isRoomEncrypted: suspend () -> Boolean,
            ): MatrixProtectedContentAdapter = adapter
        },
    )

    private fun room(roomId: RoomId = A_ROOM_ID) = FakeFfiRoom(roomId = roomId, latestEventLambda = {
        RustLatestEventValue.Remote(
            1000uL, "@qa:next", false, ProfileDetails.Unavailable, aRustTimelineItemContentMsgLike("yizhe-secure-v1:opaque"),
        )
    })

    private fun RoomSummary.message() = (latestEvent as LatestEventValue.Remote).content as MessageContent

    private fun authorized(message: MessageContent) = message.copy(
        body = "authorized synthetic QA preview", type = TextMessageType("authorized synthetic QA preview", formatted = null),
        isContentProtectionAuthorized = true,
    )

    private fun assertSafe(message: MessageContent, status: ProtectedMessageStatus) {
        assertThat(message.protectedMessageStatus).isEqualTo(status)
        assertThat(message.body).isEmpty()
        assertThat(message.type).isEqualTo(TextMessageType("", formatted = null))
        assertThat(message.isContentProtectionAuthorized).isFalse()
        assertThat(message.clipboardSource).isNull()
    }
}
