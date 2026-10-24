/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.test.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.ProtectedMediaSource
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.ForwardEventPairResult
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.simulateLongTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class FakeTimeline(
    private val name: String = "FakeTimeline",
    override val timelineItems: Flow<List<MatrixTimelineItem>> = MutableStateFlow(emptyList()),
    override val backwardPaginationStatus: MutableStateFlow<Timeline.PaginationStatus> = MutableStateFlow(
        Timeline.PaginationStatus(
            isPaginating = false,
            hasMoreToLoad = true
        )
    ),
    override val forwardPaginationStatus: MutableStateFlow<Timeline.PaginationStatus> = MutableStateFlow(
        Timeline.PaginationStatus(
            isPaginating = false,
            hasMoreToLoad = false
        )
    ),
    override val membershipChangeEventReceived: Flow<Unit> = MutableSharedFlow(),
    override val onSyncedEventReceived: Flow<Unit> = MutableSharedFlow(),
    private val cancelSendResult: (TransactionId) -> Result<Unit> = { lambdaError() },
    override val mode: Timeline.Mode = Timeline.Mode.Live,
    private val markAsReadResult: (ReceiptType) -> Result<Unit> = { lambdaError() },
    private val getLatestEventIdResult: () -> Result<EventId?> = { lambdaError() },
    var sendReadReceiptLambda: (
        eventId: EventId,
        receiptType: ReceiptType,
    ) -> Result<Unit> = { _, _ ->
        lambdaError()
    }
) : Timeline {
    val viewportUpdates = mutableListOf<List<UniqueId>>()
    val initialRenderPermissions = mutableListOf<Boolean>()

    override fun updateViewport(visibleItemIds: List<UniqueId>, allowInitialRender: Boolean) {
        viewportUpdates += visibleItemIds.toList()
        initialRenderPermissions += allowInitialRender
    }

    var sendMessageLambda: (
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType,
        asPlainText: Boolean,
    ) -> Result<Unit> = { _, _, _, _, _ ->
        lambdaError()
    }

    override suspend fun cancelSend(transactionId: TransactionId): Result<Unit> = simulateLongTask {
        cancelSendResult(transactionId)
    }

    override suspend fun sendMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType,
        asPlainText: Boolean,
    ): Result<Unit> = simulateLongTask {
        sendMessageLambda(body, htmlBody, intentionalMentions, msgType, asPlainText)
    }

    var openBurnAfterReadLambda: suspend (EventId) -> Result<Unit> = { lambdaError() }

    var sendCallLifecycleLambda: suspend (EventId, CallLifecycleState, Long?) -> Result<Unit> = { _, _, _ -> lambdaError() }

    override suspend fun sendCallLifecycle(
        notificationEventId: EventId,
        state: CallLifecycleState,
        durationMillis: Long?,
    ): Result<Unit> = sendCallLifecycleLambda(notificationEventId, state, durationMillis)

    override suspend fun openBurnAfterRead(eventId: EventId): Result<Unit> =
        openBurnAfterReadLambda(eventId)

    var endBurnAfterReadLambda: (EventId, Timeline.BurnAfterReadEndReason) -> Result<Unit> = { _, _ -> lambdaError() }

    override suspend fun endBurnAfterRead(
        eventId: EventId,
        reason: Timeline.BurnAfterReadEndReason,
    ): Result<Unit> = endBurnAfterReadLambda(eventId, reason)

    var redactEventLambda: (eventOrTransactionId: EventOrTransactionId, reason: String?) -> Result<Unit> = { _, _ ->
        lambdaError()
    }

    override suspend fun redactEvent(
        eventOrTransactionId: EventOrTransactionId,
        reason: String?
    ): Result<Unit> = redactEventLambda(eventOrTransactionId, reason)

    var editMessageLambda: (
        eventOrTransactionId: EventOrTransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
    ) -> Result<Unit> = { _, _, _, _ ->
        lambdaError()
    }

    override suspend fun editMessage(
        eventOrTransactionId: EventOrTransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
    ): Result<Unit> = editMessageLambda(
        eventOrTransactionId,
        body,
        htmlBody,
        intentionalMentions
    )

    var editCaptionLambda: (
        eventOrTransactionId: EventOrTransactionId,
        caption: String?,
        formattedCaption: String?,
    ) -> Result<Unit> = { _, _, _ ->
        lambdaError()
    }

    override suspend fun editCaption(
        eventOrTransactionId: EventOrTransactionId,
        caption: String?,
        formattedCaption: String?,
    ): Result<Unit> = editCaptionLambda(
        eventOrTransactionId,
        caption,
        formattedCaption,
    )

    var replyMessageLambda: (
        inReplyToEventId: EventId?,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        fromNotification: Boolean,
        msgType: MsgType,
    ) -> Result<Unit> = { _, _, _, _, _, _ ->
        lambdaError()
    }

    override suspend fun replyMessage(
        repliedToEventId: EventId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        fromNotification: Boolean,
        msgType: MsgType,
    ): Result<Unit> = replyMessageLambda(
        repliedToEventId,
        body,
        htmlBody,
        intentionalMentions,
        fromNotification,
        msgType,
    )

    var sendImageLambda: (
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
        body: String?,
        formattedBody: String?,
        inReplyToEventId: EventId?,
    ) -> Result<MediaUploadHandler> = { _, _, _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }
    var lastSendImageBurnAfterRead: Boolean? = null
    var lastSendImageMediaAlbum: ProtectedMediaAlbum? = null

    override suspend fun sendImage(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
    ): Result<MediaUploadHandler> = simulateLongTask {
        lastSendImageBurnAfterRead = burnAfterRead
        lastSendImageMediaAlbum = mediaAlbum
        sendImageLambda(
            file,
            thumbnailFile,
            imageInfo,
            caption,
            formattedCaption,
            inReplyToEventId,
        )
    }

    var sendVideoLambda: (
        file: File,
        thumbnailFile: File?,
        videoInfo: VideoInfo,
        body: String?,
        formattedBody: String?,
        inReplyToEventId: EventId?,
    ) -> Result<MediaUploadHandler> = { _, _, _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }
    var lastSendVideoBurnAfterRead: Boolean? = null
    var lastSendVideoMediaAlbum: ProtectedMediaAlbum? = null

    override suspend fun sendVideo(
        file: File,
        thumbnailFile: File?,
        videoInfo: VideoInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
    ): Result<MediaUploadHandler> = simulateLongTask {
        lastSendVideoBurnAfterRead = burnAfterRead
        lastSendVideoMediaAlbum = mediaAlbum
        sendVideoLambda(
            file,
            thumbnailFile,
            videoInfo,
            caption,
            formattedCaption,
            inReplyToEventId,
        )
    }

    var sendAudioLambda: (
        file: File,
        audioInfo: AudioInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ) -> Result<MediaUploadHandler> = { _, _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }

    override suspend fun sendAudio(
        file: File,
        audioInfo: AudioInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> = simulateLongTask {
        sendAudioLambda(
            file,
            audioInfo,
            caption,
            formattedCaption,
            inReplyToEventId,
        )
    }

    var sendFileLambda: (
        file: File,
        fileInfo: FileInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ) -> Result<MediaUploadHandler> = { _, _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }

    override suspend fun sendFile(
        file: File,
        fileInfo: FileInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> = simulateLongTask {
        sendFileLambda(
            file,
            fileInfo,
            caption,
            formattedCaption,
            inReplyToEventId,
        )
    }

    var sendVoiceMessageLambda: (
        file: File,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
    ) -> Result<MediaUploadHandler> = { _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }

    override suspend fun sendVoiceMessage(
        file: File,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> = simulateLongTask {
        sendVoiceMessageLambda(
            file,
            audioInfo,
            waveform,
            inReplyToEventId,
        )
    }

    var sendProtectedVoiceMessageLambda: (
        source: ProtectedMediaSource,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ) -> Result<MediaUploadHandler> = { _, _, _, _, _ ->
        Result.success(FakeMediaUploadHandler())
    }

    override suspend fun sendProtectedVoiceMessage(
        source: ProtectedMediaSource,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<MediaUploadHandler> = simulateLongTask {
        sendProtectedVoiceMessageLambda(source, audioInfo, waveform, inReplyToEventId, burnAfterRead)
    }

    var sendLocationLambda: (
        body: String,
        geoUri: String,
        description: String?,
        zoomLevel: Int?,
        assetType: AssetType?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ) -> Result<Unit> = { _, _, _, _, _, _, _ ->
        lambdaError()
    }

    override suspend fun sendLocation(
        body: String,
        geoUri: String,
        description: String?,
        zoomLevel: Int?,
        assetType: AssetType?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
    ): Result<Unit> = simulateLongTask {
        sendLocationLambda(
            body,
            geoUri,
            description,
            zoomLevel,
            assetType,
            inReplyToEventId,
            burnAfterRead,
        )
    }

    var toggleReactionLambda: (emoji: String, eventOrTransactionId: EventOrTransactionId) -> Result<Boolean> = { _, _ -> lambdaError() }

    override suspend fun toggleReaction(emoji: String, eventOrTransactionId: EventOrTransactionId): Result<Boolean> = simulateLongTask {
        toggleReactionLambda(
            emoji,
            eventOrTransactionId,
        )
    }

    var forwardEventLambda: (eventId: EventId, roomIds: List<RoomId>) -> Result<Unit> = { _, _ -> lambdaError() }

    override suspend fun forwardEvent(eventId: EventId, roomIds: List<RoomId>): Result<Unit> = simulateLongTask {
        forwardEventLambda(eventId, roomIds)
    }

    var forwardEventPairLambda: (EventId, RoomId, String) -> ForwardEventPairResult = { _, _, _ -> lambdaError() }
    var forwardEventPairSuspendingLambda: (suspend (EventId, RoomId, String) -> ForwardEventPairResult)? = null

    override suspend fun forwardEventPair(eventId: EventId, roomId: RoomId, operationId: String): ForwardEventPairResult {
        simulateLongTask { Unit }
        return forwardEventPairSuspendingLambda?.invoke(eventId, roomId, operationId)
            ?: forwardEventPairLambda(eventId, roomId, operationId)
    }

    var createPollLambda: (
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ) -> Result<Unit> = { _, _, _, _ ->
        lambdaError()
    }

    override suspend fun createPoll(question: String, answers: List<String>, maxSelections: Int, pollKind: PollKind): Result<Unit> = simulateLongTask {
        createPollLambda(
            question,
            answers,
            maxSelections,
            pollKind,
        )
    }

    var editPollLambda: (
        pollStartId: EventId,
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ) -> Result<Unit> = { _, _, _, _, _ ->
        lambdaError()
    }

    override suspend fun editPoll(
        pollStartId: EventId,
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
        answerIds: List<String>?,
    ): Result<Unit> = simulateLongTask {
        editPollLambda(
            pollStartId,
            question,
            answers,
            maxSelections,
            pollKind,
        )
    }

    var sendPollResponseLambda: (
        pollStartId: EventId,
        answers: List<String>,
    ) -> Result<Unit> = { _, _ ->
        lambdaError()
    }

    override suspend fun sendPollResponse(
        pollStartId: EventId,
        answers: List<String>,
    ): Result<Unit> = simulateLongTask {
        sendPollResponseLambda(
            pollStartId,
            answers,
        )
    }

    var endPollLambda: (
        pollStartId: EventId,
        text: String,
    ) -> Result<Unit> = { _, _ ->
        lambdaError()
    }

    override suspend fun endPoll(
        pollStartId: EventId,
        text: String,
    ): Result<Unit> = simulateLongTask {
        endPollLambda(
            pollStartId,
            text,
        )
    }

    override suspend fun sendReadReceipt(
        eventId: EventId,
        receiptType: ReceiptType,
    ): Result<Unit> = sendReadReceiptLambda(eventId, receiptType)

    override suspend fun markAsRead(receiptType: ReceiptType): Result<Unit> {
        return markAsReadResult(receiptType)
    }

    var paginateLambda: (direction: Timeline.PaginationDirection) -> Result<Boolean> = {
        Result.success(false)
    }

    override suspend fun paginate(direction: Timeline.PaginationDirection): Result<Boolean> = paginateLambda(direction)

    var loadReplyDetailsLambda: (eventId: EventId) -> InReplyTo = {
        InReplyTo.NotLoaded(it)
    }

    override suspend fun loadReplyDetails(eventId: EventId) = loadReplyDetailsLambda(eventId)

    var pinEventLambda: (eventId: EventId) -> Result<Boolean> = { lambdaError() }
    override suspend fun pinEvent(eventId: EventId): Result<Boolean> {
        return pinEventLambda(eventId)
    }

    var unpinEventLambda: (eventId: EventId) -> Result<Boolean> = { lambdaError() }
    override suspend fun unpinEvent(eventId: EventId): Result<Boolean> {
        return unpinEventLambda(eventId)
    }

    override suspend fun getLatestEventId(): Result<EventId?> {
        return getLatestEventIdResult()
    }

    private val pendingRelayEdits = mutableMapOf<String, io.element.android.libraries.matrix.api.timeline.RelayEditRecovery>()

    override suspend fun retainRelayEditRecovery(recovery: io.element.android.libraries.matrix.api.timeline.RelayEditRecovery): Result<Unit> =
        runCatching {
            require(pendingRelayEdits[recovery.targetEventId].let { it == null || it == recovery })
            pendingRelayEdits[recovery.targetEventId] = recovery
        }

    override suspend fun pendingRelayEditRecovery(eventId: EventId): Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?> =
        Result.success(pendingRelayEdits[eventId.value])

    override suspend fun resolveRelayEditRecovery(eventId: EventId, operationId: String): Result<Unit> = runCatching {
        require(pendingRelayEdits[eventId.value].let { it == null || it.operationId == operationId })
        pendingRelayEdits.remove(eventId.value)
        Unit
    }

    var closeCounter = 0
        private set

    override fun close() {
        closeCounter++
    }

    override fun toString() = "FakeTimeline: $name"
}
