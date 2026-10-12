/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.timeline.item.event

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.poll.PollAnswer
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.room.location.LiveLocationInfo
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet

@Immutable
sealed interface EventContent

data class MessageContent(
    val body: String,
    val inReplyTo: InReplyTo?,
    val isEdited: Boolean,
    val threadInfo: EventThreadInfo?,
    val type: MessageType,
    /** Non-null only for a fail-closed UI shell produced by protected-content processing. */
    val protectedMessageStatus: ProtectedMessageStatus? = null,
    /** True only after the YiZhe envelope, device gate, KMS grant, and payload integrity all succeed. */
    val isContentProtectionAuthorized: Boolean = false,
    /** Authenticated lifecycle marker used to suppress copy, forward and quote operations. */
    val isBurnAfterRead: Boolean = false,
    /** Authenticated presentation metadata for images selected in one send operation. */
    val protectedMediaAlbum: ProtectedMediaAlbum? = null,
    /** Room-local transfer projection only. It is never serialized into a Matrix event. */
    val localMediaProgress: Float? = null,
    /** Process-local copy classification/authority. Null fails closed and is never serialized. */
    val clipboardSource: MessageClipboardSource? = null,
    /** Identity bound by authenticated content processing; used to correlate successful protected transactions. */
    val authenticatedContentId: String? = null,
    /** Correlation from a Matrix-confirmed own event only. Never authorizes body display, decryption or recovery. */
    val confirmedProtectedContentId: String? = null,
    /** Authenticated relay item identity/revisions; presentation text alone cannot authorize editing. */
    val protectedRelayEntries: ImmutableList<ProtectedRelayEntry>? = null,
    val protectedRelayInitialEntryId: String? = null,
    /** Non-serialized room-list metadata from a currently authorized decrypted operation. */
    val protectedPreviewOperation: ProtectedOperationContent.Operation? = null,
) : EventContent

/**
 * Non-serialized identity of the exact projection which produced visible plaintext.
 * Matrix event mappers must never read or write this value on the wire.
 */
sealed interface MessageClipboardSource {
    /** Explicitly classified only after the protected-content adapter has ruled out a protected carrier. */
    data object Unprotected : MessageClipboardSource

    data class Protected(
        val sessionId: String,
        val roomId: String,
        val eventId: String,
        val contentVersion: String,
        val contentIds: ImmutableSet<String>,
        val authorizationRevision: Long,
        val expiresAtEpochMillis: Long,
    ) : MessageClipboardSource {
        init {
            require(sessionId.isNotBlank())
            require(roomId.isNotBlank())
            require(eventId.isNotBlank())
            require(contentVersion.isNotBlank())
            require(contentIds.isNotEmpty() && contentIds.none(String::isBlank))
            require(authorizationRevision >= 0L)
            require(expiresAtEpochMillis > 0L)
        }
    }
}

data class ProtectedMediaAlbum(
    val id: String,
    val index: Int,
    val count: Int,
)

enum class ProtectedMessageStatus {
    DECRYPTING,
    ATTACHMENT_DECRYPTING,
    BURN_AFTER_READ_UNOPENED,
    LEGACY_BLOCKED,
    TEMPORARILY_UNAVAILABLE,
    SEND_BINDING_PENDING,
    LICENSE_AUTHORIZATION_FAILED,
    VAULT_LOCKED,
    DEVICE_SECURITY_UNAVAILABLE,
    ACCESS_DENIED,
    EXPIRED,
    REVOKED,
    INTEGRITY_REJECTED,
}

data object RedactedContent : EventContent

data class StickerContent(
    val filename: String,
    val body: String?,
    val info: ImageInfo,
    val source: MediaSource,
    val threadInfo: EventThreadInfo?,
) : EventContent {
    val bestDescription: String
        get() = body ?: filename
}

data class PollContent(
    val question: String,
    val kind: PollKind,
    val maxSelections: ULong,
    val answers: ImmutableList<PollAnswer>,
    val votes: ImmutableMap<String, ImmutableList<UserId>>,
    val endTime: ULong?,
    val isEdited: Boolean,
    val threadInfo: EventThreadInfo?,
) : EventContent

data class UnableToDecryptContent(
    val data: Data,
    val threadInfo: EventThreadInfo?,
) : EventContent {
    @Immutable
    sealed interface Data {
        data class OlmV1Curve25519AesSha2(
            val senderKey: String
        ) : Data

        data class MegolmV1AesSha2(
            val sessionId: String,
            val utdCause: UtdCause
        ) : Data

        data object Unknown : Data
    }
}

data class RoomMembershipContent(
    val userId: UserId,
    val userDisplayName: String?,
    val change: MembershipChange?,
    val reason: String?,
) : EventContent

data class ProfileChangeContent(
    val displayName: String?,
    val prevDisplayName: String?,
    val avatarUrl: String?,
    val prevAvatarUrl: String?
) : EventContent

data class StateContent(
    val stateKey: String,
    val content: OtherState
) : EventContent

data class FailedToParseMessageLikeContent(
    val eventType: String,
    val error: String
) : EventContent

data class FailedToParseStateContent(
    val eventType: String,
    val stateKey: String,
    val error: String
) : EventContent

data class LiveLocationContent(
    val isLive: Boolean,
    val description: String?,
    val startTimestamp: Long,
    val timeout: Long,
    val assetType: AssetType?,
    val locations: List<LiveLocationInfo>,
    val protectedAggregateId: String? = null,
) : EventContent {
    val endTimestamp = startTimestamp + timeout
}

/**
 * Authenticated KMS operation carrier. These events are consumed by the deterministic timeline
 * reducer and are never rendered as standalone messages.
 */
data class ProtectedOperationContent(
    val protocolVersion: Int,
    val operationId: String,
    val operation: Operation,
    val targetEventId: String,
    val aggregateId: String?,
    val emoji: String? = null,
    val enabled: Boolean? = null,
    val question: String? = null,
    val pollKind: PollKind? = null,
    val maxSelections: ULong? = null,
    val pollAnswers: List<PollAnswer> = emptyList(),
    val selectedAnswerIds: List<String> = emptyList(),
    val pollVotes: Map<String, List<UserId>> = emptyMap(),
    val caption: String? = null,
    val formattedCaption: String? = null,
    val geoUri: String? = null,
    val description: String? = null,
    val timestamp: Long? = null,
    val relayEntry: String? = null,
    val relayEntryId: String? = null,
    val relayExpectedRevision: Long? = null,
    val callLifecycleState: CallLifecycleState? = null,
    val durationMillis: Long? = null,
    /** Local HIDDEN projection status only; never a protected payload or authorization fact. */
    val protectedMessageStatus: ProtectedMessageStatus? = null,
) : EventContent {
    enum class Operation {
        REACTION_SET,
        POLL_EDIT,
        POLL_RESPONSE,
        POLL_END,
        CAPTION_SET,
        LIVE_LOCATION_UPDATE,
        LIVE_LOCATION_END,
        RELAY_APPEND,
        RELAY_EDIT,
        RELAY_CLOSE,
        CALL_STATE,
        HIDDEN,
    }
}

data object LegacyCallInviteContent : EventContent

data class CallNotifyContent(
    val callIntent: CallIntent,
    val declinedBy: List<UserId>,
    val lifecycleState: CallLifecycleState? = null,
    val durationMillis: Long? = null,
    val lifecycleActor: UserId? = null,
) : EventContent

enum class CallLifecycleState {
    CONNECTED,
    DECLINED,
    CANCELLED,
    MISSED,
    ENDED,
}

data object UnknownContent : EventContent
