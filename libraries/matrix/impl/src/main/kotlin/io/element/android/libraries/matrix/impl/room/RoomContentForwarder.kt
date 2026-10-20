/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.core.coroutine.parallelMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.room.ForwardEventException
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocationMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.impl.media.map
import io.element.android.libraries.matrix.impl.room.member.RoomMemberMapper
import io.element.android.libraries.matrix.impl.roomlist.roomOrNull
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedAttachmentMetadata
import io.element.android.libraries.matrix.impl.timeline.ProtectedMatrixPayload
import io.element.android.libraries.matrix.impl.timeline.ProtectedPollAnswer
import io.element.android.libraries.matrix.impl.timeline.RustTimeline
import io.element.android.libraries.matrix.impl.timeline.runWithTimelineListenerRegistered
import io.element.android.libraries.matrix.impl.util.MessageEventContent
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.performForComponent
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import timber.log.Timber
import uniffi.matrix_sdk_base.EncryptionState
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal class ForwardTargetUnavailableException(val roomId: RoomId) : IllegalStateException("Forward target room is unavailable.")

private val EMPTY_IMAGE_INFO = ImageInfo(null, null, null, null, null, null, null)
private val EMPTY_VIDEO_INFO = VideoInfo(null, null, null, null, null, null, null, null)
private val EMPTY_AUDIO_INFO = AudioInfo(null, null, null)
private val EMPTY_FILE_INFO = FileInfo(null, null, null, null)

/**
 * Helper to forward event contents from a room to a set of other rooms.
 * @param roomListService the [RoomListService] to fetch room instances to forward the event to
 */
class RoomContentForwarder(
    private val roomListService: RoomListService,
    private val sessionId: SessionId? = null,
    private val deviceId: DeviceId? = null,
    private val protectedContentAdapterFactory: MatrixProtectedContentAdapterFactory? = null,
    private val matrixMediaLoader: (() -> MatrixMediaLoader)? = null,
    private val cacheDirectory: File? = null,
    private val lifecycleCoordinator: AppOperationalStateCoordinator? = null,
    private val targetTimeline: (suspend (RoomId) -> RustTimeline?)? = null,
) {
    /** Performs only checks that are known to happen before the first Matrix send. */
    suspend fun validateForwardPair(fromTimeline: Timeline, eventId: EventId, roomId: RoomId): Result<Unit> = runCatchingExceptions {
        val messageLikeContent = (fromTimeline.getEventTimelineItemByEventId(eventId.value).content as? TimelineItemContent.MsgLike)?.content
            ?: error("Forward source event is unavailable.")
        check((messageLikeContent.kind as? MsgLikeKind.Message)?.content != null) { "Forward source is not a message." }
        val room = roomListService.roomOrNull(roomId.value) ?: error("Forward target room is unavailable.")
        room.use { }
    }

    suspend fun validateTargetRoom(roomId: RoomId): Result<Unit> = runCatchingExceptions {
        val room = roomListService.roomOrNull(roomId.value) ?: error("Forward target room is unavailable.")
        room.use { }
    }

    /**
     * Forwards the event with the given [eventId] from the [fromTimeline] to the given [toRoomIds].
     * @param fromTimeline the room to forward the event from
     * @param eventId the id of the event to forward
     * @param toRoomIds the ids of the rooms to forward the event to
     * @param timeoutMs the maximum time in milliseconds to wait for the event to be sent to a room
     */
    suspend fun forward(
        fromTimeline: Timeline,
        eventId: EventId,
        toRoomIds: List<RoomId>,
        timeoutMs: Long = 5000L
    ) {
        val messageLikeContent = (fromTimeline.getEventTimelineItemByEventId(eventId.value).content as? TimelineItemContent.MsgLike)?.content
            ?: throw ForwardEventException(toRoomIds)

        val content = (messageLikeContent.kind as? MsgLikeKind.Message)?.content
            ?: throw ForwardEventException(toRoomIds)

        val targetRooms = toRoomIds.map { roomId ->
            roomListService.roomOrNull(roomId.value) ?: throw ForwardTargetUnavailableException(roomId)
        }
        val failedForwardingTo = mutableSetOf<RoomId>()
        targetRooms.parallelMap { room ->
            room.use { targetRoom ->
                runCatchingExceptions {
                    // Sending a message requires a registered timeline listener
                    targetRoom.timeline().runWithTimelineListenerRegistered {
                        withTimeout(timeoutMs.milliseconds) {
                            targetRoom.timeline().send(contentWithoutRelationFromMessage(content))
                        }
                    }
                }
            }.onFailure {
                failedForwardingTo.add(RoomId(room.id()))
            }
        }

        if (failedForwardingTo.isNotEmpty()) {
            throw ForwardEventException(failedForwardingTo.toList())
        }
    }

    suspend fun forwardProtected(content: EventContent, toRoomIds: List<RoomId>, timeoutMs: Long = 5_000L, operationId: String? = null) {
        val source = content as? MessageContent ?: error("Protected source content is unavailable.")
        check(source.isContentProtectionAuthorized && source.protectedMessageStatus == null && !source.isBurnAfterRead) {
            "Protected source content is not authorized for forwarding."
        }
        val adapterFactory = checkNotNull(protectedContentAdapterFactory)
        val activeSessionId = checkNotNull(sessionId)
        val activeDeviceId = checkNotNull(deviceId)
        val failed = mutableSetOf<RoomId>()
        val sourceMedia = source.type
            .let { it as? MessageTypeWithAttachment }
            ?.let { materializeProtectedAttachment(it) }
        try {
            toRoomIds.parallelMap { roomId ->
                val room = roomListService.roomOrNull(roomId.value) ?: run {
                    failed += roomId
                    return@parallelMap
                }
                room.use { targetRoom ->
                    runCatchingExceptions {
                        val adapter = adapterFactory.create(
                            sessionId = activeSessionId,
                            deviceId = activeDeviceId,
                            roomId = roomId,
                            activeMemberAccountIdHashes = { targetRoom.memberAccountIdHashes(activeSessionId) },
                            isRoomEncrypted = { targetRoom.roomInfo().encryptionState == EncryptionState.ENCRYPTED },
                        )
                        check(adapter.enabled())
                        val targetTimeline = targetRoom.timeline()
                        if (sourceMedia != null) {
                            forwardProtectedAttachment(sourceMedia, roomId, requireNotNull(operationId))
                        } else {
                            val payload = content.toForwardPayload()
                            val prepared = adapter.protectInteraction(ContentKind.FORWARD, payload)
                            try {
                                targetTimeline.runWithTimelineListenerRegistered {
                                    withTimeout(timeoutMs.milliseconds) {
                                        MessageEventContent.protected(prepared.encodedEnvelope).use { targetTimeline.send(it) }
                                    }
                                }
                            } catch (failure: Exception) {
                                check(adapter.destroyPrepared(prepared, ContentAccessDestroyReason.ADMINISTRATIVE_REVOCATION)) {
                                    "KMS refused to clean a failed protected forward."
                                }
                                throw failure
                            }
                        }
                    }.onFailure { failure ->
                        Timber.e(failure, "Protected forward failed for room %s", roomId.value)
                        failed += roomId
                    }
                }
            }
        } finally {
            sourceMedia?.delete()
        }
        if (failed.isNotEmpty()) throw ForwardEventException(failed.toList())
    }

    private suspend fun materializeProtectedAttachment(attachment: MessageTypeWithAttachment): SourceAttachment {
        val loader = checkNotNull(matrixMediaLoader) { "Protected media forwarding requires the secure media loader." }.invoke()
        val (source, thumbnailSource) = when (attachment) {
            is ImageMessageType -> attachment.source to attachment.info?.thumbnailSource
            is StickerMessageType -> attachment.source to attachment.info?.thumbnailSource
            is VideoMessageType -> attachment.source to attachment.info?.thumbnailSource
            is AudioMessageType -> attachment.source to null
            is VoiceMessageType -> attachment.source to null
            is FileMessageType -> attachment.source to attachment.info?.thumbnailSource
        }
        var mainBytes: ByteArray? = null
        var thumbnailBytes: ByteArray? = null
        try {
            mainBytes = loader.loadMediaContent(source).getOrThrow()
            thumbnailBytes = thumbnailSource?.let { loader.loadMediaContent(it).getOrThrow() }
            return SourceAttachment(attachment, mainBytes, thumbnailBytes)
        } catch (failure: Exception) {
            mainBytes?.fill(0)
            thumbnailBytes?.fill(0)
            throw failure
        }
    }

    private suspend fun forwardProtectedAttachment(source: SourceAttachment, roomId: RoomId, pairOperationId: String) {
        val coordinator = requireNotNull(lifecycleCoordinator)
        val parent = coordinator.operation(requireNotNull(currentLifecycleOperationContext()).operationId)
        check(parent.deadlineEpochMillis > System.currentTimeMillis()) { "Original forward deadline expired." }
        val timeline = requireNotNull(targetTimeline).invoke(roomId) ?: throw ForwardTargetUnavailableException(roomId)
        val directory = requireNotNull(cacheDirectory)
        val account = accountIdHash(requireNotNull(sessionId).value)
        val device = accountIdHash(requireNotNull(deviceId).value)
        val room = accountIdHash(roomId.value)
        val uploadId = "forward-upload-" + accountIdHash("$pairOperationId:${roomId.value}")
        val captionId = "media-caption-" + accountIdHash("$uploadId:caption")
        val metadata = when (val attachment = source.attachment) {
            is FileMessageType -> ProtectedAttachmentMetadata(attachment.filename, attachment.info?.mimetype,
                attachment.info?.size ?: source.mainBytes.size.toLong())
            is AudioMessageType -> ProtectedAttachmentMetadata(attachment.filename, attachment.info?.mimetype,
                attachment.info?.size ?: source.mainBytes.size.toLong())
            else -> null
        }
        val media = listOf(source.mainBytes to source.attachment.contentKind()) +
            listOfNotNull(source.thumbnailBytes?.let { it to ContentKind.IMAGE })
        var accepted = false
        try {
            coordinator.perform(lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_16, trigger = "upload", operationId = uploadId,
                accountIdHash = account, matrixDeviceIdHash = device, roomIdHash = room,
                deadlineEpochMillis = parent.deadlineEpochMillis,
            )) {
                val uploadContext = LifecycleOperationContext(uploadId, FullAppLifecycleId.LC_16)
                val send: suspend () -> Unit = {
                    withContext(MediaSubmissionContext(
                        beforeSdk = {},
                        accepted = {},
                        originalDeadline = { parent.deadlineEpochMillis },
                    )) {
                        timeline.sendForwardedAttachment(media, directory, source.attachment.caption,
                            source.attachment.formattedCaption?.body, metadata) { native, files, carrier ->
                            source.attachment.sendProtected(native, files.first(), files.getOrNull(1), carrier)
                        }.getOrThrow().await().getOrThrow()
                    }
                    accepted = true
                }
                if (metadata == null && source.attachment.caption == null && source.attachment.formattedCaption == null) {
                    send()
                } else {
                    coordinator.performForComponent(lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_15, trigger = "media_caption", operationId = captionId,
                        accountIdHash = account, matrixDeviceIdHash = device, roomIdHash = room,
                        deadlineEpochMillis = parent.deadlineEpochMillis,
                    ), LifecycleComponent.ANDROID_FRONTEND) {
                        withContext(uploadContext + MediaCaptionOperationContext(uploadId, captionId, account, device, room)) { send() }
                    }
                }
            }
        } catch (failure: Exception) {
            if (!accepted || failure is CancellationException) throw failure
            // Native commit is confirmed. Pending receipt delivery belongs to the original
            // coordinator and must not turn a successful forward into a second send.
            Timber.w("Forwarded media accepted; lifecycle receipt confirmation remains pending")
        }
    }

    private fun MessageTypeWithAttachment.sendProtected(
        timeline: Timeline,
        main: File,
        thumbnail: File?,
        captionCarrier: String?,
    ) = when (this) {
        is ImageMessageType -> timeline.sendImage(
            params = uploadParameters(main, captionCarrier),
            thumbnailSource = thumbnail?.path?.let(UploadSource::File),
            imageInfo = (info ?: EMPTY_IMAGE_INFO).copy(
                size = main.length(),
                thumbnailInfo = info?.thumbnailInfo?.copy(size = thumbnail?.length()),
            ).map(),
        )
        is StickerMessageType -> timeline.sendImage(
            params = uploadParameters(main, captionCarrier),
            thumbnailSource = thumbnail?.path?.let(UploadSource::File),
            imageInfo = (info ?: EMPTY_IMAGE_INFO).copy(
                size = main.length(),
                thumbnailInfo = info?.thumbnailInfo?.copy(size = thumbnail?.length()),
            ).map(),
        )
        is VideoMessageType -> timeline.sendVideo(
            params = uploadParameters(main, captionCarrier),
            thumbnailSource = thumbnail?.path?.let(UploadSource::File),
            videoInfo = (info ?: EMPTY_VIDEO_INFO).copy(
                size = main.length(),
                thumbnailInfo = info?.thumbnailInfo?.copy(size = thumbnail?.length()),
            ).map(),
        )
        is AudioMessageType -> timeline.sendAudio(
            params = uploadParameters(main, captionCarrier),
            audioInfo = (info ?: EMPTY_AUDIO_INFO).copy(size = main.length()).map(),
        )
        is VoiceMessageType -> timeline.sendAudio(
            params = uploadParameters(main, captionCarrier),
            audioInfo = (info ?: EMPTY_AUDIO_INFO).copy(size = main.length()).map(),
        )
        is FileMessageType -> timeline.sendFile(
            params = uploadParameters(main, captionCarrier),
            fileInfo = (info ?: EMPTY_FILE_INFO).copy(
                mimetype = MimeTypes.OctetStream,
                size = main.length(),
                thumbnailInfo = info?.thumbnailInfo?.copy(size = thumbnail?.length()),
            ).map(),
        )
    }

    private fun MessageTypeWithAttachment.contentKind(): ContentKind = when (this) {
        is ImageMessageType -> ContentKind.IMAGE
        is StickerMessageType -> ContentKind.STICKER
        is VideoMessageType -> ContentKind.VIDEO
        is AudioMessageType, is VoiceMessageType -> ContentKind.VOICE_MESSAGE
        is FileMessageType -> ContentKind.ATTACHMENT
    }

    private fun uploadParameters(file: File, captionCarrier: String?) = UploadParameters(
        source = UploadSource.File(file.path),
        caption = captionCarrier,
        formattedCaption = null,
        mentions = null,
        inReplyTo = null,
    )

    private data class SourceAttachment(
        val attachment: MessageTypeWithAttachment,
        val mainBytes: ByteArray,
        val thumbnailBytes: ByteArray?,
    ) {
        fun delete() {
            mainBytes.fill(0)
            thumbnailBytes?.fill(0)
        }
    }

    private suspend fun org.matrix.rustcomponents.sdk.Room.memberAccountIdHashes(requestingSessionId: SessionId): Set<String> = members().use { iterator ->
        buildSet {
            while (true) {
                val chunk = iterator.nextChunk(256u) ?: break
                addAll(chunk.map(RoomMemberMapper::map).protectedRoomAudienceAccountIdHashes())
            }
        }.requireProtectedRoomSender(accountIdHash(requestingSessionId.value))
    }

    private fun EventContent.toForwardPayload(): ProtectedMatrixPayload = when (this) {
        is MessageContent -> when (val message = type) {
            is TextMessageType -> ProtectedMatrixPayload(
                payloadType = "TEXT",
                body = message.body,
                htmlBody = message.formatted?.body,
            )
            is EmoteMessageType -> ProtectedMatrixPayload(
                payloadType = "TEXT",
                body = message.body,
                htmlBody = message.formatted?.body,
                messageType = "MSG_TYPE_EMOTE",
            )
            is LocationMessageType -> ProtectedMatrixPayload(
                payloadType = "LOCATION",
                body = message.body,
                geoUri = message.geoUri,
                description = message.description,
                assetType = message.assetType?.name,
            )
            else -> throw SecurityException("Protected media forwarding requires local media re-encryption and is not available for this item.")
        }
        is PollContent -> ProtectedMatrixPayload(
            payloadType = "POLL_START",
            aggregateId = java.util.UUID.randomUUID().toString(),
            question = question,
            pollKind = kind.name,
            maxSelections = maxSelections.toLong(),
            pollAnswers = answers.map { ProtectedPollAnswer(it.id, it.text) },
        )
        is LiveLocationContent -> locations.lastOrNull()?.let { location ->
            ProtectedMatrixPayload(
                payloadType = "LOCATION",
                body = description ?: "Live location",
                geoUri = location.geoUri,
                description = description,
            )
        } ?: throw SecurityException("Live location has no authorized coordinate to forward.")
        else -> throw SecurityException("This protected content type cannot be forwarded.")
    }
}
