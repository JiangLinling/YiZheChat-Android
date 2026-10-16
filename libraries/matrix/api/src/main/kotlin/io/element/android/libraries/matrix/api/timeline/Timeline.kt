/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.timeline

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
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
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import java.io.File

interface Timeline : AutoCloseable {
    /** Positive Matrix fact only. Missing local projection is never a remote absence proof. */
    suspend fun mediaSubmissionFact(transportName: String): io.element.android.libraries.matrix.api.media.MediaSubmissionFact? = null

    enum class BurnAfterReadEndReason {
        VIEW_CLOSED,
        WINDOW_EXPIRED,
        PLAYBACK_ENDED,
        APP_LOCKED,
    }
    data class PaginationStatus(
        val isPaginating: Boolean,
        val hasMoreToLoad: Boolean,
        val lastRequestFailed: Boolean = false,
    ) {
        val canPaginate: Boolean = !isPaginating && hasMoreToLoad
    }

    enum class PaginationDirection {
        BACKWARDS,
        FORWARDS
    }

    @Parcelize
    @Immutable
    sealed interface Mode : Parcelable {
        data object Live : Mode
        data class FocusedOnEvent(val eventId: EventId) : Mode
        data object PinnedEvents : Mode
        data object Media : Mode
        data class Thread(val threadRootId: ThreadId) : Mode
    }

    val mode: Mode
    val membershipChangeEventReceived: Flow<Unit>
    val onSyncedEventReceived: Flow<Unit>
    suspend fun sendReadReceipt(eventId: EventId, receiptType: ReceiptType): Result<Unit>
    suspend fun markAsRead(receiptType: ReceiptType): Result<Unit>
    suspend fun paginate(direction: PaginationDirection): Result<Boolean>

    val backwardPaginationStatus: StateFlow<PaginationStatus>
    val forwardPaginationStatus: StateFlow<PaginationStatus>

    val timelineItems: Flow<List<MatrixTimelineItem>>

    /**
     * Reports the Matrix item identities currently measured in the Compose viewport, in viewport
     * order. Implementations may use this only to prioritize derived timeline work; it must not
     * change reliable-security or committed-send ownership.
     */
    fun updateViewport(visibleItemIds: List<UniqueId>) = Unit

    suspend fun sendMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType = MsgType.MSG_TYPE_TEXT,
        asPlainText: Boolean = false,
    ): Result<Unit>

    /** Sends a one-time-view message. First open is online-only; only recipients get a view grant, never the sender. */
    suspend fun sendBurnAfterReadMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType = MsgType.MSG_TYPE_TEXT,
        asPlainText: Boolean = false,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Burn-after-read is unavailable."))

    suspend fun openBurnAfterRead(eventId: EventId): Result<Unit> =
        Result.failure(UnsupportedOperationException("Burn-after-read is unavailable."))

    suspend fun endBurnAfterRead(
        eventId: EventId,
        reason: BurnAfterReadEndReason = BurnAfterReadEndReason.VIEW_CLOSED,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Burn-after-read is unavailable."))

    /**
     * Sends or retries one logical message with a stable transaction id. Protected implementations
     * must reuse the original envelope and KMS content id for every attempt of this transaction.
     */
    suspend fun sendMessageTransaction(
        transactionId: TransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        repliedToEventId: EventId? = null,
        msgType: MsgType = MsgType.MSG_TYPE_TEXT,
        asPlainText: Boolean = false,
    ): Result<Unit> = if (repliedToEventId == null) {
        sendMessage(body, htmlBody, intentionalMentions, msgType, asPlainText)
    } else {
        replyMessage(repliedToEventId, body, htmlBody, intentionalMentions, msgType = msgType)
    }

    /** Destroys a prepared-but-unaccepted protected transaction after the user discards it. */
    suspend fun discardMessageTransaction(transactionId: TransactionId): Result<Unit> = Result.success(Unit)

    suspend fun editMessage(
        eventOrTransactionId: EventOrTransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
    ): Result<Unit>

    suspend fun editCaption(
        eventOrTransactionId: EventOrTransactionId,
        caption: String?,
        formattedCaption: String?,
    ): Result<Unit>

    suspend fun replyMessage(
        repliedToEventId: EventId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        fromNotification: Boolean = false,
        msgType: MsgType = MsgType.MSG_TYPE_TEXT,
    ): Result<Unit>

    suspend fun sendImage(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
        mediaAlbum: ProtectedMediaAlbum? = null,
    ): Result<MediaUploadHandler>

    suspend fun sendSticker(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
    ): Result<MediaUploadHandler> = Result.failure(UnsupportedOperationException("Sticker sending is unavailable."))

    suspend fun sendVideo(
        file: File,
        thumbnailFile: File?,
        videoInfo: VideoInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
        mediaAlbum: ProtectedMediaAlbum? = null,
    ): Result<MediaUploadHandler>

    suspend fun sendAudio(
        file: File,
        audioInfo: AudioInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
    ): Result<MediaUploadHandler>

    suspend fun sendFile(
        file: File,
        fileInfo: FileInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
    ): Result<MediaUploadHandler>

    /**
     * Share a location message in the room.
     *
     * @param body A human readable textual representation of the location.
     * @param geoUri A geo URI (RFC 5870) representing the location e.g. `geo:51.5008,0.1247;u=35`.
     *  Respectively: latitude, longitude, and (optional) uncertainty.
     * @param description Optional description of the location to display to the user.
     * @param zoomLevel Optional zoom level to display the map at.
     * @param assetType Optional type of the location asset.
     *  Set to SENDER if sharing own location. Set to PIN if sharing any location.
     * @param inReplyToEventId Optional [EventId] for the event this message should reply to.
     */
    suspend fun sendLocation(
        body: String,
        geoUri: String,
        description: String? = null,
        zoomLevel: Int? = null,
        assetType: AssetType? = null,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
    ): Result<Unit>

    suspend fun sendVoiceMessage(
        file: File,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
    ): Result<MediaUploadHandler>

    /**
     * Sends an authorization-bound voice source through protected-content encryption without a
     * plaintext filesystem staging file. Implementations must fail closed when protection is off.
     */
    suspend fun sendProtectedVoiceMessage(
        source: ProtectedMediaSource,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean = false,
    ): Result<MediaUploadHandler> = Result.failure(
        UnsupportedOperationException("Protected streaming voice send is unavailable.")
    )

    suspend fun redactEvent(eventOrTransactionId: EventOrTransactionId, reason: String?): Result<Unit>

    suspend fun toggleReaction(emoji: String, eventOrTransactionId: EventOrTransactionId): Result<Boolean>

    suspend fun forwardEvent(eventId: EventId, roomIds: List<RoomId>): Result<Unit>

    /**
     * Forwards one source/target pair. [operationId] is an opaque local correlation identity; it is
     * not claimed to be a Matrix transaction id.
     */
    suspend fun forwardEventPair(eventId: EventId, roomId: RoomId, operationId: String): ForwardEventPairResult =
        forwardEvent(eventId, listOf(roomId)).fold(
            onSuccess = { ForwardEventPairResult.Success },
            // The legacy API cannot prove whether a failed/timeout send was accepted remotely.
            onFailure = { ForwardEventPairResult.Ambiguous(it.message) },
        )

    suspend fun cancelSend(transactionId: TransactionId): Result<Unit> =
        redactEvent(transactionId.toEventOrTransactionId(), reason = null)

    /**
     * Create a poll in the room.
     *
     * @param question The question to ask.
     * @param answers The list of answers.
     * @param maxSelections The maximum number of answers that can be selected.
     * @param pollKind The kind of poll to create.
     */
    suspend fun createPoll(
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ): Result<Unit>

    /**
     * Edit a poll in the room.
     *
     * @param pollStartId The event ID of the poll start event.
     * @param question The question to ask.
     * @param answers The list of answers.
     * @param maxSelections The maximum number of answers that can be selected.
     * @param pollKind The kind of poll to create.
     */
    suspend fun editPoll(
        pollStartId: EventId,
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
        answerIds: List<String>? = null,
    ): Result<Unit>

    /**
     * Send a response to a poll.
     *
     * @param pollStartId The event ID of the poll start event.
     * @param answers The list of answer ids to send.
     */
    suspend fun sendPollResponse(pollStartId: EventId, answers: List<String>): Result<Unit>

    /**
     * Ends a poll in the room.
     *
     * @param pollStartId The event ID of the poll start event.
     * @param text Fallback text of the poll end event.
     */
    suspend fun endPoll(pollStartId: EventId, text: String): Result<Unit>

    suspend fun startProtectedLiveLocation(aggregateId: String, durationMillis: Long, startedAtMillis: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected live location is unavailable."))

    suspend fun updateProtectedLiveLocation(aggregateId: String, geoUri: String, timestampMillis: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected live location is unavailable."))

    suspend fun releaseLiveLocationProducer(): Result<Unit> = Result.failure(UnsupportedOperationException("Live producer cleanup unavailable"))

    suspend fun endProtectedLiveLocation(aggregateId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected live location is unavailable."))

    /** Recovers this account's active protected live-location aggregate after process restart. */
    suspend fun activeProtectedLiveLocationAggregateId(): String? = null

    suspend fun createRelay(title: String, initialEntry: String? = null): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected relay is unavailable."))

    suspend fun appendRelay(relayEventId: EventId, entry: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected relay is unavailable."))

    suspend fun closeRelay(relayEventId: EventId): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected relay is unavailable."))

    suspend fun verifyAcceptedRelayEditIntent(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Original relay edit intent is unavailable."))

    suspend fun retainRelayEditRecovery(recovery: RelayEditRecovery): Result<Unit> =
        Result.failure(UnsupportedOperationException("Durable relay recovery is unavailable."))

    suspend fun pendingRelayEditRecovery(relayEventId: EventId): Result<RelayEditRecovery?> = Result.success(null)

    suspend fun resolveRelayEditRecovery(relayEventId: EventId, operationId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Durable relay recovery is unavailable."))

    suspend fun editRelayEntry(relayEventId: EventId, itemId: String, expectedRevision: Long, entry: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Protected relay editing is unavailable."))

    /** Persists a DM call transition as an authenticated protected operation on its call notification. */
    suspend fun sendCallLifecycle(
        notificationEventId: EventId,
        state: CallLifecycleState,
        durationMillis: Long? = null,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Protected call lifecycle is unavailable."))

    suspend fun loadReplyDetails(eventId: EventId): InReplyTo

    /**
     * Adds a new pinned event by sending an updated `m.room.pinned_events`
     * event containing the new event id.
     *
     * Returns `true` if we sent the request, `false` if the event was already
     * pinned.
     */
    suspend fun pinEvent(eventId: EventId): Result<Boolean>

    /**
     * Adds a new pinned event by sending an updated `m.room.pinned_events`
     * event without the event id we want to remove.
     *
     * Returns `true` if we sent the request, `false` if the event wasn't
     * pinned
     */
    suspend fun unpinEvent(eventId: EventId): Result<Boolean>

    /**
     * Get the latest event id of the timeline.
     */
    suspend fun getLatestEventId(): Result<EventId?>
}

sealed interface ForwardEventPairResult {
    data object Success : ForwardEventPairResult
    data class Failed(val reason: String?) : ForwardEventPairResult
    data class Ambiguous(val reason: String?) : ForwardEventPairResult
}
