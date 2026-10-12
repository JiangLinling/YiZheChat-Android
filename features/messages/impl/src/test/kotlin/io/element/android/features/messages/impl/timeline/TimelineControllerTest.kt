/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.impl.local.FakeMessageReferenceStore
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShield
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShieldProvider
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.api.timeline.item.event.UtdCause
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.security.contentprotection.api.OfflineOutboxService
import io.element.android.security.contentprotection.api.OfflineTextOutboxMessage
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundary
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundaryProvider
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineControllerTest {
    @Test
    fun `pending cancellation cannot be retried even through a direct controller call`() = runTest {
        val timeline = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline)
        val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
        var retried = false
        sut.registerOptimisticMediaActions(id, retry = { retried = true; Result.success(Unit) }, discard = {})
        sut.resolveOptimisticLocalEcho(id, io.element.android.libraries.matrix.api.media.MediaCancellationPendingException(IllegalStateException()))
        val before = sut.timelineItems().first()
        assertThat(sut.retryOptimisticLocalEcho(id).isFailure).isTrue()
        assertThat(retried).isFalse()
        assertThat(sut.timelineItems().first()).isEqualTo(before)
        sut.close()
    }

    @Test
    fun `media discard waits for owner cancellation and retains unknown echo and retry owner`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
        val gate = CompletableDeferred<Result<Unit>>()
        var released = false
        sut.registerOptimisticMediaActions(id, retry = { Result.success(Unit) }, discard = { released = true }, cancelPending = { gate.await() })
        assertThat(sut.optimisticLocalEchoId(id, id)).isEqualTo(id)
        val cancelling = async { sut.discardOptimisticLocalEcho(id) }
        runCurrent()
        assertThat(released).isFalse()
        assertThat(sut.optimisticLocalEchoId(id, id)).isEqualTo(id)
        gate.complete(Result.failure(IllegalStateException("Awaiting authoritative reconciliation")))
        assertThat(cancelling.await().isFailure).isTrue()
        assertThat(released).isFalse()
        assertThat(sut.optimisticLocalEchoId(id, id)).isEqualTo(id)
        sut.close()
    }

    @Test
    fun `media discard removes echo only after acknowledged owner cancellation`() = runTest {
        val live = FakeTimeline()
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
        var cancelled = false
        sut.registerOptimisticMediaActions(id, retry = { error("No resend") }, discard = { assertThat(cancelled).isTrue() },
            cancelPending = { cancelled = true; Result.success(Unit) })
        assertThat(sut.discardOptimisticLocalEcho(id).isSuccess).isTrue()
        assertThat(sut.optimisticLocalEchoId(id, id)).isNull()
        assertThat(sut.pendingMediaSendPresentations().value).isEmpty()
        sut.close()
    }

    @Test
    fun `last viewed position survives controller recreation without changing another room`() = runTest {
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline())
        val oldPosition = EventId("\$old-position")
        val newPosition = EventId("\$new-position")
        val referenceStore = FakeMessageReferenceStore(MessageReferenceState(lastViewedEventIds = mapOf(
            room.roomId.value to oldPosition.value, "!other:next" to "\$other-position",
        )))
        val first = TimelineController(room = room, liveTimeline = room.liveTimeline, messageReferenceStore = referenceStore)
        assertThat(first.lastViewedPosition()).isEqualTo(oldPosition)
        first.saveLastViewedPosition(newPosition)
        val reopened = TimelineController(room = room, liveTimeline = room.liveTimeline, messageReferenceStore = referenceStore)
        assertThat(reopened.lastViewedPosition()).isEqualTo(newPosition)
        assertThat(referenceStore.state.value.lastViewedEventIds["!other:next"]).isEqualTo("\$other-position")
    }

    @Test
    fun `reading navigation survives controller recreation with expired hint and independent rooms`() = runTest {
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline())
        val store = FakeMessageReferenceStore()
        val first = TimelineController(room = room, liveTimeline = room.liveTimeline, messageReferenceStore = store)
        val navigation = io.element.android.features.messages.api.RoomReadingNavigation(
            latestSeenEventId = "\$latest", firstNewEventId = "\$first", batchEndEventId = "\$end",
            hintRemainingMillis = 0L, hintActivated = true,
        )
        first.saveReadingNavigation(navigation)
        val reopened = TimelineController(room = room, liveTimeline = room.liveTimeline, messageReferenceStore = store)
        assertThat(reopened.readingNavigation()).isEqualTo(navigation)
        assertThat(store.state.value.readingNavigation["!other:next"]).isNull()
    }

    @Test
    fun `protected retry obtains lifecycle authorization and keeps the captured transaction across failures`() = runTest {
        val outbox = FakeOfflineOutboxService()
        val coordinator = FakeAppOperationalStateCoordinator()
        var failSend = true
        val operations = mutableListOf<String>()
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendMessageTransaction(
                transactionId: TransactionId,
                body: String,
                htmlBody: String?,
                intentionalMentions: List<IntentionalMention>,
                repliedToEventId: EventId?,
                msgType: MsgType,
                asPlainText: Boolean,
            ): Result<Unit> {
                operations += checkNotNull(currentLifecycleOperationContext()).operationId
                return if (failSend) Result.failure(IllegalStateException("transport unavailable")) else Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val sut = TimelineController(room, timeline, requireContentProtection = true, offlineOutboxService = outbox,
            matrixClient = FakeMatrixClient(sessionId = room.sessionId), appOperationalStateCoordinator = coordinator)
        val id = sut.addOptimisticLocalEcho("captured retry intent", null)
        assertThat(sut.retryOptimisticLocalEcho(id).isFailure).isTrue()
        assertThat(outbox.entries).hasSize(1)
        val transaction = outbox.entries.single().transactionId
        assertThat(coordinator.clientReceipts).isEmpty()
        failSend = false
        assertThat(sut.retryOptimisticLocalEcho(id).isSuccess).isTrue()
        assertThat(operations).hasSize(2)
        assertThat(operations[1]).isEqualTo(operations[0])
        assertThat(id.value).isEqualTo(transaction)
        assertThat(outbox.entries).isEmpty()
        assertThat(coordinator.clientReceipts).hasSize(2)
        sut.close()
    }

    @Test
    fun `denied protected retry keeps the encrypted intent without calling Matrix or issuing applied receipts`() = runTest {
        val outbox = FakeOfflineOutboxService()
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { IllegalStateException("admission denied") })
        val timeline = FakeTimeline().apply {
            sendMessageLambda = lambdaRecorder(ensureNeverCalled = true) { _, _, _, _, _ -> Result.success(Unit) }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val sut = TimelineController(room, timeline, requireContentProtection = true, offlineOutboxService = outbox,
            matrixClient = FakeMatrixClient(sessionId = room.sessionId), appOperationalStateCoordinator = coordinator)
        val id = sut.addOptimisticLocalEcho("captured denied intent", null)
        assertThat(sut.retryOptimisticLocalEcho(id).isFailure).isTrue()
        assertThat(outbox.entries).hasSize(1)
        assertThat(coordinator.clientReceipts).isEmpty()
        sut.close()
    }

    @Test
    fun `voice presentation uses exact transport identity and survives queue and decrypt snapshots`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = items)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticVoiceEcho("voice-operation", kotlin.time.Duration.parse("3s"), emptyList(), false)
        val local = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        sut.bindOptimisticMediaTransport(id, "protected-voice-object")
        sut.markOptimisticVoiceSending(id)
        val sdk = local.copy(uniqueId = UniqueId("sdk-voice"), protectedMediaTransportName = "protected-voice-object", event = local.event.copy(
            transactionId = TransactionId("sdk-voice-txn"), localSendState = LocalEventSendState.Sending.Encrypting,
        ))
        items.value = listOf(sdk)
        val queued = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(queued.displayUniqueId).isEqualTo(id)
        assertThat(queued.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)
        sut.resolveOptimisticLocalEcho(id, null)
        items.value = emptyList()
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        val eventId = EventId("\$voice-event")
        val remote = sdk.copy(event = sdk.event.copy(eventId = eventId, isRemote = true, localSendState = null))
        items.value = listOf(remote.copy(event = remote.event.copy(content = aMessageContent(body = "").copy(
            protectedMessageStatus = ProtectedMessageStatus.ATTACHMENT_DECRYPTING,
        ))))
        val decrypting = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(decrypting.displayUniqueId).isEqualTo(id)
        assertThat(decrypting.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)
        items.value = listOf(remote)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        sut.close()
    }

    @Test
    fun `SDK text acknowledgement keeps pending row until authenticated remote handover`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = items)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val localId = sut.addOptimisticLocalEcho("handover", null)
        sut.resolveOptimisticLocalEcho(localId, null)
        val local = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        val eventId = EventId("\$handover")
        val sdkLocal = MatrixTimelineItem.Event(
            UniqueId("sdk-local-handover"),
            local.event.copy(
                eventId = eventId,
                isRemote = false,
                localSendState = LocalEventSendState.Sent(eventId),
            ),
        )
        items.value = listOf(sdkLocal)
        val acknowledged = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(acknowledged.displayUniqueId).isEqualTo(localId)
        assertThat(acknowledged.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)
        runCurrent()

        items.value = emptyList()
        val pending = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(pending.displayUniqueId).isEqualTo(localId)
        assertThat(pending.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)

        val remote = sdkLocal.copy(uniqueId = UniqueId("sdk-remote-handover"), event = sdkLocal.event.copy(isRemote = true))
        items.value = listOf(remote.copy(event = remote.event.copy(
            content = aMessageContent(body = "").copy(protectedMessageStatus = ProtectedMessageStatus.DECRYPTING),
        )))
        val decrypting = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(decrypting.displayUniqueId).isEqualTo(localId)
        assertThat(decrypting.event.localSendState).isEqualTo(LocalEventSendState.Sending.Event)

        items.value = listOf(remote)
        val confirmed = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(confirmed.displayUniqueId).isEqualTo(localId)
        assertThat(confirmed.eventId).isEqualTo(eventId)
        assertThat(confirmed.event.localSendState).isEqualTo(LocalEventSendState.Sent(eventId))
        sut.close()
    }

    @Test
    fun `local image preserves oriented preflight dimensions before preprocessing`() = runTest {
        val live = FakeTimeline(timelineItems = flowOf(emptyList()))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        sut.addOptimisticMediaEcho(
            aLocalMedia(mockk<Uri>()).copy(imageDecodeWidth = 720, imageDecodeHeight = 1600), null, null, false,
        )
        val content = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single().event.content as MessageContent
        val info = (content.type as io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType).info
        assertThat(info?.width).isEqualTo(720L)
        assertThat(info?.height).isEqualTo(1600L)
        sut.close()
    }

    @Test
    fun `prepared video dimensions reach the same local row before upload without changing progress or identity`() = runTest {
        val live = FakeTimeline(timelineItems = flowOf(emptyList()))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val media = aLocalMedia(mockk<Uri>()).let { it.copy(info = it.info.copy(mimeType = "video/mp4")) }
        val info = io.element.android.libraries.matrix.api.media.VideoInfo(
            duration = null, height = 1920, width = 1080, mimetype = "video/mp4", size = 1024,
            thumbnailInfo = null, thumbnailSource = null, blurhash = null,
        )
        val prepared = io.element.android.libraries.mediaupload.api.MediaUploadInfo.Video(java.io.File("local-video"), info, null)
        val id = sut.addOptimisticMediaEcho(media, null, null, false)
        sut.updateOptimisticMediaProgress(id, 0.2f)
        sut.updateOptimisticMediaInfo(id, prepared)
        val row = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
        assertThat(row.uniqueId).isEqualTo(id)
        val content = row.event.content as MessageContent
        assertThat(content.localMediaProgress).isEqualTo(0.2f)
        assertThat((content.type as io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType).info).isEqualTo(info)
        sut.close()
    }

    @Test
    fun `same local file sent twice binds only to each uploads exact protected aggregate`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = items)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val media = aLocalMedia(mockk<Uri>())
        val firstId = sut.addOptimisticMediaEcho(media, null, null, false)
        val secondId = sut.addOptimisticMediaEcho(media, null, null, false)
        val locals = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()
        val identities = listOf(
            ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm",
            ".yizhe-1d46d703-34ab-472d-9314-3fcc359c63fa.yzm",
        )
        val ids = listOf(firstId, secondId)
        ids.forEachIndexed { index, id ->
            sut.bindOptimisticMediaTransport(id, identities[index])
            sut.resolveOptimisticLocalEcho(id, null)
        }
        items.value = locals.mapIndexed { index, item ->
            item.copy(uniqueId = UniqueId("remote-$index"), displayUniqueId = UniqueId("remote-$index"),
                protectedMediaTransportName = identities[index],
                event = item.event.copy(eventId = EventId("\$remote-$index"), transactionId = TransactionId("sdk-$index"), isRemote = true))
        }.reversed()
        val result = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(result).hasSize(2)
        ids.forEachIndexed { index, id ->
            assertThat(result.single { it.displayUniqueId == id }.eventId).isEqualTo(EventId("\$remote-$index"))
        }
        sut.close()
    }

    @Test
    fun `overlapping SDK local and remote keep one authoritative row in either order`() = runTest {
        for (observeLocal in listOf(false, true)) for (remoteFirst in listOf(false, true)) for (redacted in listOf(false, true)) {
            val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
            val live = FakeTimeline(timelineItems = items)
            val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
            val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
            val local = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            val identity = ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm"
            sut.bindOptimisticMediaTransport(id, identity)
            val sdk = local.copy(uniqueId = UniqueId("sdk"), protectedMediaTransportName = identity,
                event = local.event.copy(transactionId = TransactionId("actual-txn")))
            if (observeLocal) {
                items.value = listOf(sdk)
                sut.timelineItems().first()
            }
            val remote = sdk.copy(uniqueId = UniqueId("remote"), protectedMediaTransportName = null,
                event = sdk.event.copy(eventId = EventId("\$actual"), isRemote = true,
                    content = if (redacted) RedactedContent else sdk.event.content))
            items.value = if (remoteFirst) listOf(remote, sdk) else listOf(sdk, remote)
            sut.resolveOptimisticLocalEcho(id, null)
            val row = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(row.eventId).isEqualTo(EventId("\$actual"))
            assertThat(row.displayUniqueId).isEqualTo(id)
            if (redacted) assertThat(row.event.content).isEqualTo(RedactedContent)
            sut.close()
        }
    }

    @Test
    fun `protected media identity survives direct remote and local upgrade in either acceptance order`() = runTest {
        for (observeLocal in listOf(false, true)) for (acceptFirst in listOf(false, true)) {
            val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
            val live = FakeTimeline(timelineItems = items)
            val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live, requireContentProtection = true)
            val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
            val local = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            val identity = ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm"
            val content = local.event.content as MessageContent
            val sdk = local.copy(
                uniqueId = UniqueId("sdk-local"),
                displayUniqueId = UniqueId("sdk-local"),
                protectedMediaTransportName = identity,
                event = local.event.copy(
                    transactionId = TransactionId("real-sdk-transaction"),
                    content = content.copy(
                        body = "protected attachment",
                        type = (content.type as io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType)
                            .copy(filename = "protected attachment"),
                        localMediaProgress = null,
                    ),
                ),
            )
            sut.bindOptimisticMediaTransport(id, identity)
            if (acceptFirst) sut.resolveOptimisticLocalEcho(id, null)
            if (observeLocal) {
                items.value = listOf(sdk)
                assertThat(sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single().displayUniqueId).isEqualTo(id)
            }
            val remote = sdk.copy(uniqueId = UniqueId("server"), event = sdk.event.copy(eventId = EventId("\$sent"), isRemote = true))
            items.value = listOf(remote)
            sut.timelineItems().first()
            sut.resolveOptimisticLocalEcho(id, null)
            runCurrent()
            val result = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(result.displayUniqueId).isEqualTo(id)
            assertThat(result.eventId).isEqualTo(EventId("\$sent"))
            assertThat((result.event.content as MessageContent).localMediaProgress).isNull()
            // A later redaction has neither transport metadata nor the sender's transaction.
            items.value = listOf(remote.copy(protectedMediaTransportName = null,
                event = remote.event.copy(transactionId = null, content = RedactedContent)))
            assertThat(sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single().displayUniqueId).isEqualTo(id)
            sut.close()
        }
    }

    @Test
    fun `protected media binding cannot merge two distinct remote events carrying the same identity`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = items)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
        val local = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
        val identity = ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm"
        sut.bindOptimisticMediaTransport(id, identity)
        sut.resolveOptimisticLocalEcho(id, null)
        fun remote(token: String) = local.copy(uniqueId = UniqueId(token), displayUniqueId = UniqueId(token),
            protectedMediaTransportName = identity,
            event = local.event.copy(eventId = EventId("\$$token"), transactionId = TransactionId(token), isRemote = true))
        items.value = listOf(remote("one"))
        assertThat(sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single().displayUniqueId).isEqualTo(id)
        runCurrent()
        items.value = listOf(remote("one"), remote("two"))
        val result = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(result).hasSize(2)
        assertThat(result.map { it.displayUniqueId }.distinct()).hasSize(2)
        sut.close()
    }

    @Test
    fun `exact protected media terminal states replace pending plaintext before send returns`() = runTest {
        for (status in listOf(ProtectedMessageStatus.ACCESS_DENIED, ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED)) {
            val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
            val live = FakeTimeline(timelineItems = items)
            val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live, requireContentProtection = true)
            val id = sut.addOptimisticMediaEcho(aLocalMedia(mockk<Uri>()), null, null, false)
            val local = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            val identity = ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm"
            sut.bindOptimisticMediaTransport(id, identity)
            val terminal = local.copy(uniqueId = UniqueId("terminal"), protectedMediaTransportName = identity,
                event = local.event.copy(eventId = EventId("\$terminal"), transactionId = TransactionId("sdk-terminal"),
                    isRemote = true, content = aMessageContent(body = "").copy(
                        protectedMessageStatus = status, isContentProtectionAuthorized = false,
                        isBurnAfterRead = status == ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
                    )))
            items.value = listOf(terminal)
            val result = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>().single()
            assertThat(result.displayUniqueId).isEqualTo(id)
            assertThat((result.event.content as MessageContent).protectedMessageStatus).isEqualTo(status)
            sut.resolveOptimisticLocalEcho(id, null)
            runCurrent()
            assertThat(sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()).hasSize(1)
            sut.close()
        }
    }

    @Test
    fun `viewport update is routed only to the timeline active at the call boundary`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val detachedTimeline = FakeTimeline(name = "detached")
        val sut = TimelineController(
            room = FakeJoinedRoom(
                liveTimeline = liveTimeline,
                createTimelineResult = { Result.success(detachedTimeline) },
            ),
            liveTimeline = liveTimeline,
        )

        sut.updateViewport(listOf(UniqueId("live-visible")))
        sut.focusOnEvent(AN_EVENT_ID, null)
        // Do not wait for currentTimelineFlow to observe the focus change. The call boundary must
        // use the authoritative detached state even while the presentation flow is one turn behind.
        sut.updateViewport(listOf(UniqueId("detached-visible")))

        assertThat(liveTimeline.viewportUpdates).containsExactly(listOf(UniqueId("live-visible")))
        assertThat(detachedTimeline.viewportUpdates).containsExactly(listOf(UniqueId("detached-visible")))
    }

    @Test
    fun `media send publishes an immediate presentation without entering timeline state`() = runTest {
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(emptyList()))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localUri = mockk<Uri>("pendingMediaUri")

        val displayId = sut.addOptimisticMediaEcho(
            localMedia = aLocalMedia(localUri),
            caption = null,
            mediaAlbum = null,
            burnAfterRead = false,
        )

        assertThat(sut.pendingMediaSendPresentations().value)
            .containsExactly(PendingMediaSendPresentation(displayId))

        sut.acknowledgePendingMediaPresentation(displayId)

        assertThat(sut.pendingMediaSendPresentations().value).isEmpty()
    }

    @Test
    fun `test switching between live and detached timeline`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val detachedTimeline = FakeTimeline(name = "detached")
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) }
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)

        sut.activeTimelineFlow().test {
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            assertThat(sut.isLive().first()).isTrue()
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline)
            }
            assertThat(sut.isLive().first()).isFalse()
            assertThat(detachedTimeline.closeCounter).isEqualTo(0)
            sut.focusOnLive()
            assertThat(sut.isLive().first()).isTrue()
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            assertThat(detachedTimeline.closeCounter).isEqualTo(1)
        }
    }

    @Test
    fun `test switching between detached 1 and detached 2 should close detached 1`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val detachedTimeline1 = FakeTimeline(name = "detached 1")
        val detachedTimeline2 = FakeTimeline(name = "detached 2")
        var callNumber = 0
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = {
                callNumber++
                when (callNumber) {
                    1 -> Result.success(detachedTimeline1)
                    2 -> Result.success(detachedTimeline2)
                    else -> lambdaError()
                }
            }
        )
        val sut = TimelineController(joinedRoom, liveTimeline)

        sut.activeTimelineFlow().test {
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline1)
            }
            assertThat(detachedTimeline1.closeCounter).isEqualTo(0)
            assertThat(detachedTimeline2.closeCounter).isEqualTo(0)
            // Focus on another event should close the previous detached timeline
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline2)
            }
            assertThat(detachedTimeline1.closeCounter).isEqualTo(1)
            assertThat(detachedTimeline2.closeCounter).isEqualTo(0)
        }
    }

    @Test
    fun `test switching to live when already in live should have no effect`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)
        sut.activeTimelineFlow().test {
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            assertThat(sut.isLive().first()).isTrue()
            sut.focusOnLive()
            assertThat(sut.isLive().first()).isTrue()
        }
    }

    @Test
    fun `test closing the TimelineController should close the detached timeline`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val detachedTimeline = FakeTimeline(name = "detached")
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) }
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)
        sut.activeTimelineFlow().test {
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline)
            }
            assertThat(detachedTimeline.closeCounter).isEqualTo(0)
            sut.close()
            assertThat(detachedTimeline.closeCounter).isEqualTo(1)
        }
    }

    @Test
    fun `test getting timeline item`() = runTest {
        val liveTimeline = FakeTimeline(
            name = "live",
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem())
                )
            )
        )
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)
        assertThat(sut.timelineItems().first()).hasSize(1)
    }

    @Test
    fun `closing the TimelineController preserves room recovery candidates for account recovery`() = runTest {
        val inventory = RecordingHiddenMessageRecoveryInventory()
        val liveTimeline = FakeTimeline(name = "live")
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val sut = TimelineController(
            room = room,
            liveTimeline = liveTimeline,
            hiddenMessageRecoveryInventory = inventory,
        )

        sut.close()

        assertThat(inventory.clearedRooms).isEmpty()
    }

    @Test
    fun `locally cleared recovery candidates stay excluded after controller recreation`() = runTest {
        val eventId = EventId("\$locally-cleared-history")
        val timelineItem = MatrixTimelineItem.Event(
            A_UNIQUE_ID,
            anEventTimelineItem(
                eventId = eventId,
                timestamp = 1234L,
                content = UnableToDecryptContent(
                    data = UnableToDecryptContent.Data.MegolmV1AesSha2(
                        sessionId = "history-session",
                        utdCause = UtdCause.HistoricalMessageAndBackupIsDisabled,
                    ),
                    threadInfo = null,
                ),
            ),
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(timelineItem)))
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val referenceStore = FakeMessageReferenceStore(
            MessageReferenceState(
                locallyDeletedKeys = setOf(MessageReferenceState.key(room.roomId, eventId)),
            )
        )
        val inventory = RecordingHiddenMessageRecoveryInventory()
        val sut = TimelineController(
            room = room,
            liveTimeline = liveTimeline,
            hiddenMessageRecoveryInventory = inventory,
            messageReferenceStore = referenceStore,
        )
        runCurrent()

        sut.timelineItems().first()

        assertThat(inventory.candidatesByRoom[room.roomId]).isEmpty()
    }

    @Test
    fun `unavailable history cutoff excludes older candidates discovered by later pagination`() = runTest {
        val oldEvent = MatrixTimelineItem.Event(
            UniqueId("old-paginated-history"),
            anEventTimelineItem(
                eventId = EventId("\$old-paginated-history"),
                timestamp = 1_234L,
                content = UnableToDecryptContent(
                    data = UnableToDecryptContent.Data.MegolmV1AesSha2(
                        sessionId = "old-history-session",
                        utdCause = UtdCause.HistoricalMessageAndBackupIsDisabled,
                    ),
                    threadInfo = null,
                ),
            ),
        )
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val inventory = RecordingHiddenMessageRecoveryInventory()
        val sut = TimelineController(
            room = room,
            liveTimeline = liveTimeline,
            hiddenMessageRecoveryInventory = inventory,
            messageReferenceStore = FakeMessageReferenceStore(
                MessageReferenceState(unavailableHistoryCutoffTimestamp = 2_000L)
            ),
        )
        runCurrent()

        timelineItems.value = listOf(oldEvent)
        sut.timelineItems().first()

        assertThat(inventory.candidatesByRoom[room.roomId]).isEmpty()
    }

    @Test
    fun `unavailable history cutoff never suppresses a new recovery anomaly`() = runTest {
        val newEventId = EventId("\$new-history-anomaly")
        val newEvent = MatrixTimelineItem.Event(
            UniqueId("new-history-anomaly"),
            anEventTimelineItem(
                eventId = newEventId,
                timestamp = 2_001L,
                content = UnableToDecryptContent(
                    data = UnableToDecryptContent.Data.MegolmV1AesSha2(
                        sessionId = "new-history-session",
                        utdCause = UtdCause.HistoricalMessageAndDeviceIsUnverified,
                    ),
                    threadInfo = null,
                ),
            ),
        )
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(listOf(newEvent))
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val inventory = RecordingHiddenMessageRecoveryInventory()
        val sut = TimelineController(
            room = room,
            liveTimeline = liveTimeline,
            hiddenMessageRecoveryInventory = inventory,
            messageReferenceStore = FakeMessageReferenceStore(
                MessageReferenceState(unavailableHistoryCutoffTimestamp = 2_000L)
            ),
        )
        runCurrent()

        sut.timelineItems().first()

        assertThat(inventory.candidatesByRoom[room.roomId]).containsExactly(newEventId.value)
    }

    @Test
    fun `optimistic local echo is reconciled in place with the authenticated timeline item`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        val pendingItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(pendingItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat(pendingItem.event.localSendState).isEqualTo(LocalEventSendState.Sending.Encrypting)
        sut.resolveOptimisticLocalEcho(localEchoId, failure = null)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).event.localSendState)
            .isEqualTo(LocalEventSendState.Sending.Event)
        assertThat(
            (sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId
        ).isEqualTo(localEchoId)

        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                A_UNIQUE_ID,
                anEventTimelineItem(
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "hello"),
                )
            )
        )
        val authenticatedItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(authenticatedItem.uniqueId).isEqualTo(A_UNIQUE_ID)
        assertThat(authenticatedItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat(authenticatedItem.eventId).isEqualTo(AN_EVENT_ID)
    }

    @Test
    fun `transaction id confirmation wins over a same-body remote event and keeps compose identities unique`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val timestamp = System.currentTimeMillis()
        val localEchoId = sut.addOptimisticLocalEcho("same body", null)
        val wrongEventId = EventId("\$wrong")
        val correctEventId = EventId("\$correct")
        val wrong = MatrixTimelineItem.Event(
            UniqueId("wrong-remote"),
            anEventTimelineItem(
                eventId = wrongEventId,
                transactionId = TransactionId("other-transaction"),
                isOwn = true,
                isRemote = true,
                timestamp = timestamp,
                content = aMessageContent(body = "same body"),
            ),
        )
        val correct = MatrixTimelineItem.Event(
            UniqueId("correct-remote"),
            anEventTimelineItem(
                eventId = correctEventId,
                transactionId = TransactionId(localEchoId.value),
                isOwn = true,
                isRemote = true,
                timestamp = timestamp + 1,
                content = aMessageContent(body = "same body"),
            ),
        )

        timelineItems.value = listOf(wrong, correct)

        val reconciled = sut.timelineItems().first { items ->
            items.filterIsInstance<MatrixTimelineItem.Event>().any { it.eventId == correctEventId }
        }.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(reconciled).hasSize(2)
        assertThat(reconciled.map { it.displayUniqueId.value }.toSet()).hasSize(2)
        assertThat(reconciled.single { it.eventId == wrongEventId }.displayUniqueId).isEqualTo(wrong.uniqueId)
        assertThat(reconciled.single { it.eventId == correctEventId }.displayUniqueId).isEqualTo(localEchoId)
    }

    @Test
    fun `redaction wins when the sdk briefly projects the confirmed event twice`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("burn after read", null)
        val eventId = EventId("\$burn-after-read")
        val timestamp = System.currentTimeMillis()
        val confirmed = MatrixTimelineItem.Event(
            uniqueId = UniqueId("live57"),
            event = anEventTimelineItem(
                eventId = eventId,
                transactionId = TransactionId(localEchoId.value),
                isOwn = true,
                isRemote = true,
                timestamp = timestamp,
                content = aMessageContent(body = "burn after read"),
            ),
        )
        timelineItems.value = listOf(confirmed)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId)
            .isEqualTo(localEchoId)

        val stalePlaintextProjection = confirmed.copy(uniqueId = UniqueId("live58"))
        val redactedProjection = confirmed.copy(
            event = confirmed.event.copy(content = RedactedContent),
        )
        timelineItems.value = listOf(redactedProjection, stalePlaintextProjection)

        val reconciled = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(reconciled).hasSize(1)
        assertThat(reconciled.single().eventId).isEqualTo(eventId)
        assertThat(reconciled.single().displayUniqueId).isEqualTo(localEchoId)
        assertThat(reconciled.single().event.content).isEqualTo(RedactedContent)
    }

    @Test
    fun `authenticated event atomically takes identity from an earlier protected shell`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val timestamp = System.currentTimeMillis()
        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        val shellEventId = EventId("\$shell")
        val confirmedEventId = EventId("\$confirmed")
        val shell = MatrixTimelineItem.Event(
            UniqueId("shell"),
            anEventTimelineItem(
                eventId = shellEventId,
                isOwn = true,
                isRemote = true,
                timestamp = timestamp,
                content = aMessageContent(body = "loading").copy(protectedMessageStatus = ProtectedMessageStatus.DECRYPTING),
            ),
        )
        timelineItems.value = listOf(shell)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(localEchoId)
        val confirmed = MatrixTimelineItem.Event(
            UniqueId("confirmed"),
            anEventTimelineItem(
                eventId = confirmedEventId,
                transactionId = TransactionId(localEchoId.value),
                isOwn = true,
                isRemote = true,
                timestamp = timestamp + 1,
                content = aMessageContent(body = "hello"),
            ),
        )

        timelineItems.value = listOf(shell, confirmed)

        val reconciled = sut.timelineItems().first { items ->
            items.filterIsInstance<MatrixTimelineItem.Event>().any { it.eventId == confirmedEventId }
        }.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(reconciled).hasSize(1)
        assertThat(reconciled.single().eventId).isEqualTo(confirmedEventId)
        assertThat(reconciled.single().displayUniqueId).isEqualTo(localEchoId)
    }

    @Test
    fun `edit arriving before ordinary confirmation atomically replaces the optimistic item`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("before edit", null)
        val eventId = EventId("\$edited-before-confirmation")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("edited-remote"),
                anEventTimelineItem(
                    eventId = eventId,
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "after edit", isEdited = true),
                ),
            )
        )

        val result = sut.timelineItems().first { items -> items.any { (it as? MatrixTimelineItem.Event)?.eventId == eventId } }
        val event = result.single() as MatrixTimelineItem.Event
        assertThat(event.displayUniqueId).isEqualTo(localEchoId)
        assertThat((event.event.content as MessageContent).isEdited).isTrue()
    }

    @Test
    fun `redaction arriving before ordinary confirmation retires plaintext optimistic item`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("must disappear", null)
        val eventId = EventId("\$redacted-before-confirmation")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("redacted-remote"),
                anEventTimelineItem(
                    eventId = eventId,
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = RedactedContent,
                ),
            )
        )

        val result = sut.timelineItems().first { items -> items.any { (it as? MatrixTimelineItem.Event)?.eventId == eventId } }
        val event = result.single() as MatrixTimelineItem.Event
        assertThat(event.displayUniqueId).isEqualTo(localEchoId)
        assertThat(event.event.content).isEqualTo(RedactedContent)
    }

    @Test
    fun `reply optimistic item confirms in place by transaction id`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val targetEventId = EventId("\$reply-target")
        val localEchoId = sut.addOptimisticLocalEcho("reply text", null, repliedToEventId = targetEventId)
        val replyEventId = EventId("\$reply")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("reply-remote"),
                anEventTimelineItem(
                    eventId = replyEventId,
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "reply text", inReplyTo = InReplyTo.NotLoaded(targetEventId)),
                ),
            )
        )

        val result = sut.timelineItems().first { items -> items.any { (it as? MatrixTimelineItem.Event)?.eventId == replyEventId } }
        val event = result.single() as MatrixTimelineItem.Event
        assertThat(event.displayUniqueId).isEqualTo(localEchoId)
        assertThat((event.event.content as MessageContent).inReplyTo).isEqualTo(InReplyTo.NotLoaded(targetEventId))
    }

    @Test
    fun `reconnect replay with a new sdk unique id keeps the confirmed compose identity`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("replay", null)
        val eventId = EventId("\$replay")
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("initial-sdk-id"),
                anEventTimelineItem(
                    eventId = eventId,
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "replay"),
                ),
            )
        )
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(localEchoId)

        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                UniqueId("replayed-sdk-id"),
                anEventTimelineItem(
                    eventId = eventId,
                    transactionId = null,
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "replay"),
                ),
            )
        )

        val replayed = sut.timelineItems().first { items ->
            (items.singleOrNull() as? MatrixTimelineItem.Event)?.uniqueId == UniqueId("replayed-sdk-id")
        }.single() as MatrixTimelineItem.Event
        assertThat(replayed.displayUniqueId).isEqualTo(localEchoId)
    }

    @Test
    fun `sdk local identity upgrade without transaction keeps the optimistic display identity`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val room = FakeJoinedRoom(liveTimeline = live)
        val sut = TimelineController(room, live, requireContentProtection = true,
            matrixClient = FakeMatrixClient(sessionId = room.sessionId), appOperationalStateCoordinator = FakeAppOperationalStateCoordinator())
        val id = sut.addOptimisticLocalEcho("identity upgrade", null)
        val local = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(sut.sendOptimisticLocalEcho(id).isSuccess).isTrue()
        val sdkLocal = local.copy(
            uniqueId = UniqueId("sdk-stable-unique-id"),
            displayUniqueId = UniqueId("sdk-stable-unique-id"),
            event = local.event.copy(transactionId = TransactionId("sdk-transaction"), localSendState = LocalEventSendState.Sending.Event),
        )
        timelineItems.value = listOf(sdkLocal)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        runCurrent()
        val remote = sdkLocal.copy(event = sdkLocal.event.copy(
            eventId = EventId("\$identity-upgrade"), transactionId = null, isRemote = true, localSendState = null,
        ))
        timelineItems.value = listOf(remote)
        val projected = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(projected.eventId).isEqualTo(remote.eventId)
        assertThat(projected.displayUniqueId).isEqualTo(id)
        assertThat(projected.event.localSendState).isNull()
        // A later event-backed replay can change its SDK identity after the first exact upgrade.
        timelineItems.value = listOf(remote.copy(uniqueId = UniqueId("replayed-identity")))
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        sut.close()
    }

    @Test
    fun `sdk identity upgrade keeps terminal security result without restoring optimistic plaintext`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val room = FakeJoinedRoom(liveTimeline = live)
        val sut = TimelineController(room, live, requireContentProtection = true,
            matrixClient = FakeMatrixClient(sessionId = room.sessionId), appOperationalStateCoordinator = FakeAppOperationalStateCoordinator())
        val id = sut.addOptimisticLocalEcho("never restore", null)
        val local = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(sut.sendOptimisticLocalEcho(id).isSuccess).isTrue()
        val sdk = local.copy(
            uniqueId = UniqueId("sdk-terminal-upgrade"), displayUniqueId = UniqueId("sdk-terminal-upgrade"),
            event = local.event.copy(transactionId = TransactionId("sdk-txn")),
        )
        timelineItems.value = listOf(sdk)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        runCurrent()
        val terminal = sdk.copy(event = sdk.event.copy(
            eventId = EventId("\$terminal-upgrade"), transactionId = null, isRemote = true,
            localSendState = null, content = RedactedContent,
        ))
        timelineItems.value = listOf(terminal)
        val projected = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(projected.displayUniqueId).isEqualTo(id)
        assertThat(projected.event.content).isEqualTo(RedactedContent)
        sut.close()
    }

    @Test
    fun `sdk identity reuse by a distinct event or another sender cannot acquire the confirmed display identity`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = live), live)
        val id = sut.addOptimisticLocalEcho("distinct events", null)
        val local = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(sut.sendOptimisticLocalEcho(id).isSuccess).isTrue()
        val sdkLocal = local.copy(
            uniqueId = UniqueId("reused-sdk-id"), displayUniqueId = UniqueId("reused-sdk-id"),
            event = local.event.copy(transactionId = TransactionId("sdk-reused-txn")),
        )
        timelineItems.value = listOf(sdkLocal)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        runCurrent()
        val otherSender = sdkLocal.copy(event = sdkLocal.event.copy(
            eventId = EventId("\$other"), transactionId = null, isRemote = true,
            isOwn = false, sender = UserId("@other:example.org"),
        ))
        timelineItems.value = listOf(otherSender)
        val withUnrelatedSender = sut.timelineItems().first().filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(withUnrelatedSender.single { it.eventId == otherSender.eventId }.displayUniqueId).isEqualTo(otherSender.uniqueId)
        // An unrelated sender cannot consume the still-pending owned send or acquire its display identity.
        assertThat(withUnrelatedSender.single { it.eventId == null }.displayUniqueId).isEqualTo(id)
        val first = sdkLocal.copy(event = sdkLocal.event.copy(eventId = EventId("\$first"), transactionId = null, isRemote = true))
        timelineItems.value = listOf(first)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(id)
        runCurrent()
        val distinct = first.copy(event = first.event.copy(eventId = EventId("\$distinct"), transactionId = null))
        timelineItems.value = listOf(distinct)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(distinct.uniqueId)
        sut.close()
    }

    @Test
    fun `history pagination preserves confirmed identity and event order`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("latest", null)
        val eventId = EventId("\$latest")
        val confirmed = MatrixTimelineItem.Event(
            UniqueId("latest-sdk"),
            anEventTimelineItem(
                eventId = eventId,
                transactionId = TransactionId(localEchoId.value),
                isOwn = true,
                isRemote = true,
                timestamp = 2L,
                content = aMessageContent(body = "latest"),
            ),
        )
        timelineItems.value = listOf(confirmed)
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(localEchoId)
        val history = MatrixTimelineItem.Event(
            UniqueId("history-sdk"),
            anEventTimelineItem(
                eventId = EventId("\$history"),
                isRemote = true,
                timestamp = 1L,
                content = aMessageContent(body = "history"),
            ),
        )
        timelineItems.value = listOf(history, confirmed.copy(uniqueId = UniqueId("latest-paginated-sdk")))

        val paginated = sut.timelineItems().first { items -> items.size == 2 }.filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(paginated.map { it.eventId }).containsExactly(EventId("\$history"), eventId).inOrder()
        assertThat(paginated.single { it.eventId == eventId }.displayUniqueId).isEqualTo(localEchoId)
        assertThat(paginated.map { it.displayUniqueId.value }.toSet()).hasSize(2)
    }

    @Test
    fun `ten rapid same-body sends reconcile to ten distinct compose identities`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoIds = List(10) { sut.addOptimisticLocalEcho("rapid", null) }
        val remoteItems = localEchoIds.mapIndexed { index, localEchoId ->
            MatrixTimelineItem.Event(
                UniqueId("rapid-sdk-$index"),
                anEventTimelineItem(
                    eventId = EventId("\$rapid-$index"),
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis() + index,
                    content = aMessageContent(body = "rapid"),
                ),
            )
        }.reversed()

        timelineItems.value = remoteItems

        val reconciled = sut.timelineItems().first { items -> items.filterIsInstance<MatrixTimelineItem.Event>().size == 10 }
            .filterIsInstance<MatrixTimelineItem.Event>()
        assertThat(reconciled.map { it.displayUniqueId.value }.toSet()).hasSize(10)
        remoteItems.forEach { remote ->
            val expectedDisplayId = localEchoIds.single { it.value == remote.transactionId?.value }
            assertThat(reconciled.single { it.eventId == remote.eventId }.displayUniqueId).isEqualTo(expectedDisplayId)
        }
    }

    @Test
    fun `older same-body event cannot consume a new echo before that send is accepted`() = runTest {
        val timestamp = System.currentTimeMillis()
        val olderEvent = MatrixTimelineItem.Event(
            UniqueId("older-confirmed"),
            anEventTimelineItem(
                eventId = EventId("\$older-confirmed"),
                isOwn = true,
                isRemote = true,
                timestamp = timestamp,
                content = aMessageContent(body = "repeat"),
            ),
        )
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(listOf(olderEvent))
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("repeat", null)
        val pending = sut.timelineItems().first()

        assertThat(pending.filterIsInstance<MatrixTimelineItem.Event>().map { it.displayUniqueId })
            .containsExactly(olderEvent.displayUniqueId, localEchoId)
        assertThat(sut.sendOptimisticLocalEcho(localEchoId).isSuccess).isTrue()
    }

    @Test
    fun `edited authenticated item keeps confirmed identity without duplicating the optimistic echo`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val timestamp = System.currentTimeMillis()

        val localEchoId = sut.addOptimisticLocalEcho("before edit", null)
        sut.resolveOptimisticLocalEcho(localEchoId, failure = null)
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                A_UNIQUE_ID,
                anEventTimelineItem(
                    isOwn = true,
                    isRemote = true,
                    timestamp = timestamp,
                    content = aMessageContent(body = "before edit"),
                )
            )
        )
        assertThat((sut.timelineItems().first().single() as MatrixTimelineItem.Event).displayUniqueId)
            .isEqualTo(localEchoId)

        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                A_UNIQUE_ID,
                anEventTimelineItem(
                    isOwn = true,
                    isRemote = true,
                    timestamp = timestamp,
                    content = aMessageContent(body = "after edit"),
                )
            )
        )

        val editedItems = sut.timelineItems().first()
        assertThat(editedItems).hasSize(1)
        val editedItem = editedItems.single() as MatrixTimelineItem.Event
        assertThat(editedItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat((editedItem.event.content as MessageContent).body).isEqualTo("after edit")
    }

    @Test
    fun `decrypting shell is replaced in place by the trusted optimistic echo until authentication`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        timelineItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        transactionId = TransactionId(localEchoId.value),
                        isOwn = true,
                        isRemote = true,
                        timestamp = System.currentTimeMillis(),
                        content = aMessageContent(body = "正在加载…").copy(
                            protectedMessageStatus = ProtectedMessageStatus.DECRYPTING,
                        ),
                    )
                )
        )
        val decryptingItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(decryptingItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat((decryptingItem.event.content as MessageContent).body).isEqualTo("hello")

        timelineItems.value = emptyList()
        val restoredOptimisticItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(restoredOptimisticItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat((restoredOptimisticItem.event.content as MessageContent).body).isEqualTo("hello")

        timelineItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        isOwn = true,
                        isRemote = true,
                        timestamp = System.currentTimeMillis(),
                        content = aMessageContent(body = "hello"),
                    )
                )
        )
        val authenticatedItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(authenticatedItem.uniqueId).isEqualTo(A_UNIQUE_ID)
        assertThat(authenticatedItem.displayUniqueId).isEqualTo(localEchoId)
    }

    @Test
    fun `terminal protected status replaces the optimistic echo and stays hidden`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        timelineItems.value = listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(
                        transactionId = TransactionId(localEchoId.value),
                        isOwn = true,
                        isRemote = true,
                        timestamp = System.currentTimeMillis(),
                        content = aMessageContent(body = "消息校验失败").copy(
                            protectedMessageStatus = ProtectedMessageStatus.INTEGRITY_REJECTED,
                        ),
                    )
                )
        )
        assertThat(sut.timelineItems().first()).isEmpty()

        timelineItems.value = emptyList()
        assertThat(sut.timelineItems().first()).isEmpty()
    }

    @Test
    fun `redaction arriving before local echo confirmation permanently retires optimistic cleartext`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("must never revive", null)
        timelineItems.value = listOf(
            MatrixTimelineItem.Event(
                A_UNIQUE_ID,
                anEventTimelineItem(
                    transactionId = TransactionId(localEchoId.value),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = RedactedContent,
                )
            )
        )

        val terminalItems = sut.timelineItems().first()
        assertThat(terminalItems).hasSize(1)
        assertThat((terminalItems.single() as MatrixTimelineItem.Event).event.content).isEqualTo(RedactedContent)

        timelineItems.value = emptyList()
        assertThat(sut.timelineItems().first()).isEmpty()
    }

    @Test
    fun `failed optimistic local echo remains visible with the same stable identity`() = runTest {
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(emptyList()))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        sut.resolveOptimisticLocalEcho(localEchoId, IllegalStateException("send failed"))
        val failedItem = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        assertThat(failedItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat(failedItem.event.localSendState).isInstanceOf(LocalEventSendState.Failed.Unknown::class.java)
    }

    @Test
    fun `sdk failed local echo with a different transaction id replaces the optimistic echo`() = runTest {
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)
        val localEchoId = sut.addOptimisticLocalEcho("must stay encrypted", null)
        val localEcho = sut.timelineItems().first().single() as MatrixTimelineItem.Event
        val send = async { sut.sendOptimisticLocalEcho(localEchoId) }
        runCurrent()
        val sdkFailure = MatrixTimelineItem.Event(
            UniqueId("sdk-local-echo"),
            anEventTimelineItem(
                transactionId = TransactionId("sdk-generated-transaction"),
                isOwn = true,
                isRemote = false,
                localSendState = LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice(
                    mapOf(UserId("@alice:example.org") to listOf(DeviceId("UNSIGNED")))
                ),
                timestamp = localEcho.event.timestamp,
                content = aMessageContent(body = "must stay encrypted").copy(isContentProtectionAuthorized = true),
            ).copy(eventId = null),
        )

        timelineItems.value = listOf(sdkFailure)

        val result = sut.timelineItems().first()
        assertThat(result).hasSize(1)
        val failedItem = result.single() as MatrixTimelineItem.Event
        assertThat(failedItem.uniqueId).isEqualTo(sdkFailure.uniqueId)
        assertThat(failedItem.displayUniqueId).isEqualTo(localEchoId)
        assertThat(failedItem.event.localSendState)
            .isInstanceOf(LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice::class.java)
        assertThat(send.await().isSuccess).isTrue()
    }

    @Test
    fun `older sdk local echo cannot consume a repeated new message before its send starts`() = runTest {
        val timestamp = System.currentTimeMillis()
        val olderSdkEcho = MatrixTimelineItem.Event(
            UniqueId("older-sdk-local-echo"),
            anEventTimelineItem(
                transactionId = TransactionId("older-sdk-transaction"),
                isOwn = true,
                isRemote = false,
                timestamp = timestamp,
                content = aMessageContent(body = "repeat"),
            ).copy(eventId = null),
        )
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(listOf(olderSdkEcho))
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems).apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("repeat", null)
        val beforeSend = sut.timelineItems().first()

        assertThat(beforeSend.filterIsInstance<MatrixTimelineItem.Event>().map { it.displayUniqueId })
            .containsExactly(olderSdkEcho.displayUniqueId, localEchoId)
        assertThat(sut.sendOptimisticLocalEcho(localEchoId).isSuccess).isTrue()
    }

    @Test
    fun `normal text is encrypted into outbox before Matrix send and removed after acceptance`() = runTest {
        val outbox = FakeOfflineOutboxService()
        var wasStagedBeforeSend = false
        val liveTimeline = FakeTimeline(name = "live").apply {
            sendMessageLambda = { _, _, _, _, _ ->
                wasStagedBeforeSend = outbox.entries.isNotEmpty()
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val sut = TimelineController(room, liveTimeline, offlineOutboxService = outbox)

        val localEchoId = sut.addOptimisticLocalEcho("encrypted pending message", null)
        val result = sut.sendOptimisticLocalEcho(localEchoId)

        assertThat(result.isSuccess).isTrue()
        assertThat(wasStagedBeforeSend).isTrue()
        assertThat(outbox.entries).isEmpty()
    }

    @Test
    fun `captured intent is recoverable before remote admission and close waits for its session send`() = runTest {
        val outbox = FakeOfflineOutboxService()
        var sends = 0
        val timeline = FakeTimeline(name = "live").apply {
            sendMessageLambda = { _, _, _, _, _ -> sends++; Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline, offlineOutboxService = outbox)
        val allowAdmission = kotlinx.coroutines.CompletableDeferred<Unit>()
        val staged = kotlinx.coroutines.CompletableDeferred<Unit>()
        val send = async {
            sut.withComposerSendLifetime {
                val id = sut.addOptimisticLocalEcho("captured before admission", null)
                sut.stageOptimisticLocalEcho(id).getOrThrow()
                staged.complete(Unit)
                allowAdmission.await()
                sut.sendOptimisticLocalEcho(id)
            }
        }
        staged.await()
        assertThat(outbox.entries).hasSize(1)
        assertThat(sends).isEqualTo(0)
        sut.close()
        allowAdmission.complete(Unit)
        assertThat(send.await().isSuccess).isTrue()
        assertThat(sends).isEqualTo(1)
        assertThat(outbox.entries).isEmpty()
        assertThat(runCatching { sut.withComposerSendLifetime { Unit } }.isFailure).isTrue()
    }

    @Test
    fun `failed secure send restores after restart then confirms with one stable compose identity`() = runTest {
        val outbox = FakeOfflineOutboxService()
        val liveTimeline = FakeTimeline(name = "live").apply {
            sendMessageLambda = { _, _, _, _, _ -> Result.failure(IllegalStateException("offline")) }
        }
        val room = FakeJoinedRoom(liveTimeline = liveTimeline)
        val first = TimelineController(room, liveTimeline, offlineOutboxService = outbox)
        val localEchoId = first.addOptimisticLocalEcho("pending after restart", null)

        val result = first.sendOptimisticLocalEcho(localEchoId)
        first.resolveOptimisticLocalEcho(localEchoId, result.exceptionOrNull())

        assertThat(result.isFailure).isTrue()
        assertThat(outbox.entries).hasSize(1)
        first.close()

        val restartedTimelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val restartedTimeline = FakeTimeline(name = "restarted", timelineItems = restartedTimelineItems).apply {
            backwardPaginationStatus.value = Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = false)
        }
        val restartedRoom = FakeJoinedRoom(liveTimeline = restartedTimeline)
        val restarted = TimelineController(restartedRoom, restartedTimeline, offlineOutboxService = outbox)
        val restored = restarted.timelineItems().first { it.isNotEmpty() }.single() as MatrixTimelineItem.Event

        assertThat((restored.event.content as MessageContent).body).isEqualTo("pending after restart")
        assertThat(restored.event.localSendState).isInstanceOf(LocalEventSendState.Failed.Unknown::class.java)
        assertThat(outbox.entries).hasSize(1)

        val confirmedUniqueId = UniqueId("confirmed-after-restart")
        restartedTimelineItems.value = listOf(
            MatrixTimelineItem.Event(
                confirmedUniqueId,
                anEventTimelineItem(
                    eventId = EventId("\$confirmed-after-restart"),
                    transactionId = checkNotNull(restored.event.transactionId),
                    isOwn = true,
                    isRemote = true,
                    timestamp = restored.event.timestamp,
                    content = aMessageContent(body = "pending after restart"),
                ),
            )
        )

        val confirmedItems = restarted.timelineItems().first { items ->
            items.singleOrNull()?.testUniqueId() == confirmedUniqueId
        }
        assertThat(confirmedItems).hasSize(1)
        assertThat((confirmedItems.single() as MatrixTimelineItem.Event).displayUniqueId).isEqualTo(restored.displayUniqueId)
        assertThat(confirmedItems.map { it.testUniqueId() }).containsNoDuplicates()
    }

    @Test
    fun `restored old outbox entries stay outside the loaded window until history reaches them`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val live = FakeTimeline(timelineItems = items)
        val room = FakeJoinedRoom(liveTimeline = live)
        val outbox = FakeOfflineOutboxService().apply { messagesGate = CompletableDeferred() }
        outbox.entries += OfflineTextOutboxMessage(
            transactionId = "old-outbox", accountId = room.sessionId.value, roomId = room.roomId.value,
            body = "old pending", htmlBody = null, mentionedUserIds = emptyList(), mentionsRoom = false,
            repliedToEventId = null, createdAtEpochMillis = 100,
        )
        outbox.entries += outbox.entries.single().copy(
            transactionId = "recent-outbox", body = "recent pending", createdAtEpochMillis = 400,
        )
        val sut = TimelineController(room, live, offlineOutboxService = outbox)
        fun remote(id: String, timestamp: Long) = MatrixTimelineItem.Event(
            UniqueId(id), anEventTimelineItem(
                eventId = EventId("\$$id"), isRemote = true, timestamp = timestamp,
                content = aMessageContent(body = id),
            ),
        )
        sut.timelineItems().test {
            assertThat(awaitItem()).isEmpty()
            outbox.messagesGate?.complete(Unit)
            assertThat(awaitItem()).isEmpty()
            val newest = remote("newest", 300)
            items.value = listOf(newest)
            val initial = awaitItem().filterIsInstance<MatrixTimelineItem.Event>()
            assertThat(initial.map { it.event.timestamp }).containsExactly(300L, 400L).inOrder()
            assertThat(initial.last().event.transactionId?.value).isEqualTo("recent-outbox")
            assertThat(outbox.entries).hasSize(2)
            items.value = listOf(remote("older", 50), newest)
            val paginated = awaitItem().filterIsInstance<MatrixTimelineItem.Event>()
            assertThat(paginated.map { it.event.timestamp }).containsExactly(50L, 100L, 300L, 400L).inOrder()
            assertThat(paginated[1].event.localSendState).isInstanceOf(LocalEventSendState.Failed.Unknown::class.java)
            assertThat(outbox.entries).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
        sut.close()
    }

    @Test
    fun `empty history restores pending entries at timeline start while new composer echoes remain immediate`() = runTest {
        val live = FakeTimeline()
        val room = FakeJoinedRoom(liveTimeline = live)
        val outbox = FakeOfflineOutboxService().apply { messagesGate = CompletableDeferred() }
        outbox.entries += OfflineTextOutboxMessage(
            transactionId = "old-outbox", accountId = room.sessionId.value, roomId = room.roomId.value,
            body = "old pending", htmlBody = null, mentionedUserIds = emptyList(), mentionsRoom = false,
            repliedToEventId = null, createdAtEpochMillis = 100,
        )
        val sut = TimelineController(room, live, offlineOutboxService = outbox)
        sut.timelineItems().test {
            assertThat(awaitItem()).isEmpty()
            outbox.messagesGate?.complete(Unit)
            assertThat(awaitItem()).isEmpty()
            val freshId = sut.addOptimisticLocalEcho("new draft send", null)
            assertThat(awaitItem().filterIsInstance<MatrixTimelineItem.Event>().map { it.displayUniqueId })
                .containsExactly(freshId)
            live.backwardPaginationStatus.value = Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = false)
            assertThat(awaitItem().filterIsInstance<MatrixTimelineItem.Event>().map { it.displayUniqueId.value })
                .containsExactly("old-outbox", freshId.value).inOrder()
            assertThat(outbox.entries).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        sut.close()
    }

    @Test
    fun `outbox encryption failure prevents Matrix send`() = runTest {
        val outbox = FakeOfflineOutboxService(stageResult = false)
        val liveTimeline = FakeTimeline(name = "live").apply {
            sendMessageLambda = lambdaRecorder(ensureNeverCalled = true) { _, _, _, _, _ -> Result.success(Unit) }
        }
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline, offlineOutboxService = outbox)

        val result = sut.sendOptimisticLocalEcho(sut.addOptimisticLocalEcho("must fail closed", null))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `pending echo is inserted before trailing virtual items and keeps its canonical index on confirmation`() = runTest {
        val previousId = UniqueId("previous")
        val typingId = UniqueId("typing")
        val remoteId = UniqueId("remote")
        val previous = MatrixTimelineItem.Event(
            previousId,
            anEventTimelineItem(
                eventId = EventId("\$previous"),
                isOwn = true,
                isRemote = true,
                timestamp = 1L,
                content = aMessageContent(body = "previous"),
            ),
        )
        val typing = MatrixTimelineItem.Virtual(typingId, VirtualTimelineItem.TypingNotification)
        val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(listOf(previous, typing))
        val liveTimeline = FakeTimeline(name = "live", timelineItems = timelineItems)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        val localEchoId = sut.addOptimisticLocalEcho("hello", null)
        val pendingItems = sut.timelineItems().first()
        assertThat(pendingItems.map { it.testUniqueId() }).containsExactly(previousId, localEchoId, typingId).inOrder()
        sut.resolveOptimisticLocalEcho(localEchoId, failure = null)

        timelineItems.value = listOf(
            previous,
            MatrixTimelineItem.Event(
                remoteId,
                anEventTimelineItem(
                    eventId = EventId("\$remote"),
                    isOwn = true,
                    isRemote = true,
                    timestamp = System.currentTimeMillis(),
                    content = aMessageContent(body = "hello"),
                ),
            ),
            typing,
        )
        val confirmedItems = sut.timelineItems().first { items ->
            items.filterIsInstance<MatrixTimelineItem.Event>().any { it.uniqueId == remoteId }
        }
        assertThat(confirmedItems.map { it.testUniqueId() }).containsExactly(previousId, remoteId, typingId).inOrder()
        val confirmed = confirmedItems[1] as MatrixTimelineItem.Event
        assertThat(confirmed.displayUniqueId).isEqualTo(localEchoId)
    }

    @Test
    fun `persisted verified-user failure remains visible so it can be resolved after restart`() = runTest {
        val failedEvent = MatrixTimelineItem.Event(
            A_UNIQUE_ID,
            anEventTimelineItem(
                transactionId = TransactionId("persisted-transaction"),
                isOwn = true,
                isRemote = false,
                localSendState = LocalEventSendState.Failed.VerifiedUserHasUnsignedDevice(
                    mapOf(UserId("@alice:example.org") to listOf(DeviceId("UNSIGNED")))
                ),
                content = aMessageContent(body = "pending").copy(isContentProtectionAuthorized = true),
            )
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(failedEvent)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
            requireContentProtection = true,
        )

        assertThat(sut.timelineItems().first()).containsExactly(failedEvent)
    }

    @Test
    fun `unable to decrypt events remain hidden`() = runTest {
        val liveTimeline = FakeTimeline(
            name = "live",
            timelineItems = flowOf(
                listOf(
                    MatrixTimelineItem.Event(
                        A_UNIQUE_ID,
                        anEventTimelineItem(
                            isRemote = true,
                            content = UnableToDecryptContent(UnableToDecryptContent.Data.Unknown, null),
                        )
                    )
                )
            )
        )
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = liveTimeline), liveTimeline)

        assertThat(sut.timelineItems().first()).isEmpty()
    }

    @Test
    fun `signed identity reset boundary suppresses only old authenticity-not-guaranteed history`() = runTest {
        val oldUnverifiable = MatrixTimelineItem.Event(
            UniqueId("old-unverifiable"),
            anEventTimelineItem(
                eventId = EventId("\$old-unverifiable"),
                isRemote = true,
                timestamp = 99L,
                content = aMessageContent(body = "old identity content"),
                messageShieldProvider = MessageShieldProvider {
                    MessageShield.AuthenticityNotGuaranteed(isCritical = true)
                },
            ),
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(oldUnverifiable)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
            accountHistoryAccessBoundaryProvider = fixedHistoryBoundary(100L),
        )

        assertThat(sut.timelineItems().first()).isEmpty()
    }

    @Test
    fun `identity reset boundary preserves authenticity warning on new events`() = runTest {
        val newUnverifiable = MatrixTimelineItem.Event(
            UniqueId("new-unverifiable"),
            anEventTimelineItem(
                eventId = EventId("\$new-unverifiable"),
                isRemote = true,
                timestamp = 101L,
                content = aMessageContent(body = "new content"),
                messageShieldProvider = MessageShieldProvider {
                    MessageShield.AuthenticityNotGuaranteed(isCritical = true)
                },
            ),
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(newUnverifiable)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
            accountHistoryAccessBoundaryProvider = fixedHistoryBoundary(100L),
        )

        assertThat(sut.timelineItems().first()).containsExactly(newUnverifiable)
    }

    @Test
    fun `identity reset boundary never suppresses other Matrix security warnings`() = runTest {
        val verificationViolation = MatrixTimelineItem.Event(
            UniqueId("verification-violation"),
            anEventTimelineItem(
                eventId = EventId("\$verification-violation"),
                isRemote = true,
                timestamp = 99L,
                content = aMessageContent(body = "must remain warned"),
                messageShieldProvider = MessageShieldProvider {
                    MessageShield.VerificationViolation(isCritical = true)
                },
            ),
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(verificationViolation)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
            accountHistoryAccessBoundaryProvider = fixedHistoryBoundary(100L),
        )

        assertThat(sut.timelineItems().first()).containsExactly(verificationViolation)
    }

    @Test
    fun `without signed reset boundary old authenticity warnings remain visible`() = runTest {
        val oldUnverifiable = MatrixTimelineItem.Event(
            UniqueId("old-unverifiable"),
            anEventTimelineItem(
                eventId = EventId("\$old-unverifiable"),
                isRemote = true,
                timestamp = 99L,
                content = aMessageContent(body = "old content"),
                messageShieldProvider = MessageShieldProvider {
                    MessageShield.AuthenticityNotGuaranteed(isCritical = true)
                },
            ),
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(oldUnverifiable)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
        )

        assertThat(sut.timelineItems().first()).containsExactly(oldUnverifiable)
    }

    @Test
    fun `strict timeline only exposes content protection authorized messages`() = runTest {
        val unauthorized = MatrixTimelineItem.Event(
            UniqueId("unauthorized"),
            anEventTimelineItem(
                isRemote = true,
                content = aMessageContent(body = "must stay hidden"),
            )
        )
        val authorized = MatrixTimelineItem.Event(
            UniqueId("authorized"),
            anEventTimelineItem(
                isRemote = true,
                content = aMessageContent(body = "allowed").copy(isContentProtectionAuthorized = true),
            )
        )
        val liveTimeline = FakeTimeline(name = "live", timelineItems = flowOf(listOf(unauthorized, authorized)))
        val sut = TimelineController(
            room = FakeJoinedRoom(liveTimeline = liveTimeline),
            liveTimeline = liveTimeline,
            requireContentProtection = true,
        )

        assertThat(sut.timelineItems().first()).containsExactly(authorized)
    }

    @Test
    fun `test invokeOnCurrentTimeline use the detached timeline and not the live timeline`() = runTest {
        val lambdaForDetached = lambdaRecorder { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val lambdaForLive = lambdaRecorder(ensureNeverCalled = true) { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val liveTimeline = FakeTimeline(name = "live").apply {
            sendMessageLambda = lambdaForLive
        }
        val detachedTimeline = FakeTimeline(name = "detached").apply {
            sendMessageLambda = lambdaForDetached
        }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) }
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)
        sut.activeTimelineFlow().test {
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline)
            }
            sut.invokeOnCurrentTimeline {
                sendMessage("body", "htmlBody", emptyList())
            }
            lambdaForDetached.assertions().isCalledOnce()
        }
    }

    @Test
    fun `test last forward pagination on a detached timeline should switch to live timeline`() = runTest {
        val liveTimeline = FakeTimeline(name = "live")
        val detachedTimeline = FakeTimeline(name = "detached")
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = liveTimeline,
            createTimelineResult = { Result.success(detachedTimeline) }
        )
        val sut = TimelineController(room = joinedRoom, liveTimeline = liveTimeline)

        sut.activeTimelineFlow().test {
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
            sut.focusOnEvent(AN_EVENT_ID, null)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(detachedTimeline)
            }
            val paginateLambda = lambdaRecorder { _: Timeline.PaginationDirection ->
                Result.success(true)
            }
            detachedTimeline.apply {
                this.paginateLambda = paginateLambda
            }
            sut.paginate(Timeline.PaginationDirection.FORWARDS)
            awaitItem().also { state ->
                assertThat(state).isEqualTo(liveTimeline)
            }
        }
    }
}

private fun fixedHistoryBoundary(resetCommittedAtEpochMillis: Long): AccountHistoryAccessBoundaryProvider =
    object : AccountHistoryAccessBoundaryProvider {
        override suspend fun current(): AccountHistoryAccessBoundary =
            AccountHistoryAccessBoundary(resetCommittedAtEpochMillis)
    }

private class RecordingHiddenMessageRecoveryInventory : HiddenMessageRecoveryInventory {
    override val candidateCount: StateFlow<Int> = MutableStateFlow(0)
    override val candidateCountByRoom: StateFlow<Map<io.element.android.libraries.matrix.api.core.RoomId, Int>> = MutableStateFlow(emptyMap())
    override val recoveryStatus: StateFlow<io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus> =
        MutableStateFlow(io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus.IDLE)
    val clearedRooms = mutableListOf<io.element.android.libraries.matrix.api.core.RoomId>()
    val candidatesByRoom = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, Set<String>>()

    override fun replaceRoomCandidates(
        roomId: io.element.android.libraries.matrix.api.core.RoomId,
        eventIds: Set<String>,
    ) {
        candidatesByRoom[roomId] = eventIds
    }

    override fun clearRoom(roomId: io.element.android.libraries.matrix.api.core.RoomId) {
        clearedRooms += roomId
    }

    override fun updateRecoveryStatus(status: io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus) = Unit
}

private fun MatrixTimelineItem.testUniqueId(): UniqueId = when (this) {
    is MatrixTimelineItem.Event -> uniqueId
    is MatrixTimelineItem.Virtual -> uniqueId
    MatrixTimelineItem.Other -> UniqueId("other")
}

internal fun createTimelineController(
    room: FakeJoinedRoom = FakeJoinedRoom(liveTimeline = FakeTimeline()),
    liveTimeline: Timeline = FakeTimeline(name = "live"),
): TimelineController {
    return TimelineController(
        room = room,
        liveTimeline = liveTimeline
    )
}

private class FakeOfflineOutboxService(
    private val stageResult: Boolean = true,
) : OfflineOutboxService {
    val entries = mutableListOf<OfflineTextOutboxMessage>()
    var messagesGate: CompletableDeferred<Unit>? = null

    override suspend fun stage(message: OfflineTextOutboxMessage): Boolean {
        if (stageResult) {
            entries.removeAll { it.transactionId == message.transactionId }
            entries += message
        }
        return stageResult
    }

    override suspend fun messages(accountId: String, roomId: String): List<OfflineTextOutboxMessage> {
        messagesGate?.await()
        return entries.filter { it.accountId == accountId && it.roomId == roomId }
    }

    override suspend fun remove(accountId: String, roomId: String, transactionId: String): Boolean {
        entries.removeAll {
            it.accountId == accountId && it.roomId == roomId && it.transactionId == transactionId
        }
        return true
    }
}
