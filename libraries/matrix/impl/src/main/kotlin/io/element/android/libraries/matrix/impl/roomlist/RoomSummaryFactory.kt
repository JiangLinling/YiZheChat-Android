/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.roomlist

import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.impl.room.RoomInfoMapper
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedMessageDisplayState
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.matrix.impl.timeline.item.event.map
import io.element.android.libraries.matrix.impl.timeline.protectedMessageDisplayState
import io.element.android.libraries.matrix.impl.timeline.protectedEnvelopeBody
import io.element.android.libraries.matrix.impl.timeline.hasProtectedCaptionCarrier
import io.element.android.libraries.matrix.impl.timeline.isCanonicalProtectedEventIdentity
import io.element.android.libraries.matrix.impl.timeline.withProtectedDisplayState
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import io.element.android.libraries.matrix.impl.timeline.item.event.EventTimelineItemMapper
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber
import uniffi.matrix_sdk_ui.LatestEventValueLocalState
import java.util.concurrent.atomic.AtomicLong
import org.matrix.rustcomponents.sdk.LatestEventValue as RustLatestEventValue

class RoomSummaryFactory(
    private val contentMapper: TimelineEventContentMapper = TimelineEventContentMapper(),
    private val roomInfoMapper: RoomInfoMapper = RoomInfoMapper(),
    private val sessionId: SessionId? = null,
    private val deviceId: DeviceId? = null,
    private val protectedContentAdapterFactory: MatrixProtectedContentAdapterFactory = DisabledMatrixProtectedContentAdapterFactory,
    private val previewTimelines: RoomPreviewTimelineCache? = null,
) {
    private val previewAdapters = java.util.concurrent.ConcurrentHashMap<RoomId, MatrixProtectedContentAdapter>()
    private data class CarrierBinding(val timestamp: Long, val sender: String,
                                      val message: io.element.android.libraries.matrix.api.timeline.item.event.MessageContent,
                                      val eventId: String)
    // Matrix identity facts only: no plaintext, lease or native timeline is retained.
    private val latestCarrierBindings = java.util.Collections.synchronizedMap(LinkedHashMap<RoomId, CarrierBinding>())
    private data class PreviewSignature(
        val timestamp: Long, val sender: String,
        val message: io.element.android.libraries.matrix.api.timeline.item.event.MessageContent,
        val isConfirmedOwnSummary: Boolean,
    )
    private val previewIdentityRetries = RoomPreviewIdentityRetryGate<PreviewSignature>()

    suspend fun create(room: Room): RoomSummary = create(room, initialSnapshot = false)

    // Structural list projection never starts a protected read. Visible previews are hydrated
    // after publication through create(), with the existing content authorization unchanged.
    suspend fun createInitial(room: Room): RoomSummary = create(room, initialSnapshot = true)

    private suspend fun create(room: Room, initialSnapshot: Boolean): RoomSummary {
        val roomInfo = room.roomInfo().let(roomInfoMapper::map)
        var sourceEventFingerprint: String? = null
        val latestEvent = room.latestEvent().use { event ->
            val sourceContent = when (event) {
                is RustLatestEventValue.Local -> contentMapper.map(event.content)
                is RustLatestEventValue.Remote -> contentMapper.map(event.content)
                else -> null
            }
            sourceEventFingerprint = sourceContent?.let { source ->
                java.security.MessageDigest.getInstance("SHA-256").digest(source.toString().encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            when (event) {
                is RustLatestEventValue.None -> LatestEventValue.None
                is RustLatestEventValue.Local -> when (event.state) {
                    LatestEventValueLocalState.IS_SENDING,
                    LatestEventValueLocalState.CANNOT_BE_SENT -> LatestEventValue.Local(
                        timestamp = event.timestamp.toLong(),
                        content = authorizeLatestContent(
                            room = room,
                            roomId = roomInfo.id,
                            timestamp = event.timestamp.toLong(),
                            sender = event.sender,
                            content = requireNotNull(sourceContent),
                            isPendingLocalSummary = true,
                            initialSnapshot = initialSnapshot,
                        ),
                        isSending = event.state == LatestEventValueLocalState.IS_SENDING,
                        senderId = UserId(event.sender),
                        senderProfile = event.profile.map(),
                    )
                    // This is the same as a remote event, we just haven't received the local -> remote update yet
                    LatestEventValueLocalState.HAS_BEEN_SENT -> LatestEventValue.Remote(
                        timestamp = event.timestamp.toLong(),
                        content = authorizeLatestContent(
                            room = room,
                            roomId = roomInfo.id,
                            timestamp = event.timestamp.toLong(),
                            sender = event.sender,
                            content = requireNotNull(sourceContent),
                            isConfirmedOwnSummary = true,
                            initialSnapshot = initialSnapshot,
                        ),
                        senderId = UserId(event.sender),
                        senderProfile = event.profile.map(),
                        isOwn = true,
                    )
                }
                is RustLatestEventValue.Remote -> LatestEventValue.Remote(
                    timestamp = event.timestamp.toLong(),
                    content = authorizeLatestContent(
                        room = room,
                        roomId = roomInfo.id,
                        timestamp = event.timestamp.toLong(),
                        sender = event.sender,
                        content = requireNotNull(sourceContent),
                        isConfirmedOwnSummary = event.isOwn,
                        initialSnapshot = initialSnapshot,
                    ),
                    senderId = UserId(event.sender),
                    senderProfile = event.profile.map(),
                    isOwn = event.isOwn,
                )
                is RustLatestEventValue.RemoteInvite -> LatestEventValue.RoomInvite(
                    timestamp = event.timestamp.toLong(),
                    inviterId = event.inviter?.let(::UserId),
                    invitedProfile = event.inviterProfile.map(),
                )
            }
        }
        return RoomSummary(
            info = roomInfo,
            latestEvent = latestEvent,
            sourceEventFingerprint = sourceEventFingerprint,
        )
    }

    private suspend fun authorizeLatestContent(
        room: Room,
        roomId: RoomId,
        timestamp: Long,
        sender: String,
        content: io.element.android.libraries.matrix.api.timeline.item.event.EventContent,
        isConfirmedOwnSummary: Boolean = false,
        isPendingLocalSummary: Boolean = false,
        initialSnapshot: Boolean = false,
    ): io.element.android.libraries.matrix.api.timeline.item.event.EventContent {
        val message = content as? io.element.android.libraries.matrix.api.timeline.item.event.MessageContent ?: return content
        val currentSessionId = sessionId ?: return content
        val currentDeviceId = deviceId ?: return content
        if (initialSnapshot) {
            return message.withProtectedDisplayState(ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE)
        }
        return try {
            val adapter = previewAdapters.getOrPut(roomId) {
                protectedContentAdapterFactory.create(
                    sessionId = currentSessionId,
                    deviceId = currentDeviceId,
                    roomId = roomId,
                    activeMemberAccountIdHashes = { emptySet() },
                )
            }
            val protectedCarrier = message.protectedEnvelopeBody() != null || message.hasProtectedCaptionCarrier()
            if (protectedCarrier && isPendingLocalSummary) {
                return message.withProtectedDisplayState(ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE)
            }
            val canonicalIdentity = adapter.observedRoomSummaryEventIdentity(timestamp, sender, message)
                ?.takeIf(::isCanonicalProtectedEventIdentity)
                ?: if (protectedCarrier && !message.isBurnAfterRead) {
                    resolveLatestCarrier(roomId, timestamp, sender, message, isConfirmedOwnSummary)
                } else null
            // The SDK latest-event projection has no event ID. Only observed Matrix metadata
            // can identify a protected carrier; a timestamp/sender surrogate must never reach LC13.
            if ((message.protectedEnvelopeBody() != null || message.hasProtectedCaptionCarrier()) &&
                (canonicalIdentity == null || !isCanonicalProtectedEventIdentity(canonicalIdentity))
            ) return message.withProtectedDisplayState(ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE)
            val identity = canonicalIdentity ?: "unprotected-preview"
            // Independent lists may preview the same room concurrently. Reusing a raw scope would
            // replace the other list's still-live owner before it reaches its protected read.
            val previewId = previewOwnerSequence.incrementAndGet()
            check(previewId > 0) { "Room preview owner sequence exhausted." }
            val scope = adapter.openTimelineReleaseScope("room-summary-preview:$previewId")
            try {
                adapter.decryptMessageContent(
                    eventIdentity = identity,
                    message = message,
                    senderAccountIdHash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(sender.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) },
                    timelineReleaseIntent = TimelineContentReleaseIntent(
                        scopeId = scope, requestId = "preview:$identity", eventId = identity, generation = 0,
                        releaseClass = TimelineContentReleaseClass.HISTORY_PREFETCH,
                        readPurpose = TimelineContentReadPurpose.ROOM_PREVIEW,
                    ),
                )
            } finally {
                adapter.closeTimelineReleaseScope(scope)
            }
        } catch (failure: Exception) {
            // A request/owner may expire without cancelling the list itself. Preserve its structural
            // Matrix entry, but never let a genuinely cancelled parent publish a projection.
            currentCoroutineContext().ensureActive()
            val state = if (failure is CancellationException) {
                ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE
            } else {
                failure.protectedMessageDisplayState()
            }
            Timber.w("Room summary preview unavailable: %s", state.name)
            message.withProtectedDisplayState(state)
        }
    }

    private suspend fun resolveLatestCarrier(
        roomId: RoomId, timestamp: Long, sender: String,
        message: io.element.android.libraries.matrix.api.timeline.item.event.MessageContent,
        isConfirmedOwnSummary: Boolean,
    ): String? {
        latestCarrierBindings[roomId]?.takeIf { it.timestamp == timestamp && it.sender == sender && it.message == message }
            ?.let { return it.eventId }
        val attempt = previewIdentityRetries.begin(roomId, PreviewSignature(timestamp, sender, message, isConfirmedOwnSummary)) ?: return null
        var resolved: String? = null
        try {
            resolved = withTimeoutOrNull(5_000L) {
                previewTimelines?.withTimeline(roomId) { timeline ->
                    val eventId = timeline.latestEventId()?.takeIf(::isCanonicalProtectedEventIdentity) ?: run {
                        Timber.w("Room preview identity: latest Matrix event unavailable")
                        return@withTimeline null
                    }
                    val event = EventTimelineItemMapper(contentMapper).map(timeline.getEventTimelineItemByEventId(eventId))
                    // A sent local summary may retain its enqueue time after server confirmation.
                    // Its exact ciphertext and authenticated own author bind the real remote carrier;
                    // the canonical ID still passes through normal ownership and KMS authorization.
                    val confirmedOwnCarrier = isConfirmedOwnSummary && sender == sessionId?.value && event.isOwn
                    if (!event.isRemote || event.eventId?.value != eventId || (!confirmedOwnCarrier && event.timestamp != timestamp) ||
                        event.sender.value != sender || event.content != message) {
                        val candidate = event.content as? io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
                        // Boolean diagnostics only: do not log identifiers, bodies, or ciphertext.
                        Timber.w("Room preview identity mismatch: remote=%s id=%s time=%s sender=%s body=%s type=%s reply=%s thread=%s",
                            event.isRemote, event.eventId?.value == eventId, event.timestamp == timestamp,
                            event.sender.value == sender, candidate?.body == message.body, candidate?.type == message.type,
                            candidate?.inReplyTo == message.inReplyTo, candidate?.threadInfo == message.threadInfo)
                        return@withTimeline null
                    }
                    synchronized(latestCarrierBindings) {
                        latestCarrierBindings[roomId] = CarrierBinding(timestamp, sender, message, eventId)
                        while (latestCarrierBindings.size > 512) latestCarrierBindings.remove(latestCarrierBindings.keys.first())
                    }
                    eventId
                }
            }
            return resolved
        } finally {
            // Unchanged unresolved summaries must not rebuild whole native histories on every
            // unrelated room-list update. Observed canonical metadata still bypasses this gate,
            // and every resolved carrier still uses the existing ownership/KMS read checks.
            previewIdentityRetries.complete(attempt, success = resolved != null)
        }
    }

    private companion object {
        val previewOwnerSequence = AtomicLong()
    }
}
