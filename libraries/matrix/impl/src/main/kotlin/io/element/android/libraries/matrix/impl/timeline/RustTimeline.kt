/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent

import io.element.android.libraries.matrix.api.timeline.PendingSendLifetime

import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.join.JoinRule
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.ForwardEventPairResult
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.QueuedEncryptedRoomMessageReceipt
import io.element.android.libraries.matrix.api.timeline.QueuedEncryptedRoomMessageSender
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.api.media.MediaSendCancellationContext
import io.element.android.libraries.matrix.impl.media.CancellableProtectedMediaUpload
import io.element.android.libraries.matrix.impl.media.MediaUploadHandlerImpl
import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.libraries.matrix.impl.media.map
import io.element.android.libraries.matrix.impl.poll.toInner
import io.element.android.libraries.matrix.impl.room.RoomContentForwarder
import io.element.android.libraries.matrix.impl.room.ForwardTargetUnavailableException
import io.element.android.libraries.matrix.impl.room.location.into
import io.element.android.libraries.matrix.impl.timeline.item.event.EventTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.matrix.impl.timeline.item.virtual.VirtualTimelineItemMapper
import io.element.android.libraries.matrix.impl.timeline.postprocessor.FilterEmptyDayPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.FilterPublicMembershipChangesPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.withLastForwardIndicators
import io.element.android.libraries.matrix.impl.timeline.postprocessor.LoadingIndicatorsPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.RoomBeginningPostProcessor
import io.element.android.libraries.matrix.impl.timeline.postprocessor.TypingNotificationPostProcessor
import io.element.android.libraries.matrix.impl.timeline.reply.InReplyToMapper
import io.element.android.libraries.matrix.impl.util.MessageEventContent
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.PollData
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import uniffi.matrix_sdk.PaginationStatus
import java.io.File
import java.util.UUID
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import org.matrix.rustcomponents.sdk.EventOrTransactionId as RustEventOrTransactionId
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

private const val PAGINATION_SIZE = 50
private const val ROOM_MESSAGE_EVENT_TYPE = "m.room.message"
private const val SEND_QUEUE_RECEIPT_TIMEOUT_MILLIS = 120_000L

@Suppress("LargeClass")
class RustTimeline(
    private val inner: InnerTimeline,
    override val mode: Timeline.Mode,
    systemClock: SystemClock,
    private val joinedRoom: JoinedRoom,
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val roomContentForwarder: RoomContentForwarder,
    private val protectedContentAdapter: MatrixProtectedContentAdapter = DisabledMatrixProtectedContentAdapter,
    private val protectedCaptionLineageWiring: ProtectedCaptionLineageWiring = UnavailableProtectedCaptionLineageWiring,
    private val protectedMessageContentFactory: (String, List<IntentionalMention>) -> RoomMessageEventContentWithoutRelation =
        MessageEventContent::protected,
    private val rawMessageSender: suspend (eventType: String, contentJson: String) -> Unit = { _, _ ->
        error("Raw Matrix message sending is unavailable.")
    },
    private val sendQueueUpdateSubscriber: suspend (SendQueueListener) -> TaskHandle = {
        error("Matrix send queue updates are unavailable.")
    },
    private val queuedRawMessageEnqueuer: suspend (
        eventType: String,
        contentJson: String,
        transactionId: String,
    ) -> Unit = { _, _, _ -> error("Queued raw Matrix message sending is unavailable.") },
    private val sendQueueReceiptTimeoutMillis: Long = SEND_QUEUE_RECEIPT_TIMEOUT_MILLIS,
    private val serverEventBindingEnabled: Boolean = false,
) : Timeline, QueuedEncryptedRoomMessageSender {
    private val _timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 32)

    private val _membershipChangeEventReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _onSyncedEventReceived: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    private val timelineEventContentMapper = TimelineEventContentMapper()
    private val inReplyToMapper = InReplyToMapper(timelineEventContentMapper)
    private val eventTimelineItemMapper = EventTimelineItemMapper(
        contentMapper = timelineEventContentMapper,
    )
    private val timelineItemMapper = MatrixTimelineItemMapper(
        fetchDetailsForEvent = this::fetchDetailsForEvent,
        coroutineScope = coroutineScope,
        virtualTimelineItemMapper = VirtualTimelineItemMapper(),
        eventTimelineItemMapper = eventTimelineItemMapper,
    )
    private val timelineDiffProcessor = MatrixTimelineDiffProcessor(
        timelineItems = _timelineItems,
        membershipChangeEventReceivedFlow = _membershipChangeEventReceived,
        syncedEventReceivedFlow = _onSyncedEventReceived,
        timelineItemMapper = timelineItemMapper,
        protectedContentAdapter = protectedContentAdapter,
        coroutineScope = coroutineScope,
        decryptDispatcher = dispatcher,
        mediaRoomId = joinedRoom.roomId.value,
    )
    private val timelineItemsSubscriber = TimelineItemsSubscriber(
        timeline = inner,
        timelineCoroutineScope = coroutineScope,
        timelineDiffProcessor = timelineDiffProcessor,
        dispatcher = dispatcher,
    )
    private val preparedMessageMutex = Mutex()
    private val preparedMessageTransactions = mutableMapOf<String, PreparedProtectedContent>()
    private val pendingTimelineSends = PendingSendLifetime {
        coroutineScope.cancel()
        inner.close()
    }

    private val roomBeginningPostProcessor = RoomBeginningPostProcessor(mode)
    private val loadingIndicatorsPostProcessor = LoadingIndicatorsPostProcessor(systemClock)
    private val typingNotificationPostProcessor = TypingNotificationPostProcessor(mode)
    private val publicMembershipChangesPostProcessor = FilterPublicMembershipChangesPostProcessor()
    private val emptyDayPostProcessor = FilterEmptyDayPostProcessor()

    private data class RoomTimelineInfo(
        val roomCreators: ImmutableList<UserId>,
        val isDm: Boolean,
        val joinRule: JoinRule?,
        val isEncrypted: Boolean?,
    )

    override val backwardPaginationStatus = MutableStateFlow(
        Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = mode != Timeline.Mode.PinnedEvents)
    )

    override val forwardPaginationStatus = MutableStateFlow(
        Timeline.PaginationStatus(isPaginating = false, hasMoreToLoad = mode is Timeline.Mode.FocusedOnEvent)
    )

    private val loggerTag = "Timeline($mode)"

    init {
        when (mode) {
            is Timeline.Mode.Live, is Timeline.Mode.FocusedOnEvent -> coroutineScope.fetchMembers()
            else -> Unit
        }

        if (mode == Timeline.Mode.Live) {
            // When timeline is live, we need to listen to the back pagination status as
            // sdk can automatically paginate backwards.
            coroutineScope.registerBackPaginationStatusListener()
        }
    }

    private fun CoroutineScope.registerBackPaginationStatusListener() {
        inner.liveBackPaginationStatus()
            .onEach { backPaginationStatus ->
                updatePaginationStatus(Timeline.PaginationDirection.BACKWARDS) {
                    when (backPaginationStatus) {
                        is PaginationStatus.Idle -> it.copy(isPaginating = false, hasMoreToLoad = !backPaginationStatus.hitTimelineStart)
                        is PaginationStatus.Paginating -> it.copy(isPaginating = true, hasMoreToLoad = true)
                    }
                }
            }
            .launchIn(this)
    }

    override val membershipChangeEventReceived: Flow<Unit> = _membershipChangeEventReceived
        .onStart { timelineItemsSubscriber.subscribeIfNeeded() }
        .onCompletion { timelineItemsSubscriber.unsubscribeIfNeeded() }

    override val onSyncedEventReceived: Flow<Unit> = _onSyncedEventReceived
        .onStart { timelineItemsSubscriber.subscribeIfNeeded() }
        .onCompletion { timelineItemsSubscriber.unsubscribeIfNeeded() }

    override fun updateViewport(visibleItemIds: List<UniqueId>) {
        timelineItemsSubscriber.updateViewport(visibleItemIds)
    }

    override suspend fun sendReadReceipt(eventId: EventId, receiptType: ReceiptType): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            inner.sendReadReceipt(receiptType.toRustReceiptType(), eventId.value)
        }
    }

    override suspend fun markAsRead(receiptType: ReceiptType): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            inner.markAsRead(receiptType.toRustReceiptType())
        }
    }

    override suspend fun enqueueEncryptedRoomMessage(
        transactionId: TransactionId,
        canonicalContentJson: String,
    ): Result<QueuedEncryptedRoomMessageReceipt> = withContext(dispatcher) {
        val localEcho = CompletableDeferred<Unit>()
        val sentEventId = CompletableDeferred<String>()
        val expectedTransactionId = transactionId.value
        val listener = object : SendQueueListener {
            override fun onUpdate(update: RoomSendQueueUpdate) {
                when (update) {
                    is RoomSendQueueUpdate.NewLocalEvent -> {
                        if (update.transactionId == expectedTransactionId) localEcho.complete(Unit)
                    }
                    is RoomSendQueueUpdate.SentEvent -> {
                        if (update.transactionId == expectedTransactionId) sentEventId.complete(update.eventId)
                    }
                    is RoomSendQueueUpdate.CancelledLocalEvent -> {
                        if (update.transactionId == expectedTransactionId) {
                            val failure = IllegalStateException("Queued Matrix event was cancelled before sending.")
                            localEcho.completeExceptionally(failure)
                            sentEventId.completeExceptionally(failure)
                        }
                    }
                    is RoomSendQueueUpdate.ReplacedLocalEvent -> {
                        if (update.transactionId == expectedTransactionId) {
                            val failure = IllegalStateException("Queued Matrix event content was replaced.")
                            localEcho.completeExceptionally(failure)
                            sentEventId.completeExceptionally(failure)
                        }
                    }
                    is RoomSendQueueUpdate.SendError -> {
                        if (update.transactionId == expectedTransactionId) {
                            val failure = IllegalStateException("Queued Matrix event could not be sent.")
                            localEcho.completeExceptionally(failure)
                            sentEventId.completeExceptionally(failure)
                        }
                    }
                    is RoomSendQueueUpdate.MediaUpload,
                    is RoomSendQueueUpdate.RetryEvent -> Unit
                }
            }
        }

        var updateSubscription: TaskHandle? = null
        try {
            val eventId = withTimeout(sendQueueReceiptTimeoutMillis) {
                // The suspending native subscription returns only after RoomSendQueue::subscribe
                // has completed, so no queue update can race ahead of listener readiness. The
                // deadline covers subscription, durable enqueue, and both required receipts.
                updateSubscription = sendQueueUpdateSubscriber(listener)
                queuedRawMessageEnqueuer(
                    ROOM_MESSAGE_EVENT_TYPE,
                    canonicalContentJson,
                    expectedTransactionId,
                )
                localEcho.await()
                sentEventId.await()
            }
            Result.success(
                QueuedEncryptedRoomMessageReceipt(
                    transactionId = transactionId,
                    eventId = EventId(eventId),
                    localEchoObserved = true,
                )
            )
        } catch (failure: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    "Queued Matrix event outcome is uncertain after waiting for local echo and sent event.",
                    failure,
                )
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            updateSubscription?.let { subscription ->
                try {
                    subscription.cancel()
                } finally {
                    subscription.destroy()
                }
            }
        }
    }

    private fun updatePaginationStatus(direction: Timeline.PaginationDirection, update: (Timeline.PaginationStatus) -> Timeline.PaginationStatus) {
        val result = when (direction) {
            Timeline.PaginationDirection.BACKWARDS -> backwardPaginationStatus.getAndUpdate(update)
            Timeline.PaginationDirection.FORWARDS -> forwardPaginationStatus.getAndUpdate(update)
        }
        Timber.tag(loggerTag).d("updatePaginationStatus $direction: $result")
    }

    // Use NonCancellable to avoid breaking the timeline when the coroutine is cancelled.
    override suspend fun paginate(direction: Timeline.PaginationDirection): Result<Boolean> = withContext(NonCancellable) {
        withContext(dispatcher) {
            runCatchingExceptions {
                if (!canPaginate(direction)) throw TimelineException.CannotPaginate
                updatePaginationStatus(direction) { it.copy(isPaginating = true) }
                when (direction) {
                    Timeline.PaginationDirection.BACKWARDS -> inner.paginateBackwards(PAGINATION_SIZE.toUShort())
                    Timeline.PaginationDirection.FORWARDS -> inner.paginateForwards(PAGINATION_SIZE.toUShort())
                }
            }.onFailure { error ->
                if (error is TimelineException.CannotPaginate) {
                    Timber.tag(loggerTag).d("Can't paginate $direction on room ${joinedRoom.roomId} with paginationStatus: ${backwardPaginationStatus.value}")
                } else {
                    updatePaginationStatus(direction) { it.copy(isPaginating = false) }
                    Timber.tag(loggerTag).e(error, "Error paginating $direction on room ${joinedRoom.roomId}")
                }
            }.onSuccess { hasReachedEnd ->
                Timber.tag(loggerTag).d("Finished paginating $direction on room ${joinedRoom.roomId}, hasReachedEnd: $hasReachedEnd")
                updatePaginationStatus(direction) { it.copy(isPaginating = false, hasMoreToLoad = !hasReachedEnd) }
            }
        }
    }

    private fun canPaginate(direction: Timeline.PaginationDirection): Boolean {
        return when (direction) {
            Timeline.PaginationDirection.BACKWARDS -> backwardPaginationStatus.value.canPaginate
            Timeline.PaginationDirection.FORWARDS -> forwardPaginationStatus.value.canPaginate
        }
    }

    override val timelineItems: Flow<List<MatrixTimelineItem>> = combine(
        _timelineItems,
        backwardPaginationStatus,
        forwardPaginationStatus,
        joinedRoom.roomInfoFlow.map { RoomTimelineInfo(it.creators, it.isDm, it.joinRule, it.isEncrypted) }.distinctUntilChanged(),
    ) {
        timelineItems,
        backwardPaginationStatus,
        forwardPaginationStatus,
        roomInfo,
        ->
        withContext(dispatcher) {
            val (roomCreators, isDm, joinRule, isEncrypted) = roomInfo
            timelineItems
                .let { items ->
                    roomBeginningPostProcessor.process(
                        items = items,
                        isDm = isDm,
                        roomCreator = roomCreators.firstOrNull(),
                        hasMoreToLoadBackwards = backwardPaginationStatus.hasMoreToLoad,
                    )
                }
                // This should be the first post processor after room beginning.
                .let { items ->
                    publicMembershipChangesPostProcessor.process(
                        items = items,
                        joinRule = joinRule,
                        isEncrypted = isEncrypted,
                    )
                }
                // After removing public membership changes, we might end up with empty days, so we need to filter them out.
                .let { items ->
                    emptyDayPostProcessor.process(items)
                }
                .let { items ->
                    loadingIndicatorsPostProcessor.process(
                        items = items,
                        hasMoreToLoadBackward = backwardPaginationStatus.hasMoreToLoad,
                        hasMoreToLoadForward = forwardPaginationStatus.hasMoreToLoad,
                    )
                }
                .let { items ->
                    typingNotificationPostProcessor.process(items = items)
                }
        }
    }.withLastForwardIndicators(mode).onStart {
        timelineItemsSubscriber.subscribeIfNeeded()
    }.onCompletion {
        timelineItemsSubscriber.unsubscribeIfNeeded()
    }

    override fun close() {
        pendingTimelineSends.close()
    }

    private fun CoroutineScope.fetchMembers() = launch(dispatcher) {
        try {
            inner.fetchMembers()
        } catch (exception: Exception) {
            Timber.tag(loggerTag).e(exception, "Error fetching members for room ${joinedRoom.roomId}")
        }
    }

    override suspend fun sendMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType,
        asPlainText: Boolean,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions<Unit> {
            if (protectedContentAdapter.enabled()) {
                val prepared = protectedContentAdapter.protectText(body, htmlBody, msgType, asPlainText)
                try {
                    sendPreparedMessage(prepared, intentionalMentions, repliedToEventId = null, allowServerBinding = true)
                } catch (failure: Exception) {
                    destroyFailedPrepared(prepared, failure)
                    throw failure
                }
            } else {
                MessageEventContent.from(body, htmlBody, intentionalMentions, msgType, asPlainText).use {
                    sendAndMeasure(it)
                }
            }
        }
    }

    override suspend fun sendBurnAfterReadMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType,
        asPlainText: Boolean,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            check(protectedContentAdapter.enabled()) { "Burn-after-read requires protected content." }
            val prepared = protectedContentAdapter.protectBurnAfterReadText(body, htmlBody, msgType, asPlainText)
            try {
                sendPreparedMessage(prepared, intentionalMentions, repliedToEventId = null)
            } catch (failure: Exception) {
                destroyFailedPrepared(prepared, failure)
                throw failure
            }
        }
    }

    override suspend fun openBurnAfterRead(eventId: EventId): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            val raw = rawItemsForDestruction(eventId.toEventOrTransactionId()).singleOrNull()
                ?: error("The protected event is unavailable.")
            val authorizedItem = protectedContentAdapter.openBurnAfterRead(raw)
            try {
                check(timelineDiffProcessor.publishAuthorizedProjection(raw, authorizedItem)) {
                    "The authorized burn-after-read projection is no longer current."
                }
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    runCatching {
                        protectedContentAdapter.endBurnAfterRead(
                            raw,
                            io.element.android.security.contentprotection.api.BurnAfterReadEndReason.VIEW_CLOSED,
                        )
                    }
                }
                throw failure
            }
        }
    }

    override suspend fun endBurnAfterRead(
        eventId: EventId,
        reason: Timeline.BurnAfterReadEndReason,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            val raw = rawItemsForDestruction(eventId.toEventOrTransactionId()).singleOrNull()
                ?: error("The protected event is unavailable.")
            try {
                check(protectedContentAdapter.endBurnAfterRead(
                    raw,
                    io.element.android.security.contentprotection.api.BurnAfterReadEndReason.valueOf(reason.name),
                )) { "The view session is not active." }
            } finally {
                withContext(NonCancellable) {
                    timelineDiffProcessor.clearAuthorizedProjection(raw)
                }
            }
        }
    }

    override suspend fun sendMessageTransaction(
        transactionId: TransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        repliedToEventId: EventId?,
        msgType: MsgType,
        asPlainText: Boolean,
    ): Result<Unit> = runCatchingExceptions {
        pendingTimelineSends.withSend {
            withContext(dispatcher) {
                if (!protectedContentAdapter.enabled()) {
                    if (repliedToEventId == null) {
                        MessageEventContent.from(body, htmlBody, intentionalMentions, msgType, asPlainText).use { sendAndMeasure(it) }
                    } else {
                        MessageEventContent.from(body, htmlBody, intentionalMentions, msgType, asPlainText).use {
                            inner.sendReply(it, repliedToEventId.value)
                        }
                    }
                    return@withContext
                }
                val prepared = preparedMessageMutex.withLock {
                    preparedMessageTransactions[transactionId.value] ?: if (repliedToEventId == null) {
                        protectedContentAdapter.protectText(
                            body = body,
                            htmlBody = htmlBody,
                            msgType = msgType,
                            asPlainText = asPlainText,
                            contentId = stableContentId(transactionId),
                        )
                    } else {
                        protectedContentAdapter.protectReplyText(
                            body = body,
                            htmlBody = htmlBody,
                            msgType = msgType,
                            asPlainText = asPlainText,
                            repliedToEventId = repliedToEventId,
                            contentId = stableContentId(transactionId),
                        )
                    }.also { preparedMessageTransactions[transactionId.value] = it }
                }
                sendPreparedMessage(prepared, intentionalMentions, repliedToEventId, allowServerBinding = true)
                preparedMessageMutex.withLock {
                    if (preparedMessageTransactions[transactionId.value] == prepared) {
                        preparedMessageTransactions.remove(transactionId.value)
                    }
                }
            }
        }
    }

    override suspend fun discardMessageTransaction(transactionId: TransactionId): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            val prepared = preparedMessageMutex.withLock { preparedMessageTransactions.remove(transactionId.value) }
                ?: return@runCatchingExceptions
            check(protectedContentAdapter.destroyPrepared(prepared, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                "KMS refused to destroy the prepared message share."
            }
        }
    }

    override suspend fun redactEvent(eventOrTransactionId: EventOrTransactionId, reason: String?): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            val protectedContentEnabled = protectedContentAdapter.enabled()
            val rawItems = if (protectedContentEnabled) {
                val rawItems = rawItemsForDestruction(eventOrTransactionId)
                check(rawItems.isNotEmpty()) {
                    "The original protected Matrix event is unavailable; redaction is blocked."
                }
                rawItems.forEach { rawItem ->
                    check(protectedContentAdapter.revokeItemLocally(rawItem)) {
                        "Local content tombstone persistence failed; Matrix redaction is blocked."
                    }
                    timelineDiffProcessor.clearAuthorizedProjection(rawItem)
                }
                if (eventOrTransactionId.eventId == null && rawItems.size > 1) {
                    error("Aggregate destruction requires a confirmed target event.")
                }
                rawItems
            } else {
                emptyList()
            }
            coroutineScope {
                val remoteDestruction = async {
                    runCatchingExceptions {
                        rawItems.forEach { rawItem ->
                            check(protectedContentAdapter.destroyItem(rawItem, ContentAccessDestroyReason.USER_WITHDRAWN)) {
                                "KMS aggregate share destruction failed after the local tombstone was committed."
                            }
                        }
                    }
                }
                val matrixRedaction = async {
                    runCatchingExceptions {
                        rawItems.mapNotNull { (it as? MatrixTimelineItem.Event)?.event?.eventId }
                            .filterNot { it == eventOrTransactionId.eventId }
                            .forEach { operationEventId ->
                                inner.redactEvent(RustEventOrTransactionId.EventId(operationEventId.value), reason = null)
                            }
                        inner.redactEvent(
                            eventOrTransactionId = eventOrTransactionId.toRustEventOrTransactionId(),
                            // Redaction reasons are ordinary Matrix plaintext. Never place one on the wire in strict mode.
                            reason = reason.takeUnless { protectedContentEnabled },
                        )
                        logRedactionStage("T1", eventOrTransactionId.diagnosticIdentity())
                    }
                }
                val matrixResult = matrixRedaction.await()
                val destructionResult = remoteDestruction.await()
                matrixResult.getOrThrow()
                destructionResult.getOrThrow()
                if (protectedContentEnabled) {
                    val redactedEventIds = buildSet {
                        eventOrTransactionId.eventId?.let(::add)
                        rawItems.mapNotNullTo(this) { (it as? MatrixTimelineItem.Event)?.event?.eventId }
                    }
                    check(redactedEventIds.isNotEmpty()) {
                        "The protected Matrix redaction target is unavailable for authority confirmation."
                    }
                    redactedEventIds.forEach { eventId ->
                        check(protectedContentAdapter.confirmMatrixRedaction(eventId)) {
                            "Matrix did not confirm the accepted redaction from authoritative storage."
                        }
                    }
                }
            }
        }
    }

    /**
     * Recovers the authenticated event-to-content-id mapping even when the event is outside the
     * currently projected window. The Matrix SDK event store persists the original protected
     * envelope/media transport, which carries the opaque KMS content id; deletion remains blocked
     * if neither the live raw projection nor that persisted event can be read.
     */
    private suspend fun rawItemsForDestruction(eventOrTransactionId: EventOrTransactionId): List<MatrixTimelineItem> {
        val projected = timelineDiffProcessor.rawItemsForDestruction(eventOrTransactionId)
        if (projected.isNotEmpty()) return projected
        val eventId = eventOrTransactionId.eventId ?: return emptyList()
        val persisted = inner.getEventTimelineItemByEventId(eventId.value)
        return listOf(
            MatrixTimelineItem.Event(
                uniqueId = UniqueId("destruction:${eventId.value}"),
                event = eventTimelineItemMapper.map(persisted),
            )
        )
    }

    private suspend fun requireNotBurnAfterRead(
        eventOrTransactionId: EventOrTransactionId,
        operation: String,
    ) {
        if (!protectedContentAdapter.enabled()) return
        val raw = rawItemsForDestruction(eventOrTransactionId).firstOrNull { item ->
            val event = (item as? MatrixTimelineItem.Event)?.event ?: return@firstOrNull false
            EventOrTransactionId.from(event.eventId, event.transactionId) == eventOrTransactionId
        }
            ?: error("Protected target event is unavailable.")
        check(!protectedContentAdapter.isBurnAfterRead(raw)) {
            "Burn-after-read messages cannot be $operation."
        }
    }

    override suspend fun editMessage(
        eventOrTransactionId: EventOrTransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            requireNotBurnAfterRead(eventOrTransactionId, "edited")
            val prepared = if (protectedContentAdapter.enabled()) {
                val eventId = requireNotNull(eventOrTransactionId.eventId?.value) { "TEXT edit requires a confirmed event." }
                val target = rawItemsForDestruction(eventOrTransactionId).filterIsInstance<MatrixTimelineItem.Event>()
                    .singleOrNull { it.eventId?.value == eventId } ?: throw SecurityException("Exact TEXT edit target unavailable.")
                protectedContentAdapter.protectTextEdit(target, body, htmlBody)
            } else {
                null
            }
            try {
                val content = prepared?.let {
                    protectedMessageContentFactory(it.encodedEnvelope, intentionalMentions)
                } ?: MessageEventContent.from(body, htmlBody, intentionalMentions)
                val editedContent = EditedContent.RoomMessage(
                    content = content,
                )
                inner.edit(
                    newContent = editedContent,
                    eventOrTransactionId = eventOrTransactionId.toRustEventOrTransactionId(),
                )
            } catch (failure: Exception) {
                // SDK edit can have queued/sent before its response fails. The encrypted version
                // journal owns recovery; deleting this share could invalidate a delivered edit.
                throw failure
            }
        }
    }

    override suspend fun editCaption(
        eventOrTransactionId: EventOrTransactionId,
        caption: String?,
        formattedCaption: String?,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions<Unit> {
            requireNotBurnAfterRead(eventOrTransactionId, "edited")
            if (protectedContentAdapter.enabled()) {
                val targetEventId = requireNotNull(eventOrTransactionId.eventId?.value) {
                    "Protected caption editing requires a confirmed target event."
                }
                val operationId = requireNotNull(currentLifecycleOperationContext()?.operationId) {
                    "Protected caption editing requires an authenticated lifecycle operation."
                }
                val rawTarget = rawItemsForDestruction(eventOrTransactionId).filterIsInstance<MatrixTimelineItem.Event>()
                    .singleOrNull { it.eventId?.value == targetEventId }
                    ?: throw SecurityException("Protected caption target is not available as an exact synced event.")
                val target = protectedContentAdapter.authenticatedCaptionTarget(rawTarget)
                val plan = protectedCaptionLineageWiring.prepare(
                    targetEventId, operationId, target.contentId, target.expiresAtEpochMillis,
                )
                val prepared = try {
                    protectedContentAdapter.protectInteraction(
                        kind = ContentKind.MEDIA_CAPTION,
                        payload = ProtectedMatrixPayload(
                            payloadType = "OPERATION",
                            operationId = operationId,
                            operation = "CAPTION_SET",
                            targetEventId = targetEventId,
                            aggregateId = targetEventId,
                            caption = caption,
                            formattedCaption = formattedCaption,
                        ),
                        contentId = plan.childContentId,
                    )
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        runCatching { protectedCaptionLineageWiring.protectionOutcomeUnknown(plan, failure) }
                            .onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
                try {
                    sendPreparedMessage(prepared, emptyList(), repliedToEventId = null)
                    protectedCaptionLineageWiring.matrixSendAccepted(plan)
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        runCatching { protectedCaptionLineageWiring.matrixSendOutcomeUnknown(plan, failure) }
                            .onFailure(failure::addSuppressed)
                    }
                    // `Timeline.send` does not distinguish rejection from an accepted send whose response was lost.
                    // Keep both the KMS share and durable pending operation for synced-event reconciliation.
                    throw failure
                }
            } else {
                val editedContent = EditedContent.MediaCaption(
                    caption = caption,
                    formattedCaption = formattedCaption?.let { FormattedBody(body = it, format = MessageFormat.Html) },
                    mentions = null,
                )
                withContext(Dispatchers.IO) {
                    inner.edit(
                        eventOrTransactionId = eventOrTransactionId.toRustEventOrTransactionId(),
                        newContent = editedContent,
                    )
                }
            }
        }
    }

    override suspend fun replyMessage(
        repliedToEventId: EventId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        fromNotification: Boolean,
        msgType: MsgType,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (protectedContentAdapter.enabled()) {
                val rawReplyTarget = rawItemsForDestruction(repliedToEventId.toEventOrTransactionId()).singleOrNull()
                    ?: error("Protected reply target is unavailable.")
                check(!protectedContentAdapter.isBurnAfterRead(rawReplyTarget)) {
                    "Burn-after-read messages cannot be quoted or replied to."
                }
                val prepared = protectedContentAdapter.protectReplyText(
                    body = body,
                    htmlBody = htmlBody,
                    msgType = msgType,
                    asPlainText = false,
                    repliedToEventId = repliedToEventId,
                )
                try {
                    sendPreparedMessage(prepared, intentionalMentions, repliedToEventId)
                } catch (failure: Exception) {
                    destroyFailedPrepared(prepared, failure)
                    throw failure
                }
            } else {
                MessageEventContent.from(body, htmlBody, intentionalMentions, msgType).use {
                    inner.sendReply(msg = it, eventId = repliedToEventId.value)
                }
            }
        }
    }

    override suspend fun sendImage(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
    ): Result<MediaUploadHandler> {
        Timber.tag(loggerTag).d("Sending image ${file.path.hash()}")
        if (protectedContentAdapter.enabled()) {
            return sendProtectedAttachment(
                plaintextFiles = listOfNotNull(file, thumbnailFile),
                mediaToProtect = listOfNotNull(file to ContentKind.IMAGE, thumbnailFile?.let { it to ContentKind.IMAGE }),
                caption = caption,
                formattedCaption = formattedCaption,
                burnAfterRead = burnAfterRead,
                mediaAlbum = mediaAlbum,
            ) { protectedFiles, protectedCaption ->
                val protectedFile = protectedFiles.first()
                val protectedThumbnail = protectedFiles.getOrNull(1)
                val protectedInfo = imageInfo.copy(
                    size = protectedFile.length(),
                    thumbnailInfo = imageInfo.thumbnailInfo?.copy(size = protectedThumbnail?.length()),
                )
                inner.sendImage(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = protectedCaption,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        thumbnailSource = protectedThumbnail?.path?.let(UploadSource::File),
                        imageInfo = protectedInfo.map(),
                )
            }
        }
        return sendAttachment(listOfNotNull(file, thumbnailFile)) {
            inner.sendImage(
                params = UploadParameters(
                    source = UploadSource.File(file.path),
                    caption = caption,
                    formattedCaption = formattedCaption?.let {
                        FormattedBody(body = it, format = MessageFormat.Html)
                    },
                    mentions = null,
                    inReplyTo = inReplyToEventId?.value,
                ),
                thumbnailSource = thumbnailFile?.path?.let(UploadSource::File),
                imageInfo = imageInfo.map(),
            )
        }
    }

    override suspend fun sendSticker(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
    ): Result<MediaUploadHandler> {
        require(protectedContentAdapter.enabled()) { "Stickers require YiZhe protected content." }
        return sendProtectedAttachment(
            plaintextFiles = listOfNotNull(file, thumbnailFile),
            mediaToProtect = listOfNotNull(file to ContentKind.STICKER, thumbnailFile?.let { it to ContentKind.IMAGE }),
        ) { protectedFiles, _ ->
            val protectedFile = protectedFiles.first()
            val protectedThumbnail = protectedFiles.getOrNull(1)
            inner.sendImage(
                params = UploadParameters(
                    source = UploadSource.File(protectedFile.path),
                    caption = null,
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = null,
                ),
                thumbnailSource = protectedThumbnail?.path?.let(UploadSource::File),
                imageInfo = imageInfo.copy(
                    size = protectedFile.length(),
                    thumbnailInfo = imageInfo.thumbnailInfo?.copy(size = protectedThumbnail?.length()),
                ).map(),
            )
        }
    }

    override suspend fun sendVideo(
        file: File,
        thumbnailFile: File?,
        videoInfo: VideoInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
    ): Result<MediaUploadHandler> {
        Timber.tag(loggerTag).d("Sending video ${file.path.hash()}")
        if (protectedContentAdapter.enabled()) {
            return sendProtectedAttachment(
                plaintextFiles = listOfNotNull(file, thumbnailFile),
                mediaToProtect = listOfNotNull(file to ContentKind.VIDEO, thumbnailFile?.let { it to ContentKind.IMAGE }),
                caption = caption,
                formattedCaption = formattedCaption,
                burnAfterRead = burnAfterRead,
                mediaAlbum = mediaAlbum,
            ) { protectedFiles, protectedCaption ->
                val protectedFile = protectedFiles.first()
                val protectedThumbnail = protectedFiles.getOrNull(1)
                val protectedInfo = videoInfo.copy(
                    size = protectedFile.length(),
                    thumbnailInfo = videoInfo.thumbnailInfo?.copy(size = protectedThumbnail?.length()),
                )
                inner.sendVideo(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = protectedCaption,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        thumbnailSource = protectedThumbnail?.path?.let(UploadSource::File),
                        videoInfo = protectedInfo.map(),
                )
            }
        }
        return sendAttachment(listOfNotNull(file, thumbnailFile)) {
            inner.sendVideo(
                params = UploadParameters(
                    source = UploadSource.File(file.path),
                    caption = caption,
                    formattedCaption = formattedCaption?.let {
                        FormattedBody(body = it, format = MessageFormat.Html)
                    },
                    mentions = null,
                    inReplyTo = inReplyToEventId?.value,
                ),
                thumbnailSource = thumbnailFile?.path?.let(UploadSource::File),
                videoInfo = videoInfo.map(),
            )
        }
    }

    override suspend fun sendAudio(
        file: File,
        audioInfo: AudioInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> {
        Timber.tag(loggerTag).d("Sending audio ${file.path.hash()}")
        if (protectedContentAdapter.enabled()) {
            return sendProtectedAttachment(
                plaintextFiles = listOf(file),
                mediaToProtect = listOf(file to ContentKind.VOICE_MESSAGE),
                caption = caption,
                formattedCaption = formattedCaption,
                burnAfterRead = burnAfterRead,
            ) { protectedFiles, protectedCaption ->
                val protectedFile = protectedFiles.single()
                inner.sendAudio(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = protectedCaption,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        audioInfo = audioInfo.copy(size = protectedFile.length()).map(),
                )
            }
        }
        return sendAttachment(listOf(file)) {
            inner.sendAudio(
                params = UploadParameters(
                    source = UploadSource.File(file.path),
                    caption = caption,
                    formattedCaption = formattedCaption?.let {
                        FormattedBody(body = it, format = MessageFormat.Html)
                    },
                    mentions = null,
                    inReplyTo = inReplyToEventId?.value,
                ),
                audioInfo = audioInfo.map(),
            )
        }
    }

    override suspend fun sendFile(
        file: File,
        fileInfo: FileInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> {
        Timber.tag(loggerTag).d("Sending file ${file.path.hash()}")
        if (protectedContentAdapter.enabled()) {
            return sendProtectedAttachment(
                plaintextFiles = listOf(file),
                mediaToProtect = listOf(file to ContentKind.ATTACHMENT),
                caption = caption,
                formattedCaption = formattedCaption,
                burnAfterRead = burnAfterRead,
                attachmentMetadata = ProtectedAttachmentMetadata(
                    displayName = file.name,
                    mimeType = fileInfo.mimetype,
                    size = fileInfo.size ?: file.length(),
                ),
            ) { protectedFiles, protectedCaption ->
                val protectedFile = protectedFiles.single()
                inner.sendFile(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = protectedCaption,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        fileInfo = fileInfo.copy(
                            mimetype = MimeTypes.OctetStream,
                            size = protectedFile.length(),
                        ).map(),
                )
            }
        }
        return sendAttachment(listOf(file)) {
            inner.sendFile(
                params = UploadParameters(
                    source = UploadSource.File(file.path),
                    caption = caption,
                    formattedCaption = formattedCaption?.let {
                        FormattedBody(body = it, format = MessageFormat.Html)
                    },
                    mentions = null,
                    inReplyTo = inReplyToEventId?.value,
                ),
                fileInfo = fileInfo.map(),
            )
        }
    }

    override suspend fun toggleReaction(emoji: String, eventOrTransactionId: EventOrTransactionId): Result<Boolean> = withContext(dispatcher) {
        runCatchingExceptions {
            requireNotBurnAfterRead(eventOrTransactionId, "reacted to")
            if (!protectedContentAdapter.enabled()) {
                return@runCatchingExceptions inner.toggleReaction(
                    key = emoji,
                    itemId = eventOrTransactionId.toRustEventOrTransactionId(),
                )
            }
            val targetEventId = requireNotNull(eventOrTransactionId.eventId?.value) {
                "Protected reactions require a confirmed target event."
            }
            val target = currentEvent(targetEventId)
            val currentlyEnabled = target?.event?.reactions.orEmpty().any { reaction ->
                reaction.key == emoji && reaction.senders.any { it.senderId.value == joinedRoom.sessionId.value }
            }
            val enabled = !currentlyEnabled
            sendInteraction(
                ContentKind.REACTION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                    operation = "REACTION_SET",
                    targetEventId = targetEventId,
                    aggregateId = targetEventId,
                    emoji = emoji,
                    enabled = enabled,
                )
            )
            enabled
        }
    }

    override suspend fun forwardEvent(eventId: EventId, roomIds: List<RoomId>): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (protectedContentAdapter.enabled()) {
                val targetId = eventId.toEventOrTransactionId()
                val raw = rawItemsForDestruction(targetId).firstOrNull { item ->
                    val event = (item as? MatrixTimelineItem.Event)?.event ?: return@firstOrNull false
                    EventOrTransactionId.from(event.eventId, event.transactionId) == targetId
                }
                    ?: error("Protected source event is unavailable.")
                check(!protectedContentAdapter.isBurnAfterRead(raw)) { "Burn-after-read messages cannot be forwarded." }
                val content = currentEvent(eventId.value)?.event?.content ?: error("Protected source event is unavailable.")
                roomContentForwarder.forwardProtected(content, roomIds)
            } else {
                roomContentForwarder.forward(fromTimeline = inner, eventId = eventId, toRoomIds = roomIds)
            }
        }.onFailure {
            Timber.tag(loggerTag).e(it)
        }
    }

    override suspend fun forwardEventPair(eventId: EventId, roomId: RoomId, operationId: String): ForwardEventPairResult = withContext(dispatcher) {
        val preflight = runCatchingExceptions {
            if (protectedContentAdapter.enabled()) {
                val targetId = eventId.toEventOrTransactionId()
                val raw = rawItemsForDestruction(targetId).firstOrNull { item ->
                    val event = (item as? MatrixTimelineItem.Event)?.event ?: return@firstOrNull false
                    EventOrTransactionId.from(event.eventId, event.transactionId) == targetId
                } ?: error("Protected source event is unavailable.")
                check(!protectedContentAdapter.isBurnAfterRead(raw)) { "Burn-after-read messages cannot be forwarded." }
                check(currentEvent(eventId.value)?.event?.content != null) { "Protected source event is unavailable." }
                roomContentForwarder.validateTargetRoom(roomId).getOrThrow()
            } else {
                roomContentForwarder.validateForwardPair(inner, eventId, roomId).getOrThrow()
            }
        }
        if (preflight.isFailure) {
            ForwardEventPairResult.Failed(preflight.exceptionOrNull()?.message)
        } else {
            // From this point the existing SDK path may have accepted a send before returning an error.
            forwardEvent(eventId, listOf(roomId)).fold(
                onSuccess = { ForwardEventPairResult.Success },
                onFailure = {
                    if (it is ForwardTargetUnavailableException) ForwardEventPairResult.Failed(it.message)
                    else ForwardEventPairResult.Ambiguous(it.message)
                },
            )
        }
    }

    override suspend fun sendLocation(
        body: String,
        geoUri: String,
        description: String?,
        zoomLevel: Int?,
        assetType: AssetType?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions<Unit> {
            if (protectedContentAdapter.enabled()) {
                val lifecycle = if (burnAfterRead) protectedContentAdapter.burnAfterReadLifecycle() else ContentLifecycle()
                val prepared = protectedContentAdapter.protectLocation(
                    body,
                    geoUri,
                    description,
                    assetType,
                    lifecycle = lifecycle,
                )
                var matrixEnqueueAttempted = false
                try {
                    sendPreparedMessage(
                        prepared,
                        intentionalMentions = emptyList(),
                        repliedToEventId = inReplyToEventId.takeUnless { burnAfterRead },
                        allowServerBinding = !burnAfterRead,
                        beforeServerEnqueue = { matrixEnqueueAttempted = true },
                    )
                } catch (failure: Exception) {
                    // A queue call can accept the event before failing or being cancelled.
                    // Retain the original producer identity for sync/recovery and authority
                    // reconciliation; an exception is not proof that Matrix has no event.
                    if (!matrixEnqueueAttempted) destroyFailedPrepared(prepared, failure)
                    throw failure
                }
            } else {
                require(!burnAfterRead) { "Burn-after-read location requires protected messaging." }
                inner.sendLocation(
                    body = body,
                    geoUri = geoUri,
                    description = description,
                    zoomLevel = zoomLevel?.toUByte(),
                    assetType = assetType?.into(),
                    repliedToEventId = inReplyToEventId?.value,
                )
            }
        }
    }

    private suspend fun sendPreparedMessage(
        prepared: PreparedProtectedContent,
        intentionalMentions: List<IntentionalMention>,
        repliedToEventId: EventId?,
        allowServerBinding: Boolean = false,
        beforeServerEnqueue: () -> Unit = {},
    ) {
        if (serverEventBindingEnabled && allowServerBinding && repliedToEventId == null) {
            // The KMS prepare already commits SHA-256(contentId). Use that exact
            // identity for the durable Matrix transaction so the server can
            // independently bind the accepted event without a UI registration.
            protectedContentAdapter.bindPreparedTransaction(prepared, prepared.contentId.value)
            LifecycleStageTiming.measure("matrix_sdk_enqueue", prepared.contentId.value) {
                beforeServerEnqueue()
                queuedRawMessageEnqueuer(
                    ROOM_MESSAGE_EVENT_TYPE,
                    MessageEventContent.protectedJson(prepared.encodedEnvelope, intentionalMentions),
                    prepared.contentId.value,
                )
            }
        } else if (repliedToEventId == null) {
            protectedMessageContentFactory(prepared.encodedEnvelope, intentionalMentions).use { content ->
                sendAndMeasure(content)
            }
        } else {
            val startedAt = System.nanoTime()
            try {
                rawMessageSender(
                    ROOM_MESSAGE_EVENT_TYPE,
                    MessageEventContent.protectedReplyJson(
                        encodedEnvelope = prepared.encodedEnvelope,
                        intentionalMentions = intentionalMentions,
                        repliedToEventId = repliedToEventId.value,
                    ),
                )
            } finally {
                Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                    "stage=matrix_send_raw_reply duration_ms=%d",
                    (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
                )
            }
        }
    }

    private suspend fun sendAndMeasure(content: RoomMessageEventContentWithoutRelation) {
        val startedAt = System.nanoTime()
        try {
            LifecycleStageTiming.measure("matrix_sdk_enqueue") { inner.send(content) }
        } finally {
            Timber.tag(TIMELINE_PERFORMANCE_TAG).d(
                "stage=matrix_send duration_ms=%d",
                (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
            )
        }
    }

    private suspend fun destroyFailedPrepared(prepared: PreparedProtectedContent, sendFailure: Exception) {
        withContext(NonCancellable) {
            if (!protectedContentAdapter.destroyPrepared(prepared, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                throw IllegalStateException("KMS refused to clean a failed protected send.").also { cleanupFailure ->
                    cleanupFailure.addSuppressed(sendFailure)
                }
            }
        }
    }

    private fun stableContentId(transactionId: TransactionId): ContentId = ContentId(
        UUID.nameUUIDFromBytes("${joinedRoom.roomId.value}\u0000${transactionId.value}".encodeToByteArray()).toString(),
    )

    override suspend fun sendVoiceMessage(
        file: File,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> {
        if (protectedContentAdapter.enabled()) {
            return sendProtectedAttachment(
                plaintextFiles = listOf(file),
                mediaToProtect = listOf(file to ContentKind.VOICE_MESSAGE),
                burnAfterRead = burnAfterRead,
            ) { protectedFiles, _ ->
                val protectedFile = protectedFiles.single()
                inner.sendVoiceMessage(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = null,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        audioInfo = audioInfo.copy(size = protectedFile.length()).map(),
                        waveform = waveform,
                )
            }
        }
        return sendAttachment(listOf(file)) {
            inner.sendVoiceMessage(
                params = UploadParameters(
                    source = UploadSource.File(file.path),
                    // Maybe allow a caption in the future?
                    caption = null,
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = inReplyToEventId?.value,
                ),
                audioInfo = audioInfo.map(),
                waveform = waveform,
            )
        }
    }

    override suspend fun sendProtectedVoiceMessage(
        source: ProtectedMediaSource,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> {
        if (!protectedContentAdapter.enabled()) {
            return Result.failure(SecurityException("Protected voice sending requires protected-content encryption."))
        }
        return runCatchingExceptions {
            var preparedMedia: ProtectedMatrixMedia? = null
            try {
                val lifecycle = if (burnAfterRead) protectedContentAdapter.burnAfterReadLifecycle() else ContentLifecycle()
                preparedMedia = source.openInputStream().use { input ->
                    protectedContentAdapter.protectMedia(
                        source = input,
                        workingDirectory = source.encryptedWorkingDirectory,
                        kind = ContentKind.VOICE_MESSAGE,
                        lifecycle = lifecycle,
                    )
                }
                val protectedFile = requireNotNull(preparedMedia).file
                MediaUploadHandlerImpl(
                    filesToUpload = listOf(protectedFile),
                    protectedMediaTransportName = protectedFile.name,
                    sendAttachmentJoinHandle = inner.sendVoiceMessage(
                        params = UploadParameters(
                            source = UploadSource.File(protectedFile.path),
                            caption = null,
                            formattedCaption = null,
                            mentions = null,
                            inReplyTo = inReplyToEventId?.value,
                        ),
                        audioInfo = audioInfo.copy(size = protectedFile.length()).map(),
                        waveform = waveform,
                    ),
                    onFailure = { retirePreparedMedia(listOf(requireNotNull(preparedMedia))) },
                    onCancel = {
                        coroutineScope.launch {
                            withContext(NonCancellable) {
                                retirePreparedMedia(listOf(requireNotNull(preparedMedia)))
                            }
                        }
                    },
                )
            } catch (failure: Exception) {
                preparedMedia?.file?.safeDelete()
                preparedMedia?.let { retirePreparedMedia(listOf(it)) }
                throw failure
            }
        }
    }

    override suspend fun createPoll(
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (!protectedContentAdapter.enabled()) {
                inner.createPoll(question, answers, maxSelections.toUByte(), pollKind.toInner())
            } else {
                require(answers.size >= 2 && maxSelections in 1..answers.size)
                sendInteraction(
                    ContentKind.POLL,
                    ProtectedMatrixPayload(
                        payloadType = "POLL_START",
                        aggregateId = UUID.randomUUID().toString(),
                        question = question,
                        pollKind = pollKind.name,
                        maxSelections = maxSelections.toLong(),
                        pollAnswers = answers.map { answer -> ProtectedPollAnswer(UUID.randomUUID().toString(), answer) },
                    )
                )
            }
        }
    }

    override suspend fun editPoll(
        pollStartId: EventId,
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
        answerIds: List<String>?,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (!protectedContentAdapter.enabled()) {
                val editedContent = EditedContent.PollStart(PollData(question, answers, maxSelections.toUByte(), pollKind.toInner()))
                inner.edit(
                    eventOrTransactionId = RustEventOrTransactionId.EventId(pollStartId.value),
                    newContent = editedContent,
                )
            } else {
                val existing = currentEvent(pollStartId.value)?.event?.content as? PollContent
                    ?: error("Protected poll is unavailable.")
                require(answers.size >= 2 && maxSelections in 1..answers.size)
                val identities = answerIds ?: existing.answers.map { it.id }.also {
                    require(it.size == answers.size) { "Changing poll options requires stable answer identities." }
                }
                require(identities.size == answers.size && identities.all { it.isNotBlank() } && identities.distinct().size == identities.size)
                // Identities follow the edited rows, not their shifted positions. Existing votes
                // remain attached to surviving options; removed options are reduced separately.
                sendInteraction(
                    ContentKind.POLL_OPERATION,
                    ProtectedMatrixPayload(
                        payloadType = "OPERATION",
                        operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                        operation = "POLL_EDIT",
                        targetEventId = pollStartId.value,
                        aggregateId = pollStartId.value,
                        question = question,
                        pollKind = pollKind.name,
                        maxSelections = maxSelections.toLong(),
                        pollAnswers = answers.mapIndexed { index, answer -> ProtectedPollAnswer(identities[index], answer) },
                    )
                )
            }
        }
    }

    override suspend fun sendPollResponse(
        pollStartId: EventId,
        answers: List<String>
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (!protectedContentAdapter.enabled()) {
                inner.sendPollResponse(pollStartId.value, answers)
            } else {
                val target = currentEvent(pollStartId.value) ?: error("Protected poll is unavailable.")
                val poll = target.event.content as? PollContent ?: error("Protected poll is unavailable.")
                val audience = if (poll.kind == PollKind.Undisclosed) {
                    setOf(accountIdHash(joinedRoom.sessionId.value), accountIdHash(target.event.sender.value))
                } else {
                    null
                }
                sendInteraction(
                    ContentKind.POLL_OPERATION,
                    ProtectedMatrixPayload(
                        payloadType = "OPERATION",
                        operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                        operation = "POLL_RESPONSE",
                        targetEventId = pollStartId.value,
                        aggregateId = pollStartId.value,
                        selectedAnswerIds = answers,
                    ),
                    audience,
                )
            }
        }
    }

    override suspend fun endPoll(
        pollStartId: EventId,
        text: String
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            if (!protectedContentAdapter.enabled()) {
                inner.endPoll(pollStartId.value, text)
            } else {
                val poll = currentEvent(pollStartId.value)?.event?.content as? PollContent
                    ?: error("Protected poll is unavailable.")
                sendInteraction(
                    ContentKind.POLL_OPERATION,
                    ProtectedMatrixPayload(
                        payloadType = "OPERATION",
                        operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                        operation = "POLL_END",
                        targetEventId = pollStartId.value,
                        aggregateId = pollStartId.value,
                        body = text,
                        pollVotes = poll.votes.mapValues { (_, voters) -> voters.map { it.value } },
                    )
                )
            }
        }
    }

    override suspend fun startProtectedLiveLocation(
        aggregateId: String,
        durationMillis: Long,
        startedAtMillis: Long,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            require(durationMillis > 0)
            sendInteraction(
                ContentKind.LIVE_LOCATION,
                ProtectedMatrixPayload(
                    payloadType = "LIVE_LOCATION_START",
                    aggregateId = aggregateId,
                    timestamp = startedAtMillis,
                    timeoutMillis = durationMillis,
                    assetType = AssetType.SENDER.name,
                )
            )
        }
    }

    override suspend fun updateProtectedLiveLocation(
        aggregateId: String,
        geoUri: String,
        timestampMillis: Long,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            sendInteraction(
                ContentKind.LIVE_LOCATION_OPERATION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = protectedLiveLocationUpdateOperationId(
                        lifecycleOperationId = currentLifecycleOperationContext()?.operationId,
                        aggregateId = aggregateId,
                        timestampMillis = timestampMillis,
                    ),
                    operation = "LIVE_LOCATION_UPDATE",
                    targetEventId = aggregateId,
                    aggregateId = aggregateId,
                    geoUri = geoUri,
                    timestamp = timestampMillis,
                )
            )
        }
    }

    override suspend fun releaseLiveLocationProducer(): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions { protectedContentAdapter.releaseLiveLocationProducer() }
    }

    override suspend fun endProtectedLiveLocation(aggregateId: String): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            sendInteraction(
                ContentKind.LIVE_LOCATION_OPERATION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = protectedLiveLocationEndOperationId(
                        lifecycleOperationId = currentLifecycleOperationContext()?.operationId,
                        aggregateId = aggregateId,
                    ),
                    operation = "LIVE_LOCATION_END",
                    targetEventId = aggregateId,
                    aggregateId = aggregateId,
                )
            )
        }
    }

    override suspend fun activeProtectedLiveLocationAggregateId(): String? = withContext(dispatcher) {
        timelineItems.first()
            .filterIsInstance<MatrixTimelineItem.Event>()
            .asReversed()
            .firstNotNullOfOrNull { item ->
                val live = item.event.content as? LiveLocationContent ?: return@firstNotNullOfOrNull null
                live.protectedAggregateId?.takeIf { live.isLive && item.event.sender == joinedRoom.sessionId }
            }
    }

    override suspend fun createRelay(title: String, initialEntry: String?): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            require(title.isNotBlank())
            require(initialEntry == null || initialEntry.isNotBlank())
            sendInteraction(
                ContentKind.RELAY,
                ProtectedMatrixPayload(
                    payloadType = "RELAY_START",
                    aggregateId = UUID.randomUUID().toString(),
                    question = title.trim(),
                    relayEntry = initialEntry?.trim(),
                    relayEntryId = UUID.randomUUID().toString(),
                )
            )
        }
    }

    override suspend fun appendRelay(relayEventId: EventId, entry: String): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            require(entry.isNotBlank())
            val operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString()
            sendInteraction(
                ContentKind.RELAY_OPERATION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = operationId,
                    operation = "RELAY_APPEND",
                    targetEventId = relayEventId.value,
                    aggregateId = relayEventId.value,
                    relayEntry = entry.trim(),
                    relayEntryId = operationId,
                )
            )
        }
    }

    override suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> =
        withContext(dispatcher) {
            runCatchingExceptions {
                pendingTimelineSends.withSend {
                    require(protectedContentAdapter.enabled())
                    require(serverEventBindingEnabled) { "Durable relay editing requires the Next Matrix queue." }
                    require(itemId.length in 1..512 && itemId.none(Char::isWhitespace))
                    require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE)
                    require(entry.isNotBlank() && entry.trim().length <= 2_000)
                    val target = currentEvent(relayEventId.value) ?: throw SecurityException("Relay is unavailable.")
                    val message = target.event.content as? MessageContent ?: throw SecurityException("Relay is unavailable.")
                    require(message.isContentProtectionAuthorized && !message.isBurnAfterRead)
                    if (message.body.lineSequence().any { it.trim() == "（已结束）" }) {
                        throw io.element.android.security.dataprotection.api.RelayEditException(
                            io.element.android.security.dataprotection.api.RelayEditException.Reason.CLOSED)
                    }
                    val item = message.protectedRelayEntries?.singleOrNull { it.id == itemId }
                        ?: throw SecurityException("Relay item identity is unavailable.")
                    if (item.author != joinedRoom.sessionId || item.revision != expectedRevision || !item.isConfirmed) {
                        throw io.element.android.security.dataprotection.api.RelayEditException(
                            io.element.android.security.dataprotection.api.RelayEditException.Reason.CONFLICT)
                    }
                    val prepared = protectedContentAdapter.protectInteraction(
                        ContentKind.RELAY_OPERATION, relayEditPayload(relayEventId, itemId, expectedRevision, entry),
                        contentId = relayEditPreparedContentId(joinedRoom.roomId.value, checkNotNull(currentLifecycleOperationContext()).operationId),
                    )
                    // A failed/unknown enqueue keeps the original encrypted preparation;
                    // retries reuse its exact transaction and ciphertext, never retire a
                    // share that may already back an accepted Matrix event.
                    sendPreparedMessage(prepared, emptyList(), repliedToEventId = null, allowServerBinding = true)
                }
            }
        }

    override suspend fun verifyAcceptedRelayEditIntent(relayEventId: EventId, itemId: String, expectedRevision: Long,
        entry: String,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            check(protectedContentAdapter.verifyRelayEditIntent(relayEditPayload(relayEventId, itemId, expectedRevision, entry))) {
                "Original accepted relay edit intent is unavailable."
            }
        }
    }

    override suspend fun retainRelayEditRecovery(recovery: io.element.android.libraries.matrix.api.timeline.RelayEditRecovery): Result<Unit> =
        withContext(dispatcher) {
            runCatchingExceptions {
                require(protectedContentAdapter.enabled() && serverEventBindingEnabled)
                require(currentLifecycleOperationContext()?.operationId == recovery.operationId)
                protectedContentAdapter.retainRelayEditRecovery(relayEditPayload(EventId(recovery.targetEventId),
                    recovery.itemId, recovery.expectedRevision, recovery.entry))
            }
        }

    override suspend fun pendingRelayEditRecovery(relayEventId: EventId): Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?> =
        withContext(dispatcher) { runCatchingExceptions { protectedContentAdapter.pendingRelayEditRecovery(relayEventId.value) } }

    override suspend fun resolveRelayEditRecovery(relayEventId: EventId, operationId: String): Result<Unit> =
        withContext(dispatcher) { runCatchingExceptions { protectedContentAdapter.resolveRelayEditRecovery(relayEventId.value, operationId) } }

    private suspend fun relayEditPayload(relayEventId: EventId, itemId: String, expectedRevision: Long,
        entry: String,
    ): ProtectedMatrixPayload {
        require(itemId.length in 1..512 && itemId.none(Char::isWhitespace))
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE && entry.isNotBlank() && entry.trim().length <= 2_000)
        val operation = checkNotNull(currentLifecycleOperationContext()) { "Relay edit requires its original lifecycle." }
        require(operation.lifecycleId == FullAppLifecycleId.LC_15)
        return ProtectedMatrixPayload(
            payloadType = "OPERATION", operationId = operation.operationId, operation = "RELAY_EDIT",
            targetEventId = relayEventId.value, aggregateId = relayEventId.value,
            relayEntryId = itemId, relayExpectedRevision = expectedRevision, relayEntry = entry.trim(),
        )
    }

    override suspend fun closeRelay(relayEventId: EventId): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            sendInteraction(
                ContentKind.RELAY_OPERATION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                    operation = "RELAY_CLOSE",
                    targetEventId = relayEventId.value,
                    aggregateId = relayEventId.value,
                )
            )
        }
    }

    override suspend fun sendCallLifecycle(
        notificationEventId: EventId,
        state: CallLifecycleState,
        durationMillis: Long?,
    ): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            require(protectedContentAdapter.enabled())
            require(durationMillis == null || durationMillis >= 0L)
            sendInteraction(
                ContentKind.CALL_OPERATION,
                ProtectedMatrixPayload(
                    payloadType = "OPERATION",
                    operationId = currentLifecycleOperationContext()?.operationId ?: UUID.randomUUID().toString(),
                    operation = "CALL_STATE",
                    targetEventId = notificationEventId.value,
                    aggregateId = notificationEventId.value,
                    callLifecycleState = state.name,
                    durationMillis = durationMillis,
                )
            )
        }
    }

    private suspend fun sendInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>? = null,
    ) {
        if (kind in setOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION) &&
            protectedContentAdapter.verifyLiveLocationIntent(kind, payload)) return
        val prepared = protectedContentAdapter.protectInteraction(kind, payload, audienceAccountIdHashes)
        try {
            sendPreparedMessage(prepared, emptyList(), repliedToEventId = null,
                // Persist the original producer transaction before publishing live start/update/end too.
                // Each carrier keeps its own content ID and exact event binding.
                allowServerBinding = kind in setOf(ContentKind.POLL, ContentKind.RELAY,
                    ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION))
        } catch (failure: Exception) {
            destroyFailedPrepared(prepared, failure)
            throw failure
        }
    }

    private suspend fun currentEvent(eventId: String): MatrixTimelineItem.Event? =
        _timelineItems.first().filterIsInstance<MatrixTimelineItem.Event>().firstOrNull { it.eventId?.value == eventId }

    private fun sendAttachment(files: List<File>, handle: () -> SendAttachmentJoinHandle): Result<MediaUploadHandler> {
        return runCatchingExceptions {
            MediaUploadHandlerImpl(files, handle())
        }
    }

    private suspend fun requireLegacyContentOperationAllowed(operation: String) {
        if (protectedContentAdapter.enabled()) {
            throw SecurityException("$operation is disabled because it has no YiZhe protected-content envelope mapping.")
        }
    }

    override suspend fun mediaSubmissionFact(transportName: String): MediaSubmissionFact? =
        acceptedMediaSubmission(timelineDiffProcessor.rawMediaItems(transportName), transportName)

    private suspend fun sendProtectedAttachment(
        plaintextFiles: List<File>,
        mediaToProtect: List<Pair<File, ContentKind>>,
        caption: String? = null,
        formattedCaption: String? = null,
        burnAfterRead: Boolean = false,
        attachmentMetadata: ProtectedAttachmentMetadata? = null,
        mediaAlbum: ProtectedMediaAlbum? = null,
        handle: (List<File>, String?) -> SendAttachmentJoinHandle,
    ): Result<MediaUploadHandler> = runCatchingExceptions {
        var preparedMedia = emptyList<ProtectedMatrixMedia>()
        var preparedCaption: PreparedProtectedContent? = null
        val cancelOwner = currentCoroutineContext()[MediaSendCancellationContext]
        var enqueueAttempted = false
        var closeUnsentSubscription: (() -> Unit)? = null
        val roomRetention = joinedRoom.retainSendLifetime()
        val timelineRetention = try { pendingTimelineSends.retain() } catch (failure: Exception) { roomRetention.close(); throw failure }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val completionRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val nativeExited = java.util.concurrent.atomic.AtomicBoolean(false)
        val bindingCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        var retainedSubscription = false
        var deadlineJob: kotlinx.coroutines.Job? = null
        val closeNativeDependencies: () -> Unit = {
            try { timelineRetention.close() } finally { roomRetention.close() }
        }
        val releaseDependencies: (Boolean) -> Unit = { force ->
            // Successful commit must not cancel native result delivery or the original caption binding.
            // Deadline/session failure still releases ownership without extending access.
            if ((force || (completionRequested.get() && nativeExited.get() && bindingCompleted.get())) &&
                released.compareAndSet(false, true)) {
                deadlineJob?.cancel()
                if (force) {
                    // Deadline/session teardown cannot await a NonCancellable caption commit's mutex.
                    closeNativeDependencies()
                    coroutineScope.launch {
                        if (retainedSubscription) timelineItemsSubscriber.unsubscribeIfNeeded()
                    }
                } else {
                    coroutineScope.launch {
                        if (retainedSubscription) timelineItemsSubscriber.unsubscribeIfNeeded()
                    }.invokeOnCompletion { closeNativeDependencies() }
                }
            }
        }
        coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            // Session cancellation still tears down native ownership immediately.
            if (released.compareAndSet(false, true)) closeNativeDependencies()
        }
        try {
            val captionOperation = currentCoroutineContext()[MediaCaptionOperationContext]
            if (captionOperation != null) {
                val uploadOperation = requireNotNull(currentLifecycleOperationContext())
                require(uploadOperation.operationId == captionOperation.uploadOperationId &&
                    uploadOperation.lifecycleId in setOf(FullAppLifecycleId.LC_16, FullAppLifecycleId.LC_21))
            }
            val lifecycle = if (burnAfterRead) protectedContentAdapter.burnAfterReadLifecycle() else ContentLifecycle()
            mediaToProtect.forEach { (file, kind) ->
                currentCoroutineContext().ensureActive()
                cancelOwner?.beforeProtection()
                preparedMedia = preparedMedia + protectedContentAdapter.protectMedia(file, kind, lifecycle)
                cancelOwner?.afterProtection()
                currentCoroutineContext().ensureActive()
            }
            if (preparedMedia.size > 1) {
                val primary = preparedMedia.first()
                val burnPrefix = if (primary.file.name.startsWith(".yizhe-burn-")) "burn-" else ""
                val stickerPrefix = if (primary.file.name.startsWith(".yizhe-sticker-")) "sticker-" else ""
                val aggregateName = ".yizhe-$burnPrefix$stickerPrefix${preparedMedia.joinToString("~") { it.contentId.value }}.yzm"
                val aggregateFile = File(requireNotNull(primary.file.parentFile), aggregateName)
                check(primary.file.renameTo(aggregateFile)) { "Unable to bind protected media shares to one transport aggregate." }
                preparedMedia = listOf(primary.copy(file = aggregateFile)) + preparedMedia.drop(1)
            }
            val metadataForEnvelope = attachmentMetadata.takeUnless { burnAfterRead }
            if (caption != null || formattedCaption != null || metadataForEnvelope != null || mediaAlbum != null) {
                cancelOwner?.beforeProtection()
                if (captionOperation == null) {
                preparedCaption = protectedContentAdapter.protectCaption(
                    caption = caption,
                    formattedCaption = formattedCaption,
                    lifecycle = lifecycle,
                    attachmentMetadata = metadataForEnvelope,
                    mediaAlbum = mediaAlbum,
                )
                } else {
                    preparedCaption = withContext(LifecycleOperationContext(captionOperation.captionOperationId, FullAppLifecycleId.LC_15)) {
                        protectedContentAdapter.protectCaption(
                            caption, formattedCaption, lifecycle, metadataForEnvelope, mediaAlbum,
                        )
                    }
                }
            }
            cancelOwner?.afterProtection()
            val preparedFiles = preparedMedia.map(ProtectedMatrixMedia::file)
            val captionCarrier = preparedCaption?.let { PROTECTED_CAPTION_PREFIX + it.encodedEnvelope }
            currentCoroutineContext().ensureActive()
            val submission = currentCoroutineContext()[MediaSubmissionContext]
            val transportName = preparedFiles.first().name
            submission?.beforeSdk?.invoke(transportName)
            currentCoroutineContext().ensureActive()
            cancelOwner?.beforeEnqueue()
            val deadline = submission?.originalDeadline?.invoke() ?: (System.currentTimeMillis() + sendQueueReceiptTimeoutMillis)
            check(deadline > System.currentTimeMillis()) { "Original media completion deadline expired." }
            // Preserve the exact LOCAL association even when the last viewport unsubscribes.
            timelineItemsSubscriber.subscribeIfNeeded()
            retainedSubscription = true
            val producerOperation = currentLifecycleOperationContext()
            val observer = ProtectedMediaCommitObserver(transportName, coroutineScope,
                retain = { fact -> submission?.observed?.invoke(fact.copy(uploadOperationId = producerOperation?.operationId)) },
                accepted = { fact ->
                    submission?.accepted?.invoke(fact.copy(uploadOperationId = producerOperation?.operationId))
                    timelineDiffProcessor.completePreparedAttachment(transportName)
                },
            )
            // Subscription readiness precedes native dispatch; retain SentEvent even if the
            // raw/local-to-remote UI projection is replaced before native join returns.
            val subscription = sendQueueUpdateSubscriber(observer)
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            observer.closeSubscription = {
                if (closed.compareAndSet(false, true)) {
                    try { subscription.cancel() } finally {
                        try { subscription.destroy() } finally {
                            completionRequested.set(true)
                            releaseDependencies(false)
                        }
                    }
                }
            }
            closeUnsentSubscription = observer.closeSubscription
            deadlineJob = coroutineScope.launch {
                kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
                observer.closeSubscription() // No renewed deadline, fabricated cleanup, or receipt.
                releaseDependencies(true)
            }
            coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { observer.closeSubscription() }
            timelineDiffProcessor.registerPreparedAttachment(transportName, captionCarrier) { transaction ->
                // Actual original media identity is independent of caption journal success.
                observer.bindOriginalTransaction(transaction)
                try {
                    preparedCaption?.let { prepared ->
                        val operation = captionOperation?.let {
                            LifecycleOperationContext(it.captionOperationId, FullAppLifecycleId.LC_15)
                        } ?: requireNotNull(producerOperation)
                        withContext(operation) { protectedContentAdapter.bindPreparedTransaction(prepared, transaction) }
                    }
                } finally {
                    // Caption journal failure remains independent; finish its original attempt first.
                    bindingCompleted.set(true)
                    releaseDependencies(false)
                }
            }
            enqueueAttempted = true
            val nativeHandle = handle(preparedFiles, captionCarrier)
            val upload = CancellableProtectedMediaUpload(
                protectedMediaTransportName = transportName,
                scope = coroutineScope,
                join = { nativeHandle.join() },
                stopPreparation = { nativeHandle.cancel() },
                observe = { timelineDiffProcessor.rawMediaItems(transportName) },
                clearFiles = {
                    (plaintextFiles + preparedFiles).forEach { file ->
                        file.safeDelete()
                        check(!file.exists()) { "Temporary media cleanup is pending." }
                    }
                },
                timeoutMillis = sendQueueReceiptTimeoutMillis,
                observeCommit = { observer.fact() },
                cleanupCompleted = { observer.localCleanupCompleted(it) },
                nativeExited = {
                    nativeExited.set(true)
                    releaseDependencies(false)
                },
                accepted = { fact ->
                    submission?.accepted?.invoke(fact.copy(uploadOperationId = producerOperation?.operationId))
                    timelineDiffProcessor.completePreparedAttachment(transportName)
                    observer.closeSubscription()
                },
            )
            cancelOwner?.attach(upload)
            upload
        } catch (failure: Exception) {
            if (!enqueueAttempted) {
                closeUnsentSubscription?.invoke()
                releaseDependencies(true)
                try {
                    withContext(NonCancellable) {
                        (plaintextFiles + preparedMedia.map(ProtectedMatrixMedia::file)).forEach { file ->
                            file.safeDelete()
                            check(!file.exists()) { "Temporary media cleanup is pending." }
                        }
                        // A normal failed KMS store keeps BUG027's unknown source/key lineage.
                        // An explicit pre-enqueue cancel can retire only fully returned shares.
                        if ((cancelOwner?.requested == true && !cancelOwner.protectionInFlight) ||
                            currentCoroutineContext()[MediaSubmissionContext]?.keyStoreStarted != true) {
                        preparedMedia.forEach { media ->
                            check(protectedContentAdapter.destroyMedia(media, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                                "Prepared media key cleanup acknowledgement is pending."
                            }
                        }
                        preparedCaption?.let {
                            check(protectedContentAdapter.destroyPrepared(it, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                                "Prepared caption cleanup acknowledgement is pending."
                            }
                        }
                        }
                    }
                } catch (cleanupFailure: Exception) {
                    cancelOwner?.cleanupFailure = cleanupFailure
                    failure.addSuppressed(cleanupFailure)
                }
            }
            // Once native enqueue was attempted, an exception is not proof of non-commit.
            throw failure
        }
    }

    private suspend fun retirePreparedMedia(preparedMedia: List<ProtectedMatrixMedia>) {
        preparedMedia.forEach { media ->
            runCatchingExceptions {
                protectedContentAdapter.destroyMedia(media, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)
            }
        }
    }

    override suspend fun loadReplyDetails(eventId: EventId): InReplyTo = withContext(dispatcher) {
        val timelineItem = _timelineItems.first().firstOrNull { timelineItem ->
            timelineItem is MatrixTimelineItem.Event && timelineItem.eventId == eventId
        } as? MatrixTimelineItem.Event

        if (timelineItem != null) {
            InReplyTo.Ready(
                eventId = eventId,
                content = timelineItem.event.content,
                senderId = timelineItem.event.sender,
                senderProfile = timelineItem.event.senderProfile,
            )
        } else {
            inner.loadReplyDetails(eventId.value).use(inReplyToMapper::map)
        }
    }

    override suspend fun pinEvent(eventId: EventId): Result<Boolean> = withContext(dispatcher) {
        runCatchingExceptions {
            requireNotBurnAfterRead(eventId.toEventOrTransactionId(), "pinned")
            inner.pinEvent(eventId = eventId.value)
        }
    }

    override suspend fun unpinEvent(eventId: EventId): Result<Boolean> = withContext(dispatcher) {
        runCatchingExceptions {
            requireNotBurnAfterRead(eventId.toEventOrTransactionId(), "unpinned")
            inner.unpinEvent(eventId = eventId.value)
        }
    }

    override suspend fun getLatestEventId(): Result<EventId?> = withContext(dispatcher) {
        runCatchingExceptions {
            inner.latestEventId()?.let(::EventId)
        }
    }

    private suspend fun fetchDetailsForEvent(eventId: EventId): Result<Unit> = withContext(dispatcher) {
        runCatchingExceptions {
            inner.fetchDetailsForEvent(eventId.value)
        }
    }
}

internal fun protectedLiveLocationUpdateOperationId(
    lifecycleOperationId: String?,
    aggregateId: String,
    timestampMillis: Long,
): String = buildString {
    append(lifecycleOperationId ?: UUID.randomUUID().toString())
    append(":live-location-update:")
    append(aggregateId)
    append(':')
    append(timestampMillis)
}

internal fun protectedLiveLocationEndOperationId(
    lifecycleOperationId: String?,
    aggregateId: String,
): String = buildString {
    append(lifecycleOperationId ?: UUID.randomUUID().toString())
    append(":live-location-end:")
    append(aggregateId)
}

private fun EventOrTransactionId.diagnosticIdentity(): String = when (this) {
    is EventOrTransactionId.Event -> id.value
    is EventOrTransactionId.Transaction -> id.value
}

private fun logRedactionStage(stage: String, identity: String) {
    Timber.tag(REDACTION_LATENCY_TAG).d(
        "stage=%s identity=%s epoch_ms=%d monotonic_ns=%d",
        stage,
        identity,
        System.currentTimeMillis(),
        System.nanoTime(),
    )
}

private const val REDACTION_LATENCY_TAG = "YiZheRedactionLatency"

private const val TIMELINE_PERFORMANCE_TAG = "YiZheTimelinePerf"
private const val NANOS_PER_MILLISECOND = 1_000_000L
