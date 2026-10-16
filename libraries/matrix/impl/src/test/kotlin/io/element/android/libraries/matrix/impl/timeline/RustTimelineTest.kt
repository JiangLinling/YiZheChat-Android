/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.libraries.matrix.impl.timeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.ForwardEventPairResult
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomListService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.libraries.matrix.impl.room.RoomContentForwarder
import io.element.android.libraries.matrix.impl.room.ForwardTargetUnavailableException
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import uniffi.matrix_sdk.PaginationStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.matrix.rustcomponents.sdk.EventOrTransactionId as RustEventOrTransactionId
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

class RustTimelineTest {
    @Test
    fun `failed pagination retains retry and clears failure on successful retry`() = runTest {
        val inner = FakeFfiTimeline().apply { paginationFailure = IllegalStateException("storage unavailable") }
        val sut = createRustTimeline(inner = inner)
        runCurrent()
        assertThat(sut.paginate(Timeline.PaginationDirection.BACKWARDS).isFailure).isTrue()
        assertThat(sut.backwardPaginationStatus.value.lastRequestFailed).isTrue()
        assertThat(sut.backwardPaginationStatus.value.isPaginating).isFalse()
        assertThat(sut.backwardPaginationStatus.value.hasMoreToLoad).isTrue()
        inner.emitPaginationStatus(PaginationStatus.Idle(hitTimelineStart = false))
        runCurrent()
        assertThat(sut.backwardPaginationStatus.value.lastRequestFailed).isTrue()
        inner.paginationFailure = null
        assertThat(sut.paginate(Timeline.PaginationDirection.BACKWARDS).isSuccess).isTrue()
        assertThat(sut.backwardPaginationStatus.value.lastRequestFailed).isFalse()
        assertThat(sut.backwardPaginationStatus.value.hasMoreToLoad).isFalse()
    }


    @Test
    fun `ordinary location persists its original LC17 transaction before one encrypted enqueue`() = runTest {
        val context = LifecycleOperationContext("location-share-original", FullAppLifecycleId.LC_17)
        val backing = RecordingPreparedAdapter()
        val adapter = object : MatrixProtectedContentAdapter by backing {
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                assertThat(currentLifecycleOperationContext()).isEqualTo(context)
                backing.bindPreparedTransaction(prepared, transactionId)
            }
        }
        val inner = FakeFfiTimeline(failSends = true)
        val queued = mutableListOf<Triple<String, String, String>>()
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { type, body, txn ->
                assertThat(backing.boundTransactions).containsExactly(txn)
                queued += Triple(type, body, txn)
            })
        val result = withContext(context) {
            sut.sendLocation("private location", "geo:1,2", "private label", 14, AssetType.PIN, null)
        }
        assertThat(result.isSuccess).isTrue()
        assertThat(queued).hasSize(1)
        assertThat(queued.single().first).isEqualTo("m.room.message")
        assertThat(queued.single().third).isEqualTo(backing.protectedContentIds.single()!!.value)
        assertThat(queued.single().second).contains("test-envelope")
        assertThat(queued.single().second).doesNotContain("private")
        assertThat(queued.single().second).doesNotContain("geo:1,2")
        assertThat(inner.sentMessageCount).isEqualTo(0)
        assertThat(backing.destroyedContentIds).isEmpty()
    }

    @Test
    fun `location producer journal failure prevents Matrix effects and retires unsent prepare`() = runTest {
        val backing = RecordingPreparedAdapter()
        val adapter = object : MatrixProtectedContentAdapter by backing {
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                error("fixture journal failure")
            }
        }
        var enqueued = 0
        val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = false), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ -> enqueued++ })
        val result = sut.sendLocation("private", "geo:1,2", null, null, AssetType.PIN, null)
        assertThat(result.isFailure).isTrue()
        assertThat(enqueued).isEqualTo(0)
        assertThat(backing.destroyedContentIds).containsExactlyElementsIn(backing.protectedContentIds.filterNotNull())
    }

    @Test
    fun `uncertain location enqueue preserves prepare without retrying or reporting success`() = runTest {
        val adapter = RecordingPreparedAdapter()
        var enqueued = 0
        val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = false), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ ->
                enqueued++
                error("fixture lost enqueue acknowledgement")
            })
        assertThat(sut.sendLocation("private", "geo:1,2", null, null, AssetType.PIN, null).isFailure).isTrue()
        assertThat(enqueued).isEqualTo(1)
        assertThat(adapter.boundTransactions).hasSize(1)
        assertThat(adapter.destroyedContentIds).isEmpty()
    }

    @Test
    fun `location cancellation after queue dispatch preserves original prepare and propagates cancellation`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val entered = CompletableDeferred<Unit>()
        var enqueued = 0
        val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = false), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ ->
                enqueued++
                entered.complete(Unit)
                awaitCancellation()
            })
        val call = async { sut.sendLocation("private", "geo:1,2", null, null, AssetType.PIN, null) }
        entered.await()
        call.cancel()
        runCurrent()
        assertThat(call.isCancelled).isTrue()
        assertThat(enqueued).isEqualTo(1)
        assertThat(adapter.destroyedContentIds).isEmpty()
    }

    @Test
    fun `ordinary location reply keeps the existing encrypted reply relation`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val raw = mutableListOf<Pair<String, String>>()
        var queued = 0
        val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = false), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ -> queued++ },
            rawMessageSender = { type, body -> raw += type to body })
        val result = sut.sendLocation("private location", "geo:1,2", null, null, AssetType.PIN, EventId("\$target"))
        assertThat(result.isSuccess).isTrue()
        assertThat(queued).isEqualTo(0)
        assertThat(adapter.boundTransactions).isEmpty()
        assertStandardProtectedReply(raw.single(), "\$target", "private location")
    }

    @Test
    fun `live start updates and stop bind distinct producer transactions before SDK publication`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val queued = mutableListOf<String>()
        val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = true), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { type, body, transaction ->
                assertThat(adapter.boundTransactions.last()).isEqualTo(transaction)
                assertThat(type).isEqualTo("m.room.message")
                assertThat(body).doesNotContain("geo:")
                queued += transaction
            })
        withContext(LifecycleOperationContext("original-live-operation-0001", FullAppLifecycleId.LC_17)) {
            assertThat(sut.startProtectedLiveLocation("live-id", 900_000, 1_000).isSuccess).isTrue()
            assertThat(sut.updateProtectedLiveLocation("live-id", "geo:39.9,116.4", 2_000).isSuccess).isTrue()
            assertThat(sut.updateProtectedLiveLocation("live-id", "geo:39.91,116.41", 3_000).isSuccess).isTrue()
            assertThat(sut.updateProtectedLiveLocation("live-id", "geo:39.92,116.42", 4_000).isSuccess).isTrue()
            assertThat(sut.endProtectedLiveLocation("live-id").isSuccess).isTrue()
        }
        assertThat(queued).hasSize(5)
        assertThat(queued.toSet()).hasSize(5)
        assertThat(adapter.boundTransactions).containsExactlyElementsIn(queued).inOrder()
    }

    @Test
    fun `live producer journal failure prevents start update and end publication`() = runTest {
        for (action in 0..2) {
            val backing = RecordingPreparedAdapter()
            val adapter = object : MatrixProtectedContentAdapter by backing {
                override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                    error("fixture journal unavailable")
                }
            }
            var queued = 0
            val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = true), protectedContentAdapter = adapter,
                serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ -> queued++ })
            val result = when (action) {
                0 -> sut.startProtectedLiveLocation("live-id", 900_000, 1_000)
                1 -> sut.updateProtectedLiveLocation("live-id", "geo:1,2", 2_000)
                else -> sut.endProtectedLiveLocation("live-id")
            }
            assertThat(result.isFailure).isTrue()
            assertThat(queued).isEqualTo(0)
        }
    }

    @Test
    fun `poll and relay publication persist producer transaction before SDK enqueue`() = runTest {
        for (kind in listOf(ContentKind.POLL, ContentKind.RELAY)) {
            val adapter = RecordingPreparedAdapter()
            val queued = mutableListOf<Triple<String, String, String>>()
            val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = true), protectedContentAdapter = adapter,
                serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { type, body, transaction ->
                    assertThat(adapter.boundTransactions).containsExactly(transaction)
                    queued += Triple(type, body, transaction)
                })
            val result = if (kind == ContentKind.POLL) sut.createPoll("private poll", listOf("A", "B"), 1, PollKind.Disclosed)
                else sut.createRelay("private relay", "private entry")
            assertThat(result.isSuccess).isTrue()
            assertThat(queued).hasSize(1)
            assertThat(queued.single().first).isEqualTo("m.room.message")
            assertThat(queued.single().second).contains("test-envelope")
            assertThat(queued.single().second).doesNotContain("private")
            assertThat(adapter.interactionKinds).containsExactly(kind)
        }
    }

    @Test
    fun `publication journal failure prevents poll and relay Matrix side effects`() = runTest {
        for (kind in listOf(ContentKind.POLL, ContentKind.RELAY)) {
            val backing = RecordingPreparedAdapter()
            val adapter = object : MatrixProtectedContentAdapter by backing {
                override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                    error("fixture journal write unavailable")
                }
            }
            var enqueued = 0
            val sut = createRustTimeline(inner = FakeFfiTimeline(failSends = false), protectedContentAdapter = adapter,
                serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ -> enqueued++ })
            val result = if (kind == ContentKind.POLL) sut.createPoll("private poll", listOf("A", "B"), 1, PollKind.Disclosed)
                else sut.createRelay("private relay", "private entry")
            assertThat(result.isFailure).isTrue()
            assertThat(enqueued).isEqualTo(0)
        }
    }

    @Test
    fun `ordinary protected text commits the KMS content identity as the real Matrix transaction`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val queued = mutableListOf<Triple<String, String, String>>()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = true),
            protectedContentAdapter = adapter,
            serverEventBindingEnabled = true,
            queuedRawMessageEnqueuer = { eventType, content, transaction ->
                assertThat(adapter.boundTransactions).containsExactly(transaction)
                queued += Triple(eventType, content, transaction)
            },
        )
        val result = sut.sendMessageTransaction(TransactionId("binding-intent"), "private text", null, emptyList(), null,
            MsgType.MSG_TYPE_TEXT, false)
        assertThat(result.isSuccess).isTrue()
        assertThat(queued).hasSize(1)
        assertThat(queued.single().first).isEqualTo("m.room.message")
        assertThat(queued.single().third).isEqualTo(adapter.protectedContentIds.single()!!.value)
        assertThat(queued.single().second).contains("test-envelope")
        assertThat(queued.single().second).doesNotContain("private text")
    }

    @Test
    fun `producer journal failure prevents Matrix enqueue and preserves retry intent`() = runTest {
        val preparedAdapter = RecordingPreparedAdapter()
        var failBinding = true
        val adapter = object : MatrixProtectedContentAdapter by preparedAdapter {
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                if (failBinding) error("fixture journal unavailable")
                preparedAdapter.bindPreparedTransaction(prepared, transactionId)
            }
        }
        val queued = mutableListOf<String>()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = true), protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, transaction -> queued += transaction },
        )
        val first = sut.sendMessageTransaction(TransactionId("journal-retry"), "private", null, emptyList(), null,
            MsgType.MSG_TYPE_TEXT, false)
        assertThat(first.isFailure).isTrue()
        assertThat(queued).isEmpty()
        failBinding = false
        val second = sut.sendMessageTransaction(TransactionId("journal-retry"), "private", null, emptyList(), null,
            MsgType.MSG_TYPE_TEXT, false)
        assertThat(second.isSuccess).isTrue()
        assertThat(preparedAdapter.protectedContentIds).hasSize(1)
        assertThat(queued).containsExactly(preparedAdapter.protectedContentIds.single()!!.value)
    }

    @Test
    fun `retry preserves the same content and Matrix transaction instead of duplicating a send`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val transactions = mutableListOf<String>()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = true),
            protectedContentAdapter = adapter,
            serverEventBindingEnabled = true,
            queuedRawMessageEnqueuer = { _, _, transaction ->
                transactions += transaction
                if (transactions.size == 1) error("fixture enqueue failure")
            },
        )
        val first = sut.sendMessageTransaction(TransactionId("binding-retry"), "private", null, emptyList(), null,
            MsgType.MSG_TYPE_TEXT, false)
        val second = sut.sendMessageTransaction(TransactionId("binding-retry"), "private", null, emptyList(), null,
            MsgType.MSG_TYPE_TEXT, false)
        assertThat(first.isFailure).isTrue()
        assertThat(second.isSuccess).isTrue()
        assertThat(adapter.protectedContentIds).hasSize(1)
        assertThat(transactions).containsExactly(adapter.protectedContentIds.single()!!.value,
            adapter.protectedContentIds.single()!!.value).inOrder()
    }

    @Test
    fun `reply stays on its existing protected relation path during ordinary text rollout`() = runTest {
        val queued = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            protectedContentAdapter = RecordingPreparedAdapter(),
            serverEventBindingEnabled = true,
            rawMessageSender = { _, content -> replies += content },
            queuedRawMessageEnqueuer = { _, _, transaction -> queued += transaction },
        )
        val result = sut.sendMessageTransaction(TransactionId("binding-reply"), "private reply", null, emptyList(),
            EventId("\$target"), MsgType.MSG_TYPE_TEXT, false)
        assertThat(result.isSuccess).isTrue()
        assertThat(queued).isEmpty()
        assertThat(replies).hasSize(1)
        assertThat(replies.single()).contains("m.in_reply_to")
        assertThat(replies.single()).doesNotContain("private reply")
    }

    @Test
    fun `real room and timeline close before LOCAL retain one original late SDK commit`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = false)
    }

    @Test
    fun `real room and timeline close after LOCAL retain the same original late SDK commit`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = true)
    }

    @Test
    fun `session cancellation still releases closed room and prevents late completion`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = true, cancelSession = true)
    }

    @Test
    fun `immediate dispatcher retains completed SDK commit until native join returns`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = true, commitBeforeJoin = true)
    }

    @Test
    fun `immediate dispatcher retains original caption binding after early SDK commit and native exit`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = false, commitBeforeJoin = true)
    }

    @Test
    fun `original deadline releases closed room even while native join remains pending`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = true, expireOriginalDeadline = true)
    }

    @Test
    fun `original deadline releases closed room while committed media caption binding remains pending`() = runTest {
        originalRoomCloseRecovery(localBeforeClose = false, commitBeforeJoin = true, expireOriginalDeadline = true)
    }

    private suspend fun TestScope.originalRoomCloseRecovery(
        localBeforeClose: Boolean,
        cancelSession: Boolean = false,
        commitBeforeJoin: Boolean = false,
        expireOriginalDeadline: Boolean = false,
    ) {
        var deadlineQueries = 0
        val nativeJoined = kotlinx.coroutines.CompletableDeferred<Unit>()
        val captionBindingStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val captionBindingFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        var emitLocal: (() -> Unit)? = null
        val directory = Files.createTempDirectory("caption-context-").toFile()
        val source = File(directory, "file.txt").apply { writeText("test") }
        val encrypted = File(directory, ".yizhe-content.yzm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val contexts = mutableListOf<Pair<String, FullAppLifecycleId>>()
        val boundTransactions = mutableListOf<String>()
        val queueListener = slot<SendQueueListener>()
        val acceptedFacts = mutableListOf<io.element.android.libraries.matrix.api.media.MediaSubmissionFact>()
        val nativeHandle = mockk<SendAttachmentJoinHandle>(relaxed = true)
        var dispatches = 0
        coEvery { nativeHandle.join() } coAnswers { nativeJoined.await() }
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun enabled() = true
            override suspend fun protectMedia(file: File, kind: ContentKind, lifecycle: ContentLifecycle): ProtectedMatrixMedia {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                return ProtectedMatrixMedia(encrypted, ContentId("content"))
            }
            override suspend fun protectCaption(caption: String?, formattedCaption: String?, lifecycle: ContentLifecycle,
                attachmentMetadata: ProtectedAttachmentMetadata?, mediaAlbum: ProtectedMediaAlbum?): PreparedProtectedContent {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                assertThat(attachmentMetadata?.displayName).isEqualTo("file.txt")
                return PreparedProtectedContent("encrypted-caption", ContentId("caption"))
            }
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                assertThat(prepared.contentId.value).isEqualTo("caption")
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                if (commitBeforeJoin && !localBeforeClose) {
                    captionBindingStarted.complete(Unit)
                    captionBindingFinished.await() // Original encrypted-journal/authority binding can suspend.
                }
                boundTransactions += transactionId
            }
        }
        val inner = mockk<InnerTimeline>(relaxed = true)
        val listener = slot<org.matrix.rustcomponents.sdk.TimelineListener>()
        coEvery { inner.addListener(capture(listener)) } returns mockk<TaskHandle>(relaxed = true)
        every { inner.sendFile(any(), any()) } answers {
            assertThat(queueListener.isCaptured).isTrue()
            dispatches++
            val params = firstArg<UploadParameters>()
            val sdkSource = mockk<org.matrix.rustcomponents.sdk.MediaSource>(relaxed = true)
            val fileType = org.matrix.rustcomponents.sdk.FileMessageContent(
                encrypted.name, params.caption, null, sdkSource, null,
            )
            val content = org.matrix.rustcomponents.sdk.TimelineItemContent.MsgLike(
                org.matrix.rustcomponents.sdk.MsgLikeContent(
                    org.matrix.rustcomponents.sdk.MsgLikeKind.Message(org.matrix.rustcomponents.sdk.MessageContent(
                        org.matrix.rustcomponents.sdk.MessageType.File(fileType), requireNotNull(params.caption), false, null,
                    )), emptyList(), null, null, null,
                ),
            )
            emitLocal = { listener.captured.onUpdate(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(
                isRemote = false, eventOrTransactionId = RustEventOrTransactionId.TransactionId("sdk-caption-txn"),
                origin = uniffi.matrix_sdk_ui.EventItemOrigin.LOCAL, content = content,
            ))))) }
            nativeHandle
        }
        val nativeRoom = mockk<org.matrix.rustcomponents.sdk.Room>(relaxed = true)
        every { nativeRoom.id() } returns "!original-room:example.org"
        coEvery { nativeRoom.subscribeToSendQueueUpdates(capture(queueListener)) } returns mockk<TaskHandle>(relaxed = true)
        val dispatchers = testCoroutineDispatchers().let {
            if (commitBeforeJoin) it.copy(main = testCoroutineDispatchers(useUnconfinedTestDispatcher = true).main) else it
        }
        val base = io.element.android.libraries.matrix.impl.room.RustBaseRoom(
            io.element.android.libraries.matrix.api.core.SessionId("@original:example.org"), io.element.android.libraries.matrix.api.core.DeviceId("original-device"), nativeRoom,
            dispatchers, mockk(relaxed = true), mockk(relaxed = true), backgroundScope,
            mockk(relaxed = true), aRoomInfo(),
        )
        val focusedDeadline = expireOriginalDeadline && commitBeforeJoin
        val liveInner = if (focusedDeadline) mockk<InnerTimeline>(relaxed = true) else inner
        coEvery { nativeRoom.timelineWithConfiguration(any()) } returns inner
        val room = io.element.android.libraries.matrix.impl.room.JoinedRustRoom(
            base, liveInner, mockk(relaxed = true), dispatchers, FakeSystemClock(),
            RoomContentForwarder(FakeFfiRoomListService()), mockk(relaxed = true), adapter,
        )
        val sut = if (focusedDeadline) room.createTimeline(
            io.element.android.libraries.matrix.api.room.CreateTimelineParams.Focused(EventId("\$original-root")),
        ).getOrThrow() else room.liveTimeline
        val viewport = backgroundScope.launch { sut.membershipChangeEventReceived.collect {} }
        runCurrent()
        val uploadContext = LifecycleOperationContext("item-upload-operation", FullAppLifecycleId.LC_16)
        val captionContext = MediaCaptionOperationContext("item-upload-operation", "item-caption-operation",
            "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val upload = withContext(uploadContext + captionContext + io.element.android.libraries.matrix.api.media.MediaSubmissionContext(
            beforeSdk = {},
            accepted = { acceptedFacts += it },
            originalDeadline = {
                deadlineQueries++
                if (expireOriginalDeadline) System.currentTimeMillis() + 5_000L else null
            },
        )) {
            val handler = sut.sendFile(source, FileInfo("text/plain", 4, null, null), null, null, null, false).getOrThrow()
            assertThat(currentLifecycleOperationContext()).isSameInstanceAs(uploadContext)
            handler
        }
        if (localBeforeClose) { requireNotNull(emitLocal).invoke(); runCurrent() }
        room.close()
        sut.close()
        viewport.cancel() // The disposed screen relinquishes its real subscription.
        runCurrent()
        assertThat(base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isActive).isTrue()
        verify(exactly = 0) { nativeRoom.destroy() }
        if (cancelSession || (expireOriginalDeadline && !commitBeforeJoin)) {
            if (expireOriginalDeadline) advanceTimeBy(6_000L)
            else base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            runCurrent()
            assertThat(deadlineQueries).isEqualTo(1)
            if (expireOriginalDeadline) {
                assertThat(source.exists()).isTrue() // No fake successful cleanup at the deadline.
                assertThat(encrypted.exists()).isTrue()
            }
            queueListener.captured.onUpdate(RoomSendQueueUpdate.SentEvent("sdk-caption-txn", "\$original-file"))
            nativeJoined.complete(Unit)
            runCurrent()
            assertThat(acceptedFacts).isEmpty()
            verify(exactly = 1) { nativeRoom.destroy() }
            source.delete(); encrypted.delete(); directory.delete()
            return
        }
        if (!commitBeforeJoin && !localBeforeClose) requireNotNull(emitLocal).invoke()
        if (commitBeforeJoin) {
            queueListener.captured.onUpdate(RoomSendQueueUpdate.SentEvent("sdk-caption-txn", "\$original-file"))
            runCurrent()
            assertThat(acceptedFacts).isEmpty() // No cleanup or exact LOCAL binding yet.
        }
        nativeJoined.complete(Unit)
        runCurrent()
        if (commitBeforeJoin && !localBeforeClose) {
            assertThat(base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isActive).isTrue()
            requireNotNull(emitLocal).invoke()
            runCurrent()
            assertThat(captionBindingStarted.isCompleted).isTrue()
            assertThat(base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isActive).isTrue()
            if (expireOriginalDeadline) {
                advanceTimeBy(6_000L)
                runCurrent()
                val activeAtOriginalDeadline = base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isActive
                // Release the fixture's NonCancellable journal even when the deadline assertion fails.
                // Its failure is independent; it must not be what finally permits deadline teardown.
                captionBindingFinished.completeExceptionally(IllegalStateException("Original caption journal remains unavailable"))
                runCurrent()
                assertThat(activeAtOriginalDeadline).isFalse()
                verify(exactly = 1) { nativeRoom.destroy() }
                assertThat(boundTransactions).isEmpty() // Never fabricate caption journal completion.
                assertThat(deadlineQueries).isEqualTo(1)
                assertThat(acceptedFacts.last().eventId).isEqualTo("\$original-file")
                assertThat(dispatches).isEqualTo(1)
                assertThat(source.exists()).isFalse()
                assertThat(encrypted.exists()).isFalse()
                assertThat(boundTransactions).isEmpty()
                directory.delete()
                return
            }
            captionBindingFinished.complete(Unit)
            runCurrent()
        }
        assertThat(boundTransactions).containsExactly("sdk-caption-txn")
        assertThat(contexts).containsExactly(
            "item-upload-operation" to FullAppLifecycleId.LC_16,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
        ).inOrder()
        // The raw projection still contains only LOCAL, but the original SDK transaction has committed.
        if (!commitBeforeJoin) {
            queueListener.captured.onUpdate(RoomSendQueueUpdate.SentEvent("sdk-caption-txn", "\$original-file"))
            runCurrent()
        }
        assertThat(acceptedFacts.last().eventId).isEqualTo("\$original-file")
        assertThat(base.roomCoroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isActive).isFalse()
        verify(exactly = 1) { nativeRoom.destroy() }
        assertThat(upload.await().isSuccess).isTrue()
        assertThat(dispatches).isEqualTo(1)
        coVerify(exactly = 1) { nativeHandle.join() }
        assertThat(acceptedFacts.last().uploadOperationId).isEqualTo("item-upload-operation")
        assertThat(acceptedFacts.last().transactionId).isEqualTo("sdk-caption-txn")
        assertThat(acceptedFacts.last().eventId).isEqualTo("\$original-file")
        assertThat(acceptedFacts.last().localCleanupAtEpochMillis).isGreaterThan(0L)
        assertThat(source.exists()).isFalse()
        assertThat(encrypted.exists()).isFalse()
        source.delete()
        encrypted.delete()
        directory.delete()
    }


    @Test
    fun `original upload commit survives original caption journal failure`() = runTest {
        val directory = Files.createTempDirectory("caption-context-").toFile()
        val source = File(directory, "file.txt").apply { writeText("test") }
        val encrypted = File(directory, ".yizhe-content.yzm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val contexts = mutableListOf<Pair<String, FullAppLifecycleId>>()
        val boundTransactions = mutableListOf<String>()
        val queueListener = slot<SendQueueListener>()
        val acceptedFacts = mutableListOf<io.element.android.libraries.matrix.api.media.MediaSubmissionFact>()
        val nativeHandle = mockk<SendAttachmentJoinHandle>(relaxed = true)
        var dispatches = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun enabled() = true
            override suspend fun protectMedia(file: File, kind: ContentKind, lifecycle: ContentLifecycle): ProtectedMatrixMedia {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                return ProtectedMatrixMedia(encrypted, ContentId("content"))
            }
            override suspend fun protectCaption(caption: String?, formattedCaption: String?, lifecycle: ContentLifecycle,
                attachmentMetadata: ProtectedAttachmentMetadata?, mediaAlbum: ProtectedMediaAlbum?): PreparedProtectedContent {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                assertThat(attachmentMetadata?.displayName).isEqualTo("file.txt")
                return PreparedProtectedContent("encrypted-caption", ContentId("caption"))
            }
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                assertThat(prepared.contentId.value).isEqualTo("caption")
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                throw IllegalStateException("Original caption journal remains failed")
            }
        }
        val inner = mockk<InnerTimeline>(relaxed = true)
        val listener = slot<org.matrix.rustcomponents.sdk.TimelineListener>()
        coEvery { inner.addListener(capture(listener)) } returns mockk<TaskHandle>(relaxed = true)
        every { inner.sendFile(any(), any()) } answers {
            assertThat(queueListener.isCaptured).isTrue()
            dispatches++
            val params = firstArg<UploadParameters>()
            val sdkSource = mockk<org.matrix.rustcomponents.sdk.MediaSource>(relaxed = true)
            val fileType = org.matrix.rustcomponents.sdk.FileMessageContent(
                encrypted.name, params.caption, null, sdkSource, null,
            )
            val content = org.matrix.rustcomponents.sdk.TimelineItemContent.MsgLike(
                org.matrix.rustcomponents.sdk.MsgLikeContent(
                    org.matrix.rustcomponents.sdk.MsgLikeKind.Message(org.matrix.rustcomponents.sdk.MessageContent(
                        org.matrix.rustcomponents.sdk.MessageType.File(fileType), requireNotNull(params.caption), false, null,
                    )), emptyList(), null, null, null,
                ),
            )
            listener.captured.onUpdate(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(
                isRemote = false, eventOrTransactionId = RustEventOrTransactionId.TransactionId("sdk-caption-txn"),
                origin = uniffi.matrix_sdk_ui.EventItemOrigin.LOCAL, content = content,
            )))))
            nativeHandle
        }
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter,
            sendQueueUpdateSubscriber = { queueListener.captured = it; mockk<TaskHandle>(relaxed = true) })
        backgroundScope.launch { sut.membershipChangeEventReceived.collect {} }
        runCurrent()
        val uploadContext = LifecycleOperationContext("item-upload-operation", FullAppLifecycleId.LC_16)
        val captionContext = MediaCaptionOperationContext("item-upload-operation", "item-caption-operation",
            "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val upload = withContext(uploadContext + captionContext + io.element.android.libraries.matrix.api.media.MediaSubmissionContext(
            beforeSdk = {},
            accepted = { acceptedFacts += it },
        )) {
            val handler = sut.sendFile(source, FileInfo("text/plain", 4, null, null), null, null, null, false).getOrThrow()
            assertThat(currentLifecycleOperationContext()).isSameInstanceAs(uploadContext)
            handler
        }
        runCurrent()
        assertThat(boundTransactions).isEmpty()
        assertThat(contexts).containsExactly(
            "item-upload-operation" to FullAppLifecycleId.LC_16,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
        ).inOrder()
        // The raw projection still contains only LOCAL, but the original SDK transaction has committed.
        queueListener.captured.onUpdate(RoomSendQueueUpdate.SentEvent("sdk-caption-txn", "\$original-file"))
        assertThat(upload.await().isSuccess).isTrue()
        assertThat(dispatches).isEqualTo(1)
        coVerify(exactly = 1) { nativeHandle.join() }
        assertThat(acceptedFacts.last().uploadOperationId).isEqualTo("item-upload-operation")
        assertThat(acceptedFacts.last().transactionId).isEqualTo("sdk-caption-txn")
        assertThat(acceptedFacts.last().eventId).isEqualTo("\$original-file")
        assertThat(acceptedFacts.last().localCleanupAtEpochMillis).isGreaterThan(0L)
        assertThat(source.exists()).isFalse()
        assertThat(encrypted.exists()).isFalse()
        source.delete()
        encrypted.delete()
        directory.delete()
    }


    @Test
    fun `protected file preserves item upload context and uses its dedicated caption context`() = runTest {
        val directory = Files.createTempDirectory("caption-context-").toFile()
        val source = File(directory, "file.txt").apply { writeText("test") }
        val encrypted = File(directory, ".yizhe-content.yzm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val contexts = mutableListOf<Pair<String, FullAppLifecycleId>>()
        val boundTransactions = mutableListOf<String>()
        val queueListener = slot<SendQueueListener>()
        val acceptedFacts = mutableListOf<io.element.android.libraries.matrix.api.media.MediaSubmissionFact>()
        val nativeHandle = mockk<SendAttachmentJoinHandle>(relaxed = true)
        var dispatches = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun enabled() = true
            override suspend fun protectMedia(file: File, kind: ContentKind, lifecycle: ContentLifecycle): ProtectedMatrixMedia {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                return ProtectedMatrixMedia(encrypted, ContentId("content"))
            }
            override suspend fun protectCaption(caption: String?, formattedCaption: String?, lifecycle: ContentLifecycle,
                attachmentMetadata: ProtectedAttachmentMetadata?, mediaAlbum: ProtectedMediaAlbum?): PreparedProtectedContent {
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                assertThat(attachmentMetadata?.displayName).isEqualTo("file.txt")
                return PreparedProtectedContent("encrypted-caption", ContentId("caption"))
            }
            override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
                assertThat(prepared.contentId.value).isEqualTo("caption")
                val c = requireNotNull(currentLifecycleOperationContext())
                contexts += c.operationId to c.lifecycleId
                boundTransactions += transactionId
            }
        }
        val inner = mockk<InnerTimeline>(relaxed = true)
        val listener = slot<org.matrix.rustcomponents.sdk.TimelineListener>()
        coEvery { inner.addListener(capture(listener)) } returns mockk<TaskHandle>(relaxed = true)
        every { inner.sendFile(any(), any()) } answers {
            assertThat(queueListener.isCaptured).isTrue()
            dispatches++
            val params = firstArg<UploadParameters>()
            val sdkSource = mockk<org.matrix.rustcomponents.sdk.MediaSource>(relaxed = true)
            val fileType = org.matrix.rustcomponents.sdk.FileMessageContent(
                encrypted.name, params.caption, null, sdkSource, null,
            )
            val content = org.matrix.rustcomponents.sdk.TimelineItemContent.MsgLike(
                org.matrix.rustcomponents.sdk.MsgLikeContent(
                    org.matrix.rustcomponents.sdk.MsgLikeKind.Message(org.matrix.rustcomponents.sdk.MessageContent(
                        org.matrix.rustcomponents.sdk.MessageType.File(fileType), requireNotNull(params.caption), false, null,
                    )), emptyList(), null, null, null,
                ),
            )
            listener.captured.onUpdate(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(
                isRemote = false, eventOrTransactionId = RustEventOrTransactionId.TransactionId("sdk-caption-txn"),
                origin = uniffi.matrix_sdk_ui.EventItemOrigin.LOCAL, content = content,
            )))))
            nativeHandle
        }
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter,
            sendQueueUpdateSubscriber = { queueListener.captured = it; mockk<TaskHandle>(relaxed = true) })
        backgroundScope.launch { sut.membershipChangeEventReceived.collect {} }
        runCurrent()
        val uploadContext = LifecycleOperationContext("item-upload-operation", FullAppLifecycleId.LC_16)
        val captionContext = MediaCaptionOperationContext("item-upload-operation", "item-caption-operation",
            "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val upload = withContext(uploadContext + captionContext + io.element.android.libraries.matrix.api.media.MediaSubmissionContext(
            beforeSdk = {},
            accepted = { acceptedFacts += it },
        )) {
            val handler = sut.sendFile(source, FileInfo("text/plain", 4, null, null), null, null, null, false).getOrThrow()
            assertThat(currentLifecycleOperationContext()).isSameInstanceAs(uploadContext)
            handler
        }
        runCurrent()
        assertThat(boundTransactions).containsExactly("sdk-caption-txn")
        assertThat(contexts).containsExactly(
            "item-upload-operation" to FullAppLifecycleId.LC_16,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
            "item-caption-operation" to FullAppLifecycleId.LC_15,
        ).inOrder()
        // The raw projection still contains only LOCAL, but the original SDK transaction has committed.
        queueListener.captured.onUpdate(RoomSendQueueUpdate.SentEvent("sdk-caption-txn", "\$original-file"))
        assertThat(upload.await().isSuccess).isTrue()
        assertThat(dispatches).isEqualTo(1)
        coVerify(exactly = 1) { nativeHandle.join() }
        assertThat(acceptedFacts.last().uploadOperationId).isEqualTo("item-upload-operation")
        assertThat(acceptedFacts.last().transactionId).isEqualTo("sdk-caption-txn")
        assertThat(acceptedFacts.last().eventId).isEqualTo("\$original-file")
        assertThat(acceptedFacts.last().localCleanupAtEpochMillis).isGreaterThan(0L)
        assertThat(source.exists()).isFalse()
        assertThat(encrypted.exists()).isFalse()
        source.delete()
        encrypted.delete()
        directory.delete()
    }


    @Test
    fun `queued raw send registers native listener before enqueue and resolves matching receipt`() = runTest {
        val transactionId = TransactionId("wp08-transaction")
        val contentJson = """{"body":"segment","msgtype":"org.example.segment"}"""
        val calls = mutableListOf<String>()
        val subscription = mockk<TaskHandle>(relaxed = true)
        lateinit var listener: SendQueueListener
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                calls += "subscribe"
                listener = it
                subscription
            },
            queuedRawMessageEnqueuer = { eventType, content, suppliedTransactionId ->
                calls += "enqueue"
                assertThat(eventType).isEqualTo("m.room.message")
                assertThat(content).isEqualTo(contentJson)
                assertThat(suppliedTransactionId).isEqualTo(transactionId.value)
                listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(transactionId.value))
                listener.onUpdate(RoomSendQueueUpdate.SentEvent(transactionId.value, "\$event:example.org"))
            },
        )

        val receipt = sut.enqueueEncryptedRoomMessage(transactionId, contentJson).getOrThrow()

        assertThat(calls).containsExactly("subscribe", "enqueue").inOrder()
        assertThat(receipt.transactionId).isEqualTo(transactionId)
        assertThat(receipt.eventId).isEqualTo(EventId("\$event:example.org"))
        assertThat(receipt.localEchoObserved).isTrue()
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }
    }

    @Test
    fun `queued raw replay accepts recovered local echo emitted during native subscription`() = runTest {
        val transactionId = TransactionId("wp08-recovered-transaction")
        val subscription = mockk<TaskHandle>(relaxed = true)
        lateinit var listener: SendQueueListener
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                listener = it
                listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(transactionId.value))
                subscription
            },
            queuedRawMessageEnqueuer = { _, _, suppliedTransactionId ->
                assertThat(suppliedTransactionId).isEqualTo(transactionId.value)
                listener.onUpdate(RoomSendQueueUpdate.SentEvent(transactionId.value, "\$recovered:example.org"))
            },
        )

        val receipt = sut.enqueueEncryptedRoomMessage(transactionId, """{"body":"recovered"}""").getOrThrow()

        assertThat(receipt.eventId).isEqualTo(EventId("\$recovered:example.org"))
        assertThat(receipt.localEchoObserved).isTrue()
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }
    }

    @Test
    fun `queued raw send with no queue updates times out uncertain and releases listener`() = runTest {
        val transactionId = TransactionId("wp08-no-update-transaction")
        val subscription = mockk<TaskHandle>(relaxed = true)
        lateinit var listener: SendQueueListener
        var enqueueCount = 0
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                listener = it
                subscription
            },
            queuedRawMessageEnqueuer = { _, _, _ -> enqueueCount++ },
            sendQueueReceiptTimeoutMillis = 1_000L,
        )

        val result = sut.enqueueEncryptedRoomMessage(
            transactionId,
            """{"body":"no-update"}""",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("outcome is uncertain")
        assertThat(enqueueCount).isEqualTo(1)
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }

        listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(transactionId.value))
        listener.onUpdate(RoomSendQueueUpdate.SentEvent(transactionId.value, "\$late:example.org"))
        assertThat(enqueueCount).isEqualTo(1)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `queued raw send with only local echo times out uncertain and releases listener`() = runTest {
        val transactionId = TransactionId("wp08-local-echo-only-transaction")
        val subscription = mockk<TaskHandle>(relaxed = true)
        lateinit var listener: SendQueueListener
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                listener = it
                subscription
            },
            queuedRawMessageEnqueuer = { _, _, _ ->
                listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(transactionId.value))
            },
            sendQueueReceiptTimeoutMillis = 1_000L,
        )

        val result = sut.enqueueEncryptedRoomMessage(transactionId, """{"body":"local-only"}""")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("outcome is uncertain")
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }
    }

    @Test
    fun `queued raw send with hanging subscription times out uncertain without enqueue`() = runTest {
        val subscriptionCancelled = CompletableDeferred<Unit>()
        var enqueueCount = 0
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                try {
                    awaitCancellation()
                } finally {
                    subscriptionCancelled.complete(Unit)
                }
            },
            queuedRawMessageEnqueuer = { _, _, _ -> enqueueCount++ },
            sendQueueReceiptTimeoutMillis = 1_000L,
        )

        val result = sut.enqueueEncryptedRoomMessage(
            TransactionId("wp08-hanging-subscription"),
            """{"body":"subscription-hangs"}""",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("outcome is uncertain")
        assertThat(subscriptionCancelled.isCompleted).isTrue()
        assertThat(enqueueCount).isEqualTo(0)
    }

    @Test
    fun `queued raw send with hanging enqueue times out uncertain without retry and releases listener`() = runTest {
        val transactionId = TransactionId("wp08-hanging-enqueue")
        val subscription = mockk<TaskHandle>(relaxed = true)
        val enqueueCancelled = CompletableDeferred<Unit>()
        lateinit var listener: SendQueueListener
        var enqueueCount = 0
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = {
                listener = it
                subscription
            },
            queuedRawMessageEnqueuer = { _, _, _ ->
                enqueueCount++
                listener.onUpdate(RoomSendQueueUpdate.NewLocalEvent(transactionId.value))
                try {
                    awaitCancellation()
                } finally {
                    enqueueCancelled.complete(Unit)
                }
            },
            sendQueueReceiptTimeoutMillis = 1_000L,
        )

        val result = sut.enqueueEncryptedRoomMessage(transactionId, """{"body":"enqueue-hangs"}""")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("outcome is uncertain")
        assertThat(enqueueCancelled.isCompleted).isTrue()
        assertThat(enqueueCount).isEqualTo(1)
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }

        listener.onUpdate(RoomSendQueueUpdate.SentEvent(transactionId.value, "\$late:example.org"))
        assertThat(enqueueCount).isEqualTo(1)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `queued raw send propagates external cancellation and releases listener`() = runTest {
        val subscription = mockk<TaskHandle>(relaxed = true)
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            sendQueueUpdateSubscriber = { subscription },
            queuedRawMessageEnqueuer = { _, _, _ -> Unit },
        )
        val call = async {
            sut.enqueueEncryptedRoomMessage(
                TransactionId("wp08-external-cancellation"),
                """{"body":"cancelled"}""",
            )
        }
        runCurrent()

        call.cancel()
        val failure = runCatching { call.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        verify(exactly = 1) { subscription.cancel() }
        verify(exactly = 1) { subscription.destroy() }
    }

    @Test
    fun `forward pair reports source preflight failure as retryable without sending`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val sut = createRustTimeline(inner)

        val result = sut.forwardEventPair(EventId("\$missing:test"), RoomId("!target:test"), "operation-1234567890")

        assertThat(result).isInstanceOf(ForwardEventPairResult.Failed::class.java)
        assertThat(inner.sentMessageCount).isEqualTo(0)
    }

    @Test
    fun `access denied source fails direct forward pair before content construction or Matrix send`() = runTest {
        val eventId = "\$access-denied:test"
        val inner = FakeFfiTimeline(failSends = false)
        val raw = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId))
        inner.storeEvent(eventId, raw)
        val denied = aMessageContent(body = "").copy(
            type = io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType("", null),
            protectedMessageStatus = ProtectedMessageStatus.ACCESS_DENIED,
            isContentProtectionAuthorized = false,
        )
        val forwarder = mockk<RoomContentForwarder>()
        val sut = createRustTimeline(inner, roomContentForwarder = forwarder, protectedContentAdapter = ProjectedForwardSourceAdapter(denied))

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem(asEventResult = raw)))))
            runCurrent()
            assertThat(expectMostRecentItem().filterIsInstance<MatrixTimelineItem.Event>().single().event.content).isEqualTo(denied)

            val result = sut.forwardEventPair(EventId(eventId), RoomId("!target:test"), "operation-1234567890")

            assertThat(result).isInstanceOf(ForwardEventPairResult.Failed::class.java)
            assertThat((result as ForwardEventPairResult.Failed).reason).contains("not authorized")
            assertThat(inner.sentMessageCount).isEqualTo(0)
            coVerify(exactly = 0) { forwarder.forwardProtected(any(), any(), any()) }
        }
    }

    @Test
    fun `protected forwarder rejects failure status even with an inconsistent authorized flag`() = runTest {
        val denied = aMessageContent(body = "").copy(
            type = io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType("", null),
            protectedMessageStatus = ProtectedMessageStatus.ACCESS_DENIED,
            isContentProtectionAuthorized = true,
        )
        val forwarder = RoomContentForwarder(FakeFfiRoomListService())

        val result = runCatching { forwarder.forwardProtected(denied, listOf(RoomId("!target:test"))) }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("not authorized")
    }

    @Test
    fun `authorized ordinary message still forwards through protected pair`() = runTest {
        val eventId = "\$authorized:test"
        val roomId = RoomId("!target:test")
        val inner = FakeFfiTimeline(failSends = false)
        val raw = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId))
        inner.storeEvent(eventId, raw)
        val authorized = aMessageContent(body = "ordinary authorized message").copy(isContentProtectionAuthorized = true)
        val forwarder = mockk<RoomContentForwarder>()
        coEvery { forwarder.validateTargetRoom(roomId) } returns Result.success(Unit)
        coEvery { forwarder.forwardProtected(authorized, listOf(roomId), any()) } returns Unit
        val sut = createRustTimeline(inner, roomContentForwarder = forwarder, protectedContentAdapter = ProjectedForwardSourceAdapter(authorized))

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem(asEventResult = raw)))))
            runCurrent()
            assertThat(expectMostRecentItem().filterIsInstance<MatrixTimelineItem.Event>().single().event.content).isEqualTo(authorized)

            assertThat(sut.forwardEventPair(EventId(eventId), roomId, "operation-1234567890")).isEqualTo(ForwardEventPairResult.Success)
            coVerify(exactly = 1) { forwarder.forwardProtected(authorized, listOf(roomId), any()) }
        }
    }

    @Test
    fun `forward pair reports missing target before send as retryable`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val eventId = "\$source:test"
        inner.storeEvent(eventId, aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)))
        val sut = createRustTimeline(inner)

        val result = sut.forwardEventPair(EventId(eventId), RoomId("!missing:test"), "operation-1234567890")

        assertThat(result).isInstanceOf(ForwardEventPairResult.Failed::class.java)
        assertThat(inner.sentMessageCount).isEqualTo(0)
    }

    @Test
    fun `forward pair reports failure after successful preflight as ambiguous`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val eventId = "\$source:test"
        inner.storeEvent(eventId, aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)))
        val roomId = RoomId("!target:test")
        val forwarder = mockk<RoomContentForwarder>()
        coEvery { forwarder.validateForwardPair(inner, EventId(eventId), roomId) } returns Result.success(Unit)
        coEvery { forwarder.forward(inner, EventId(eventId), listOf(roomId), any()) } throws IllegalStateException("timeout after submit")
        val sut = createRustTimeline(inner, roomContentForwarder = forwarder)

        val result = sut.forwardEventPair(EventId(eventId), roomId, "operation-1234567890")

        assertThat(result).isInstanceOf(ForwardEventPairResult.Ambiguous::class.java)
        coVerify(exactly = 1) { forwarder.forward(inner, EventId(eventId), listOf(roomId), any()) }
    }

    @Test
    fun `target disappearing after preflight is retryable because no send started`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val eventId = "\$source:test"
        inner.storeEvent(eventId, aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)))
        val roomId = RoomId("!target:test")
        val forwarder = mockk<RoomContentForwarder>()
        coEvery { forwarder.validateForwardPair(inner, EventId(eventId), roomId) } returns Result.success(Unit)
        coEvery { forwarder.forward(inner, EventId(eventId), listOf(roomId), any()) } throws ForwardTargetUnavailableException(roomId)
        val sut = createRustTimeline(inner, roomContentForwarder = forwarder)

        val result = sut.forwardEventPair(EventId(eventId), roomId, "operation-1234567890")

        assertThat(result).isInstanceOf(ForwardEventPairResult.Failed::class.java)
        assertThat(inner.sentMessageCount).isEqualTo(0)
    }
    @Test
    fun `protected voice streams encrypted transport into Matrix SDK and cleans it after upload`() = runTest {
        val cleartext = "authorized voice bytes".encodeToByteArray()
        val workingDirectory = Files.createTempDirectory("protected-voice-").toFile()
        val adapter = RecordingProtectedVoiceAdapter()
        val inner = mockk<InnerTimeline>(relaxed = true)
        val joinHandle = mockk<SendAttachmentJoinHandle>(relaxed = true)
        val uploadParameters = slot<UploadParameters>()
        val rustAudioInfo = slot<org.matrix.rustcomponents.sdk.AudioInfo>()
        val rustWaveform = slot<List<Float>>()
        every {
            inner.sendVoiceMessage(
                capture(uploadParameters),
                capture(rustAudioInfo),
                capture(rustWaveform),
            )
        } returns joinHandle
        coEvery { joinHandle.join() } returns Unit
        var openCount = 0
        val source = ProtectedMediaSource(
            plaintextSize = cleartext.size.toLong(),
            encryptedWorkingDirectory = workingDirectory,
        ) {
            openCount++
            ByteArrayInputStream(cleartext)
        }
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)

        val result = sut.sendProtectedVoiceMessage(
            source = source,
            audioInfo = AudioInfo(duration = null, size = cleartext.size.toLong(), mimetype = "audio/ogg"),
            waveform = listOf(0.1f, 0.6f),
            inReplyToEventId = EventId("\$reply"),
            burnAfterRead = true,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(openCount).isEqualTo(1)
        assertThat(adapter.receivedCleartext).isEqualTo(cleartext)
        assertThat(adapter.receivedKind).isEqualTo(ContentKind.VOICE_MESSAGE)
        assertThat(adapter.receivedLifecycle?.mode).isEqualTo(MessageLifecycleMode.BURN_AFTER_READ)
        val encryptedFile = File((uploadParameters.captured.source as UploadSource.File).filename)
        assertThat(encryptedFile.parentFile).isEqualTo(workingDirectory)
        assertThat(encryptedFile.readBytes()).isNotEqualTo(cleartext)
        assertThat(workingDirectory.listFiles()?.toList()).containsExactly(encryptedFile)
        assertThat(uploadParameters.captured.inReplyTo).isEqualTo("\$reply")
        assertThat(rustAudioInfo.captured.size).isEqualTo(encryptedFile.length().toULong())
        assertThat(rustAudioInfo.captured.mimetype).isEqualTo("audio/ogg")
        assertThat(rustWaveform.captured).containsExactly(0.1f, 0.6f).inOrder()

        assertThat(result.getOrThrow().await().isSuccess).isTrue()
        assertThat(encryptedFile.exists()).isFalse()
        workingDirectory.delete()
    }

    @Test
    fun `redaction commits local tombstone and completes Matrix plus KMS deletion`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)
        val eventId = "\$protected-event"

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                    uniqueId = "protected-event",
                )
            ))))
            awaitItem()

            val result = sut.redactEvent(EventId(eventId).toEventOrTransactionId(), null)

            assertThat(result.isSuccess).isTrue()
            assertThat(adapter.locallyRevokedItemEventIds).containsExactly(eventId)
            assertThat(adapter.destroyedItemEventIds).containsExactly(eventId)
            assertThat(adapter.confirmedRedactionEventIds).containsExactly(eventId)
            assertThat(inner.redactedEventIds).hasSize(1)
            assertLocallyRedacted(awaitItem(), eventId)
        }
    }

    @Test
    fun `missing Matrix storage confirmation fails redaction operation closed`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter(confirmRedactionResult = false)
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)
        val eventId = "\$protected-event"
        inner.storeEvent(
            eventId,
            aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
        )

        val result = sut.redactEvent(EventId(eventId).toEventOrTransactionId(), null)

        assertThat(result.isFailure).isTrue()
        assertThat(inner.redactedEventIds).hasSize(1)
        assertThat(adapter.destroyedItemEventIds).containsExactly(eventId)
        assertThat(adapter.confirmedRedactionEventIds).containsExactly(eventId)
    }

    @Test
    fun `KMS destruction refusal reports failure without extending the Matrix redaction window`() = runTest {
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter(destroyItemResult = false)
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)
        val eventId = "\$protected-event"

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                    uniqueId = "protected-event",
                )
            ))))
            awaitItem()

            val result = sut.redactEvent(EventId(eventId).toEventOrTransactionId(), null)

            assertThat(result.isFailure).isTrue()
            assertThat(adapter.locallyRevokedItemEventIds).containsExactly(eventId)
            assertThat(adapter.destroyedItemEventIds).containsExactly(eventId)
            assertThat(inner.redactedEventIds).hasSize(1)
            assertLocallyRedacted(awaitItem(), eventId)
        }
    }

    @Test
    fun `slow remote KMS destruction does not delay Matrix redaction acceptance`() = runTest {
        val destructionGate = CompletableDeferred<Unit>()
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter(destructionGate = destructionGate)
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)
        val eventId = "\$protected-event"

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                    uniqueId = "protected-event",
                )
            ))))
            awaitItem()

            val redaction = async { sut.redactEvent(EventId(eventId).toEventOrTransactionId(), null) }
            runCurrent()

            assertThat(adapter.locallyRevokedItemEventIds).containsExactly(eventId)
            assertThat(inner.redactedEventIds).hasSize(1)
            assertThat(redaction.isCompleted).isFalse()
            assertLocallyRedacted(awaitItem(), eventId)

            destructionGate.complete(Unit)
            assertThat(redaction.await().isSuccess).isTrue()
        }
    }

    @Test
    fun `redaction recovers content mapping from persisted Matrix event outside visible timeline`() = runTest {
        val eventId = "\$persisted-protected-event"
        val inner = FakeFfiTimeline(failSends = false).apply {
            storeEvent(
                eventId,
                aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
            )
        }
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)

        val result = sut.redactEvent(EventId(eventId).toEventOrTransactionId(), null)

        assertThat(result.isSuccess).isTrue()
        assertThat(inner.fetchedEventIds).containsExactly(eventId)
        assertThat(adapter.destroyedItemEventIds).containsExactly(eventId)
        assertThat(inner.redactedEventIds).hasSize(1)
    }

    @Test
    fun `failed direct text reply and location sends retire every prepared KMS share`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(),
            protectedContentAdapter = adapter,
        )

        val text = sut.sendMessage("text", null, emptyList())
        val reply = sut.replyMessage(EventId("\$reply"), "reply", null, emptyList())
        val location = sut.sendLocation("location", "geo:1,2", null, null, AssetType.SENDER, null)

        assertThat(text.isFailure).isTrue()
        assertThat(reply.isFailure).isTrue()
        assertThat(location.isFailure).isTrue()
        assertThat(adapter.destroyedContentIds).containsExactlyElementsIn(adapter.protectedContentIds.filterNotNull())
    }

    @Test
    fun `protected reply keeps encrypted lineage and emits a standard relation without plaintext fallback`() = runTest {
        val targetEventId = "\$reply-target"
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter()
        val rawMessages = mutableListOf<Pair<String, String>>()
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
            rawMessageSender = { eventType, contentJson -> rawMessages += eventType to contentJson },
        )

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(
                        eventOrTransactionId = RustEventOrTransactionId.EventId(targetEventId),
                    ),
                    uniqueId = "reply-target",
                )
            ))))
            awaitItem()

            val result = sut.replyMessage(EventId(targetEventId), "independent reply", null, emptyList())

            assertThat(result.isSuccess).isTrue()
            assertThat(adapter.protectedReplyTargetEventIds).containsExactly(targetEventId)
            assertThat(inner.sentMessageCount).isEqualTo(0)
            assertThat(inner.sentReplyEventIds).isEmpty()
            assertStandardProtectedReply(rawMessages.single(), targetEventId, "independent reply")
        }
    }

    @Test
    fun `transaction reply keeps encrypted lineage and emits a standard relation without plaintext fallback`() = runTest {
        val targetEventId = EventId("\$transaction-reply-target")
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = RecordingPreparedAdapter()
        val rawMessages = mutableListOf<Pair<String, String>>()
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
            rawMessageSender = { eventType, contentJson -> rawMessages += eventType to contentJson },
        )

        val result = sut.sendMessageTransaction(
            transactionId = TransactionId("transaction-reply"),
            body = "independent transaction reply",
            htmlBody = null,
            intentionalMentions = emptyList(),
            repliedToEventId = targetEventId,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(adapter.protectedReplyTargetEventIds).containsExactly(targetEventId.value)
        assertThat(inner.sentMessageCount).isEqualTo(0)
        assertThat(inner.sentReplyEventIds).isEmpty()
        assertStandardProtectedReply(rawMessages.single(), targetEventId.value, "independent transaction reply")
    }

    @Test
    fun `burn after read location uses a direct one-time lifecycle`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(failSends = false),
            protectedContentAdapter = adapter,
        )

        val result = sut.sendLocation(
            body = "location",
            geoUri = "geo:1,2",
            assetType = AssetType.SENDER,
            inReplyToEventId = null,
            burnAfterRead = true,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(adapter.recordedLocationLifecycle).isEqualTo(
            ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT)
        )
    }

    @Test
    fun `opening burn after read publishes only the explicitly authorized projection`() = runTest {
        val eventId = "\$burn-open"
        val inner = FakeFfiTimeline(failSends = false)
        val adapter = BurnOpenProjectionAdapter()
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(eventId)),
                    uniqueId = "burn-open",
                )
            ))))
            val initial = awaitItem().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat((initial.event.content as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent).body).isEmpty()

            val result = sut.openBurnAfterRead(EventId(eventId))

            assertThat(result.isSuccess).isTrue()
            val opened = awaitItem().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat((opened.event.content as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent).body)
                .isEqualTo("authorized burn cleartext")
            assertThat(adapter.openCount).isEqualTo(1)
            assertThat(adapter.endCount).isEqualTo(0)
        }
    }

    @Test
    fun `protected interaction branches emit their dedicated KMS content kinds`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val inner = FakeFfiTimeline(failSends = false)
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
        )

        sut.timelineItems.test {
            runCurrent()
            inner.emitDiff(listOf(TimelineDiff.Reset(listOf(
                FakeFfiTimelineItem(
                    asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId("\$target")),
                    uniqueId = "target",
                )
            ))))
            awaitItem()

            assertThat(sut.toggleReaction("👍", EventId("\$target").toEventOrTransactionId()).isSuccess).isTrue()
            assertThat(sut.createPoll("Question", listOf("A", "B"), 1, PollKind.Disclosed).isSuccess).isTrue()
            assertThat(sut.startProtectedLiveLocation("live-id", 60_000, 1_000).isSuccess).isTrue()
            assertThat(sut.updateProtectedLiveLocation("live-id", "geo:1,2", 2_000).isSuccess).isTrue()
            assertThat(sut.endProtectedLiveLocation("live-id").isSuccess).isTrue()
            assertThat(sut.createRelay("Relay", "Starter entry").isSuccess).isTrue()
            assertThat(sut.appendRelay(EventId("\$relay"), "Entry").isSuccess).isTrue()
            assertThat(sut.closeRelay(EventId("\$relay")).isSuccess).isTrue()
            assertThat(sut.sendCallLifecycle(EventId("\$call"), CallLifecycleState.ENDED, 65_000L).isSuccess).isTrue()

            assertThat(adapter.interactionKinds).containsExactly(
                ContentKind.REACTION,
                ContentKind.POLL,
                ContentKind.LIVE_LOCATION,
                ContentKind.LIVE_LOCATION_OPERATION,
                ContentKind.LIVE_LOCATION_OPERATION,
                ContentKind.RELAY,
                ContentKind.RELAY_OPERATION,
                ContentKind.RELAY_OPERATION,
                ContentKind.CALL_OPERATION,
            ).inOrder()
            assertThat(adapter.interactionPayloads[5].relayEntry).isEqualTo("Starter entry")
            assertThat(adapter.interactionPayloads[6].relayEntry).isEqualTo("Entry")
            assertThat(adapter.interactionPayloads.last().callLifecycleState).isEqualTo(CallLifecycleState.ENDED.name)
            assertThat(adapter.interactionPayloads.last().durationMillis).isEqualTo(65_000L)
        }
    }

    @Test
    fun `protected caption edit binds lifecycle operation and planned child content identity`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val inner = FakeFfiTimeline(failSends = false).apply {
            storeEvent("\$target", aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$target"),
            ))
        }
        val wiring = RecordingCaptionLineageWiring(ContentId("caption-child"))
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
            protectedCaptionLineageWiring = wiring,
        )

        val result = withContext(LifecycleOperationContext("lc15-caption-operation", FullAppLifecycleId.LC_15)) {
            sut.editCaption(EventId("\$target").toEventOrTransactionId(), "Caption", null)
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(wiring.prepared).containsExactly("\$target" to "lc15-caption-operation")
        assertThat(wiring.accepted.single().childContentId).isEqualTo(ContentId("caption-child"))
        assertThat(wiring.protectionFailures).isEmpty()
        assertThat(adapter.protectedContentIds).containsExactly(ContentId("caption-child"))
        assertThat(adapter.interactionPayloads.single().operationId).isEqualTo("lc15-caption-operation")
    }

    @Test
    fun `relay edit producer accepts only own confirmed current item before protection`() = runTest {
        val room = FakeJoinedRoom().apply { givenRoomInfo(aRoomInfo()) }
        val root = EventId("\$relay-edit-root")
        val own = io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry(
            "own-item", room.sessionId, "original", 2,
        )
        val prepared = RecordingPreparedAdapter()
        var projection = io.element.android.libraries.matrix.api.timeline.item.event.MessageContent(
            body = "接龙：Team\n1. original", inReplyTo = null, isEdited = false, threadInfo = null,
            type = io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType("接龙：Team\n1. original", null),
            isContentProtectionAuthorized = true,
            protectedRelayEntries = kotlinx.collections.immutable.persistentListOf(own),
        )
        val adapter = object : MatrixProtectedContentAdapter by prepared {
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                val event = item as? MatrixTimelineItem.Event ?: return item
                return event.copy(event = event.event.copy(content = projection))
            }
            override suspend fun decryptItem(item: MatrixTimelineItem,
                timelineReleaseIntent: io.element.android.security.contentprotection.api.TimelineContentReleaseIntent,
            ): MatrixTimelineItem = decryptItem(item)
        }
        val inner = FakeFfiTimeline(failSends = false)
        val sut = createRustTimeline(inner = inner, joinedRoom = room, protectedContentAdapter = adapter,
            serverEventBindingEnabled = true, queuedRawMessageEnqueuer = { _, _, _ -> Unit })
        sut.timelineItems.test {
            runCurrent()
            suspend fun publish() {
                inner.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem(asEventResult =
                    aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId(root.value)))))))
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                while (true) {
                    val item = awaitItem().filterIsInstance<MatrixTimelineItem.Event>().firstOrNull { it.eventId == root }
                    val content = item?.event?.content as? io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
                    if (content?.protectedRelayEntries == projection.protectedRelayEntries && content?.body == projection.body) break
                }
            }
            val original = projection
            val invalid = listOf(
                original.copy(protectedRelayEntries = kotlinx.collections.immutable.persistentListOf(own.copy(author =
                    io.element.android.libraries.matrix.api.core.UserId("@other:example")))),
                original.copy(protectedRelayEntries = kotlinx.collections.immutable.persistentListOf(own.copy(revision = 3))),
                original.copy(protectedRelayEntries = kotlinx.collections.immutable.persistentListOf(own.copy(isConfirmed = false))),
                original.copy(body = original.body + "\n（已结束）"),
                original.copy(isBurnAfterRead = true),
            )
            invalid.forEach { current ->
                projection = current
                publish()
                val rejected = withContext(LifecycleOperationContext("relay-edit-operation-0001", FullAppLifecycleId.LC_15)) {
                    sut.editRelayEntry(root, own.id, 2, "revised")
                }
                assertThat(rejected.isFailure).isTrue()
                assertThat(prepared.interactionPayloads).isEmpty()
            }
            projection = original
            publish()
            val result = withContext(LifecycleOperationContext("relay-edit-operation-0001", FullAppLifecycleId.LC_15)) {
                sut.editRelayEntry(root, own.id, 2, " revised ")
            }
            assertThat(result.isSuccess).isTrue()
            val payload = prepared.interactionPayloads.single()
            assertThat(payload.operation).isEqualTo("RELAY_EDIT")
            assertThat(payload.operationId).isEqualTo("relay-edit-operation-0001")
            assertThat(payload.relayEntryId).isEqualTo(own.id)
            assertThat(payload.relayExpectedRevision).isEqualTo(2L)
            assertThat(payload.relayEntry).isEqualTo("revised")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `protected caption edit fails closed before KMS when lifecycle context is absent`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val wiring = RecordingCaptionLineageWiring(ContentId("caption-child"))
        val inner = FakeFfiTimeline(failSends = false).apply {
            storeEvent("\$target", aRustEventTimelineItem(
                eventOrTransactionId = RustEventOrTransactionId.EventId("\$target"),
            ))
        }
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
            protectedCaptionLineageWiring = wiring,
        )

        val result = sut.editCaption(EventId("\$target").toEventOrTransactionId(), "Caption", null)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(wiring.prepared).isEmpty()
        assertThat(adapter.interactionPayloads).isEmpty()
        assertThat(adapter.protectedContentIds).isEmpty()
    }

    @Test
    fun `navigation close waits for protected transaction send but rejects another transaction`() = runTest {
        val protectionReady = CompletableDeferred<Unit>()
        val adapter = RecordingPreparedAdapter(protectionGate = protectionReady)
        val inner = mockk<InnerTimeline>(relaxed = true)
        var nativeClosed = false
        every { inner.close() } answers { nativeClosed = true }
        coEvery { inner.send(any()) } coAnswers {
            check(!nativeClosed) { "Native timeline was destroyed before send" }
            mockk(relaxed = true)
        }
        val sut = createRustTimeline(inner = inner, protectedContentAdapter = adapter)
        val send = async {
            sut.sendMessageTransaction(TransactionId("navigation-transaction"), "test", null, emptyList())
        }
        runCurrent()
        sut.close()
        assertThat(nativeClosed).isFalse()
        val rejected = sut.sendMessageTransaction(TransactionId("after-navigation"), "test", null, emptyList())
        assertThat(rejected.isFailure).isTrue()
        protectionReady.complete(Unit)
        assertThat(send.await().isSuccess).isTrue()
        assertThat(nativeClosed).isTrue()
        coVerify(exactly = 1) { inner.send(any()) }
        verify(exactly = 1) { inner.close() }
        assertThat(adapter.protectedContentIds).hasSize(1)
    }

    @Test
    fun `failed transaction retries reuse the same protected envelope and content id`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(),
            protectedContentAdapter = adapter,
        )
        val transactionId = TransactionId("stable-transaction")

        val first = sut.sendMessageTransaction(transactionId, "body", null, emptyList())
        val second = sut.sendMessageTransaction(transactionId, "body", null, emptyList())

        assertThat(first.isFailure).isTrue()
        assertThat(second.isFailure).isTrue()
        assertThat(adapter.protectedContentIds).hasSize(1)
        assertThat(adapter.protectedContentIds.single()).isNotNull()
    }

    @Test
    fun `discarding a failed transaction destroys its prepared KMS share`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(),
            protectedContentAdapter = adapter,
        )
        val transactionId = TransactionId("discard-transaction")
        sut.sendMessageTransaction(transactionId, "body", null, emptyList())

        val discarded = sut.discardMessageTransaction(transactionId)

        assertThat(discarded.isSuccess).isTrue()
        assertThat(adapter.destroyedContentIds).containsExactly(adapter.protectedContentIds.single())
    }

    @Test
    fun `failed protected edit preserves its exact version for sync reconciliation`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val inner = FakeFfiTimeline().apply {
            storeEvent(
                "\$event",
                aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId("\$event")),
            )
        }
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
        )

        val result = sut.editMessage(EventId("\$event").toEventOrTransactionId(), "edited", null, emptyList())

        assertThat(result.isFailure).isTrue()
        assertThat(adapter.protectedContentIds).hasSize(1)
        assertThat(adapter.destroyedContentIds).isEmpty()
    }

    @Test
    fun `protected edit keeps its durable version when the Matrix content factory fails`() = runTest {
        val adapter = RecordingPreparedAdapter()
        val conversionFailure = IllegalStateException("Unable to construct Matrix content")
        val inner = FakeFfiTimeline(failSends = false).apply {
            storeEvent(
                "\$event",
                aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId("\$event")),
            )
        }
        val sut = createRustTimeline(
            inner = inner,
            protectedContentAdapter = adapter,
            protectedMessageContentFactory = { _, _ -> throw conversionFailure },
        )

        val result = sut.editMessage(EventId("\$event").toEventOrTransactionId(), "edited", null, emptyList())

        assertThat(result.exceptionOrNull()).isSameInstanceAs(conversionFailure)
        assertThat(adapter.protectedContentIds).hasSize(1)
        assertThat(adapter.destroyedContentIds).isEmpty()
    }

    @Test
    fun `KMS outage while protecting a message returns a failure without escaping the timeline boundary`() = runTest {
        val expected = ContentProtectionException.KmsUnavailable()
        val sut = createRustTimeline(
            inner = FakeFfiTimeline(),
            protectedContentAdapter = FailingMatrixProtectedContentAdapter(expected),
        )

        val result = sut.sendMessage(
            body = "sensitive body",
            htmlBody = null,
            intentionalMentions = emptyList(),
            msgType = MsgType.MSG_TYPE_TEXT,
            asPlainText = true,
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(expected)
    }

    @Test
    fun `ensure that the timeline emits new loading item when pagination does not bring new events`() = runTest {
        val inner = FakeFfiTimeline()
        val systemClock = FakeSystemClock()
        val sut = createRustTimeline(
            inner = inner,
            systemClock = systemClock,
        )
        sut.timelineItems.test {
            // Give time for the listener to be set
            runCurrent()
            inner.emitDiff(
                listOf(
                    TimelineDiff.Reset(emptyList())
                )
            )
            with(awaitItem()) {
                assertThat(size).isEqualTo(2)
                // The loading
                assertThat((get(0) as MatrixTimelineItem.Virtual).virtual).isEqualTo(
                    VirtualTimelineItem.LoadingIndicator(
                        direction = Timeline.PaginationDirection.BACKWARDS,
                        timestamp = A_FAKE_TIMESTAMP,
                    )
                )
                // Typing notification
                assertThat((get(1) as MatrixTimelineItem.Virtual).virtual).isEqualTo(VirtualTimelineItem.TypingNotification)
            }
            systemClock.epochMillisResult = A_FAKE_TIMESTAMP + 1
            // Start pagination
            sut.paginate(Timeline.PaginationDirection.BACKWARDS)
            // Simulate SDK starting pagination
            inner.emitPaginationStatus(PaginationStatus.Paginating)
            // No new events received
            // Simulate SDK stopping pagination, more event to load
            inner.emitPaginationStatus(PaginationStatus.Idle(hitTimelineStart = false))
            // expect an item to be emitted, with an updated timestamp
            with(awaitItem()) {
                assertThat(size).isEqualTo(2)
                // The loading
                assertThat((get(0) as MatrixTimelineItem.Virtual).virtual).isEqualTo(
                    VirtualTimelineItem.LoadingIndicator(
                        direction = Timeline.PaginationDirection.BACKWARDS,
                        timestamp = A_FAKE_TIMESTAMP + 1,
                    )
                )
                // Typing notification
                assertThat((get(1) as MatrixTimelineItem.Virtual).virtual).isEqualTo(VirtualTimelineItem.TypingNotification)
            }
        }
    }
}

private class RecordingCaptionLineageWiring(
    private val childContentId: ContentId,
) : ProtectedCaptionLineageWiring {
    val prepared = mutableListOf<Pair<String, String>>()
    val accepted = mutableListOf<ProtectedCaptionEditPlan>()
    val failed = mutableListOf<Pair<ProtectedCaptionEditPlan, Exception>>()
    val protectionFailures = mutableListOf<Pair<ProtectedCaptionEditPlan, Exception>>()

    override suspend fun prepare(targetEventId: String, operationId: String, targetContentId: ContentId, targetExpiresAt: Long): ProtectedCaptionEditPlan {
        prepared += targetEventId to operationId
        return ProtectedCaptionEditPlan(targetEventId, operationId, childContentId)
    }

    override suspend fun protectionOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) {
        protectionFailures += plan to failure
    }

    override suspend fun matrixSendAccepted(plan: ProtectedCaptionEditPlan) {
        accepted += plan
    }

    override suspend fun matrixSendOutcomeUnknown(plan: ProtectedCaptionEditPlan, failure: Exception) {
        failed += plan to failure
    }
}

private fun TestScope.createRustTimeline(
    inner: InnerTimeline,
    mode: Timeline.Mode = Timeline.Mode.Live,
    systemClock: SystemClock = FakeSystemClock(),
    joinedRoom: JoinedRoom = FakeJoinedRoom().apply { givenRoomInfo(aRoomInfo()) },
    coroutineScope: CoroutineScope = backgroundScope,
    dispatcher: CoroutineDispatcher = testCoroutineDispatchers().io,
    roomContentForwarder: RoomContentForwarder = RoomContentForwarder(FakeFfiRoomListService()),
    protectedContentAdapter: MatrixProtectedContentAdapter = DisabledMatrixProtectedContentAdapter,
    protectedCaptionLineageWiring: ProtectedCaptionLineageWiring = UnavailableProtectedCaptionLineageWiring,
    protectedMessageContentFactory: (String, List<io.element.android.libraries.matrix.api.room.IntentionalMention>) ->
        RoomMessageEventContentWithoutRelation = { _, _ -> RoomMessageEventContentWithoutRelation(NoHandle) },
    rawMessageSender: suspend (String, String) -> Unit = { _, _ -> error("Fake raw Matrix send failure") },
    sendQueueUpdateSubscriber: suspend (SendQueueListener) -> TaskHandle = {
        mockk<TaskHandle>(relaxed = true)
    },
    queuedRawMessageEnqueuer: suspend (String, String, String) -> Unit = { _, _, _ ->
        error("Fake queued raw Matrix send failure")
    },
    sendQueueReceiptTimeoutMillis: Long = 120_000L,
    serverEventBindingEnabled: Boolean = false,
): RustTimeline {
    return RustTimeline(
        inner = inner,
        mode = mode,
        systemClock = systemClock,
        joinedRoom = joinedRoom,
        coroutineScope = coroutineScope,
        dispatcher = dispatcher,
        roomContentForwarder = roomContentForwarder,
        protectedContentAdapter = protectedContentAdapter,
        protectedCaptionLineageWiring = protectedCaptionLineageWiring,
        protectedMessageContentFactory = protectedMessageContentFactory,
        rawMessageSender = rawMessageSender,
        sendQueueUpdateSubscriber = sendQueueUpdateSubscriber,
        queuedRawMessageEnqueuer = queuedRawMessageEnqueuer,
        sendQueueReceiptTimeoutMillis = sendQueueReceiptTimeoutMillis,
        serverEventBindingEnabled = serverEventBindingEnabled,
    )
}

private fun assertStandardProtectedReply(
    rawMessage: Pair<String, String>,
    targetEventId: String,
    clearText: String,
) {
    assertThat(rawMessage.first).isEqualTo("m.room.message")
    val content = Json.parseToJsonElement(rawMessage.second).jsonObject
    assertThat(content.getValue("body").jsonPrimitive.content).startsWith("yizhe-secure-v1:")
    assertThat(rawMessage.second).doesNotContain(clearText)
    val relation = content.getValue("m.relates_to").jsonObject
    val reply = relation.getValue("m.in_reply_to").jsonObject
    assertThat(reply.getValue("event_id").jsonPrimitive.content).isEqualTo(targetEventId)
    assertThat(content).containsKey("m.mentions")
}

private class FailingMatrixProtectedContentAdapter(
    private val failure: Exception,
) : MatrixProtectedContentAdapter {
    override suspend fun enabled(): Boolean = true


    override suspend fun protectText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = throw failure

    override suspend fun protectBurnAfterReadText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
    ): PreparedProtectedContent = throw failure

    override suspend fun burnAfterReadLifecycle(): ContentLifecycle = throw failure

    override suspend fun protectLocation(
        body: String,
        geoUri: String,
        description: String?,
        assetType: AssetType?,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = error("unused")

    override suspend fun protectInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>?,
        contentId: ContentId?,
    ): PreparedProtectedContent = error("unused")

    override suspend fun protectMedia(
        file: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle,
    ): ProtectedMatrixMedia = error("unused")

    override suspend fun protectCaption(
        caption: String?,
        formattedCaption: String?,
        lifecycle: ContentLifecycle,
        attachmentMetadata: ProtectedAttachmentMetadata?,
        mediaAlbum: ProtectedMediaAlbum?,
    ): PreparedProtectedContent = error("unused")

    override suspend fun decryptCaption(encodedEnvelope: String): ProtectedCaption = error("unused")

    override suspend fun destroyPrepared(content: PreparedProtectedContent, reason: ContentAccessDestroyReason): Boolean = error("unused")

    override suspend fun destroyMedia(media: ProtectedMatrixMedia, reason: ContentAccessDestroyReason): Boolean = error("unused")

    override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = error("unused")

    override suspend fun destroyItem(item: MatrixTimelineItem, reason: ContentAccessDestroyReason): Boolean = error("unused")
}

private fun assertLocallyRedacted(items: List<MatrixTimelineItem>, eventId: String) {
    val event = items
        .filterIsInstance<MatrixTimelineItem.Event>()
        .single { it.eventId?.value == eventId }
    assertThat(event.event.content).isEqualTo(RedactedContent)
}

private class RecordingPreparedAdapter(
    private val destroyItemResult: Boolean = true,
    private val destructionGate: CompletableDeferred<Unit>? = null,
    private val confirmRedactionResult: Boolean = true,
    private val protectionGate: CompletableDeferred<Unit>? = null,
) : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
    val boundTransactions = mutableListOf<String>()
    override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
        boundTransactions += transactionId
    }
    val protectedContentIds = mutableListOf<ContentId?>()
    val destroyedContentIds = mutableListOf<ContentId>()
    val interactionKinds = mutableListOf<ContentKind>()
    val interactionPayloads = mutableListOf<ProtectedMatrixPayload>()
    val protectedReplyTargetEventIds = mutableListOf<String>()

    override suspend fun burnAfterReadLifecycle(): ContentLifecycle =
        ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT)
    val locallyRevokedItemEventIds = mutableListOf<String>()
    val destroyedItemEventIds = mutableListOf<String>()
    val confirmedRedactionEventIds = mutableListOf<String>()

    override suspend fun enabled(): Boolean = true
    override suspend fun authenticatedCaptionTarget(item: MatrixTimelineItem.Event): ProtectedCaptionTarget =
        ProtectedCaptionTarget(ContentId("caption-root"), Long.MAX_VALUE)


    override suspend fun protectText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent {
        protectionGate?.await()
        val actualContentId = contentId ?: ContentId("generated-${protectedContentIds.size}")
        protectedContentIds += actualContentId
        return PreparedProtectedContent("test-envelope", actualContentId)
    }

    override suspend fun protectTextEdit(item: MatrixTimelineItem.Event, body: String, htmlBody: String?): PreparedProtectedContent =
        protectText(body, htmlBody, MsgType.MSG_TYPE_TEXT, true, null, ContentLifecycle(MessageLifecycleMode.FOLLOW_ROOM))

    override suspend fun protectReplyText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        repliedToEventId: EventId,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent {
        protectedReplyTargetEventIds += repliedToEventId.value
        return protectText(body, htmlBody, msgType, asPlainText, contentId, lifecycle)
    }

    override suspend fun protectLocation(
        body: String,
        geoUri: String,
        description: String?,
        assetType: AssetType?,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent {
        recordedLocationLifecycle = lifecycle
        return recordPrepared(contentId)
    }

    var recordedLocationLifecycle: ContentLifecycle? = null

    override suspend fun protectInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>?,
        contentId: ContentId?,
    ): PreparedProtectedContent {
        interactionKinds += kind
        interactionPayloads += payload
        return recordPrepared(contentId)
    }

    override suspend fun destroyPrepared(content: PreparedProtectedContent, reason: ContentAccessDestroyReason): Boolean {
        destroyedContentIds += content.contentId
        return true
    }

    override suspend fun destroyItem(item: MatrixTimelineItem, reason: ContentAccessDestroyReason): Boolean {
        destructionGate?.await()
        (item as? MatrixTimelineItem.Event)?.eventId?.value?.let(destroyedItemEventIds::add)
        return destroyItemResult
    }

    override suspend fun revokeItemLocally(item: MatrixTimelineItem): Boolean {
        (item as? MatrixTimelineItem.Event)?.eventId?.value?.let(locallyRevokedItemEventIds::add)
        return true
    }

    override suspend fun confirmMatrixRedaction(eventId: EventId): Boolean {
        confirmedRedactionEventIds += eventId.value
        return confirmRedactionResult
    }

    private fun recordPrepared(contentId: ContentId?): PreparedProtectedContent {
        val actualContentId = contentId ?: ContentId("generated-${protectedContentIds.size}")
        protectedContentIds += actualContentId
        return PreparedProtectedContent("test-envelope", actualContentId)
    }
}

private class RecordingProtectedVoiceAdapter : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
    var receivedCleartext: ByteArray? = null
    var receivedKind: ContentKind? = null
    var receivedLifecycle: ContentLifecycle? = null

    override suspend fun enabled(): Boolean = true


    override suspend fun burnAfterReadLifecycle(): ContentLifecycle =
        ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT)

    override suspend fun protectMedia(
        source: InputStream,
        workingDirectory: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle,
    ): ProtectedMatrixMedia {
        receivedCleartext = source.readBytes()
        receivedKind = kind
        receivedLifecycle = lifecycle
        val encrypted = File(workingDirectory, "voice-transport.yzm")
        encrypted.writeBytes(byteArrayOf(0x59, 0x5A, 0x4D, 0x46) + receivedCleartext!!.map { (it.toInt() xor 0x5A).toByte() })
        return ProtectedMatrixMedia(encrypted, ContentId("voice-content"))
    }
}

private class BurnOpenProjectionAdapter : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
    var openCount = 0
    var endCount = 0

    override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
        items.map { item -> item.withTestText("") }

    override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = item.withTestText("")

    override suspend fun openBurnAfterRead(item: MatrixTimelineItem): MatrixTimelineItem {
        openCount++
        return item.withTestText("authorized burn cleartext")
    }

    override suspend fun endBurnAfterRead(
        item: MatrixTimelineItem,
        reason: io.element.android.security.contentprotection.api.BurnAfterReadEndReason,
    ): Boolean {
        endCount++
        return true
    }
}

private class ProjectedForwardSourceAdapter(private val content: MessageContent) :
    MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
    override suspend fun enabled(): Boolean = true

    override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> =
        items.map { it.withForwardSourceContent(content) }

    override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = item.withForwardSourceContent(content)
}

private fun MatrixTimelineItem.withForwardSourceContent(content: MessageContent): MatrixTimelineItem {
    val eventItem = this as? MatrixTimelineItem.Event ?: return this
    return eventItem.copy(event = eventItem.event.copy(content = content))
}

private fun MatrixTimelineItem.withTestText(body: String): MatrixTimelineItem {
    val eventItem = this as? MatrixTimelineItem.Event ?: return this
    return eventItem.copy(
        event = eventItem.event.copy(
            content = io.element.android.libraries.matrix.api.timeline.item.event.MessageContent(
                body = body,
                inReplyTo = null,
                isEdited = false,
                threadInfo = null,
                type = io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType(body, null),
            )
        )
    )
}
