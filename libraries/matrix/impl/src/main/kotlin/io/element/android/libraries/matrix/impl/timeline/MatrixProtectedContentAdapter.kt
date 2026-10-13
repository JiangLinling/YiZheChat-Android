/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.item.event.withMediaEventOrigin

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.poll.PollAnswer
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.room.location.LiveLocationInfo
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.LegacyCallInviteContent
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocationMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.OtherMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoCandidate
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoEncryptedFile
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoLocator
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoResource
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.impl.media.BurnAfterReadMediaSessionRegistry
import io.element.android.libraries.matrix.impl.media.MatrixProtectedMediaService
import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.libraries.matrix.impl.util.MessageEventContent
import io.element.android.security.contentprotection.api.BurnAfterReadEndReason
import io.element.android.security.contentprotection.api.BurnAfterReadSession
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentEnvelopeCodec
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.EndBurnAfterReadRequest
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentInvalidation
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OpenBurnAfterReadRequest
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ProtectContentRequest
import io.element.android.security.contentprotection.api.ReleaseContentRequest
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import io.element.android.security.contentprotection.api.TimelineContentReadPurpose
import io.element.android.security.contentprotection.api.TimelineReleaseScopeOwnerToken
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.BurnConsumptionKey
import io.element.android.security.dataprotection.api.BurnConsumptionState
import io.element.android.security.dataprotection.api.BurnConsumptionStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.PendingBurnConsumption
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.withOperation
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.microseconds

/** Identity metadata is optional. Its failure must retain the normal fail-closed authorization path. */
internal suspend fun <T> optionalCarrierIdentity(readIdentity: suspend () -> T): T? = try {
    readIdentity()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

interface MatrixProtectedContentAdapterFactory {
    fun create(
        sessionId: SessionId,
        deviceId: DeviceId,
        roomId: RoomId,
        activeMemberAccountIdHashes: suspend () -> Set<String>,
        isRoomEncrypted: suspend () -> Boolean = { false },
    ): MatrixProtectedContentAdapter
}

data class PreparedProtectedContent(
    val encodedEnvelope: String,
    val contentId: ContentId,
)

data class ProtectedAttachmentMetadata(
    val displayName: String,
    val mimeType: String?,
    val size: Long,
)

data class ProtectedCaption(
    val caption: String?,
    val formattedCaption: String?,
    val attachmentMetadata: ProtectedAttachmentMetadata? = null,
    val mediaAlbum: ProtectedMediaAlbum? = null,
)

/** Diagnostic labels only: never part of authorization, shared-read identity, nonce, or cache keys. */
enum class ProtectedReadDiagnosticOrigin(val token: String) {
    UNATTRIBUTED("unattributed"),
    NOTIFICATION_PREVIEW("notification_preview"),
}

interface MatrixProtectedContentAdapter {
    /** Bind a producer record before the SDK can publish its durable local echo. */
    suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) = Unit
    /** Hash-only account scope used to reject another session's additive recovery signal. */
    fun accountScopeId(): String? = null

    /** Exact Matrix room owned by this adapter. */
    fun roomScopeId(): String? = null

    fun captionLineageWiring(): ProtectedCaptionLineageWiring = UnavailableProtectedCaptionLineageWiring

    suspend fun protectTextEdit(item: MatrixTimelineItem.Event, body: String, htmlBody: String?): PreparedProtectedContent =
        throw SecurityException("TEXT version editing is unavailable.")

    suspend fun authenticatedCaptionTarget(item: MatrixTimelineItem.Event): ProtectedCaptionTarget =
        throw SecurityException("Authenticated caption lineage target is unavailable.")

    suspend fun enabled(): Boolean
    suspend fun protectText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        contentId: ContentId? = null,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): PreparedProtectedContent
    suspend fun protectReplyText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        repliedToEventId: EventId,
        contentId: ContentId? = null,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): PreparedProtectedContent = error("Protected reply lineage is unavailable.")
    suspend fun protectBurnAfterReadText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
    ): PreparedProtectedContent
    suspend fun burnAfterReadLifecycle(): ContentLifecycle
    suspend fun protectLocation(
        body: String,
        geoUri: String,
        description: String?,
        assetType: AssetType?,
        contentId: ContentId? = null,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): PreparedProtectedContent
    suspend fun protectInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>? = null,
        contentId: ContentId? = null,
    ): PreparedProtectedContent
    fun releaseLiveLocationProducer() = Unit
    suspend fun verifyLiveLocationIntent(kind: ContentKind, payload: ProtectedMatrixPayload): Boolean = false
    suspend fun verifyRelayEditIntent(payload: ProtectedMatrixPayload): Boolean = false
    suspend fun retainRelayEditRecovery(payload: ProtectedMatrixPayload): Unit = error("Durable relay recovery is unavailable.")
    suspend fun pendingRelayEditRecovery(targetEventId: String): io.element.android.libraries.matrix.api.timeline.RelayEditRecovery? = null
    suspend fun resolveRelayEditRecovery(targetEventId: String, operationId: String): Unit = error("Durable relay recovery is unavailable.")
    suspend fun protectMedia(
        file: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): ProtectedMatrixMedia
    suspend fun protectMedia(
        bytes: ByteArray,
        workingDirectory: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): ProtectedMatrixMedia = error("In-memory media protection is unavailable.")
    suspend fun protectMedia(
        source: InputStream,
        workingDirectory: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): ProtectedMatrixMedia = error("Streaming media protection is unavailable.")
    suspend fun protectCaption(
        caption: String?,
        formattedCaption: String?,
        lifecycle: ContentLifecycle = ContentLifecycle(),
        attachmentMetadata: ProtectedAttachmentMetadata? = null,
        mediaAlbum: ProtectedMediaAlbum? = null,
    ): PreparedProtectedContent
    suspend fun decryptCaption(encodedEnvelope: String): ProtectedCaption
    suspend fun destroyPrepared(content: PreparedProtectedContent, reason: ContentAccessDestroyReason): Boolean
    suspend fun destroyMedia(media: ProtectedMatrixMedia, reason: ContentAccessDestroyReason): Boolean
    suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem
    suspend fun decryptItem(
        item: MatrixTimelineItem,
        timelineReleaseIntent: TimelineContentReleaseIntent,
    ): MatrixTimelineItem = decryptItem(item)

    fun cancelTimelineReleaseRequests(scopeId: String, requestIds: Set<String>) = Unit
    fun updateTimelineReleaseViewport(scopeId: String, generation: Long, visibleRequestIds: List<String>) = Unit
    /** Eviction preference only. This never grants permission or extends a projection's lifetime. */
    fun protectRecentTimelineCache(items: List<MatrixTimelineItem>) = Unit
    fun openTimelineReleaseScope(scopeId: String): String = scopeId
    fun closeTimelineReleaseScope(scopeId: String) = Unit
    /** Identity metadata only, published by an existing live Matrix subscription. */
    suspend fun updateRoomSummaryCarrier(scopeId: String, latestEvent: MatrixTimelineItem.Event?) = Unit
    suspend fun observedRoomSummaryEventIdentity(timestamp: Long, sender: String, message: MessageContent): String? = null

    /**
     * Returns an already-authenticated, process-memory-only projection when it is still valid for
     * the current authorization generation. A miss must return null and continue through the
     * normal fail-closed shell/decrypt path.
     */
    suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? = null
    /** Original transaction presentation only; this never authorizes the replacement Matrix event. */
    suspend fun pendingOriginalSendProjection(source: MatrixTimelineItem, replacement: MatrixTimelineItem): MatrixTimelineItem? = null
    suspend fun openBurnAfterRead(item: MatrixTimelineItem): MatrixTimelineItem = item
    suspend fun endBurnAfterRead(
        item: MatrixTimelineItem,
        reason: BurnAfterReadEndReason = BurnAfterReadEndReason.VIEW_CLOSED,
    ): Boolean = false
    suspend fun isBurnAfterRead(item: MatrixTimelineItem): Boolean = false

    /**
     * Authorizes a non-timeline projection (room summary or notification) through the same
     * envelope, KMS, trust and in-memory cache path used by the visible timeline.
     */
    suspend fun decryptMessageContent(eventIdentity: String, message: MessageContent): MessageContent = message

    /** Marks the call site without supplying or changing a read authorization purpose. */
    suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        diagnosticOrigin: ProtectedReadDiagnosticOrigin,
    ): MessageContent = decryptMessageContent(eventIdentity, message)

    suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        timelineReleaseIntent: TimelineContentReleaseIntent,
        senderAccountIdHash: String,
    ): MessageContent = decryptMessageContent(eventIdentity, message, timelineReleaseIntent)

    suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        diagnosticOrigin: ProtectedReadDiagnosticOrigin,
        senderAccountIdHash: String,
    ): MessageContent = decryptMessageContent(eventIdentity, message, diagnosticOrigin)

    suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        timelineReleaseIntent: TimelineContentReleaseIntent,
    ): MessageContent = decryptMessageContent(eventIdentity, message)

    /** Returns a fail-closed, non-decrypted shell for a batch of changed timeline items. */
    suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> = items

    /** True when [timelineItemsShell] uses a transient decrypting shell for this raw item. */
    suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean = false

    /**
     * Persists the local revocation tombstone and clears process-memory plaintext before a Matrix
     * redaction is submitted. Remote KMS destruction remains a separate operation so its network
     * latency cannot extend the receiver's visible cleartext window.
     */
    suspend fun revokeItemLocally(item: MatrixTimelineItem): Boolean = false

    /** Confirms the accepted redaction from authoritative Matrix storage for the active operation. */
    suspend fun confirmMatrixRedaction(eventId: EventId): Boolean = false

    /** Clears only the derived plaintext projections affected by an inbound redaction. */
    fun clearDerivedPlaintextForRedaction(eventIdentities: Set<String>) = Unit

    /** True when the immutable encrypted carrier is bound to [contentId]. */
    fun containsContent(item: MatrixTimelineItem, contentId: ContentId): Boolean = false

    suspend fun destroyItem(item: MatrixTimelineItem, reason: ContentAccessDestroyReason): Boolean

    /** Current monotonic authorization generation used to reject stale in-flight projections. */
    fun authorizationRevision(): Long = 0L

    /** Signals that cached authorization results are invalid and active raw events must be checked again. */
    fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable = AutoCloseable {}

    /** Signals a permanent terminal transition for one content id. */
    fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable = AutoCloseable {}
}

internal sealed interface StrictMatrixMessageDecision {
    data object DecryptEnvelope : StrictMatrixMessageDecision
    data class AllowProtectedAttachment(val content: MessageContent) : StrictMatrixMessageDecision
    data object Block : StrictMatrixMessageDecision
}

/**
 * Fail-closed classifier for Matrix message events while the signed policy requires the YiZhe content envelope.
 * The protected attachment name is only a transport pre-filter. The media loader authenticates the YZMF frame.
 */
internal fun classifyStrictMatrixMessage(message: MessageContent): StrictMatrixMessageDecision {
    if (message.protectedEnvelopeBody() != null) {
        return StrictMatrixMessageDecision.DecryptEnvelope
    }
    val attachment = message.type as? MessageTypeWithAttachment ?: return StrictMatrixMessageDecision.Block
    val caption = attachment.caption
    if (!attachment.filename.isProtectedMediaTransportName() ||
        caption != null && !caption.isProtectedCaptionCarrier() ||
        attachment.formattedCaption != null
    ) {
        return StrictMatrixMessageDecision.Block
    }
    val sanitized = when (val type = message.type) {
        is ImageMessageType -> if (attachment.filename.startsWith(".yizhe-sticker-")) {
            io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType(
                filename = PROTECTED_ATTACHMENT_LABEL,
                caption = null,
                formattedCaption = null,
                source = type.source,
                info = type.info,
            )
        } else {
            type.copy(filename = PROTECTED_ATTACHMENT_LABEL, caption = null, formattedCaption = null)
        }
        is AudioMessageType -> type.copy(filename = PROTECTED_ATTACHMENT_LABEL, caption = null, formattedCaption = null)
        is VoiceMessageType -> type.copy(filename = PROTECTED_ATTACHMENT_LABEL, caption = null, formattedCaption = null)
        is VideoMessageType -> type.copy(filename = PROTECTED_ATTACHMENT_LABEL, caption = null, formattedCaption = null)
        is FileMessageType -> type.copy(filename = PROTECTED_ATTACHMENT_LABEL, caption = null, formattedCaption = null)
        else -> return StrictMatrixMessageDecision.Block
    }
    return StrictMatrixMessageDecision.AllowProtectedAttachment(
        message.copy(body = PROTECTED_ATTACHMENT_LABEL, type = sanitized)
    )
}

/**
 * Authorizes only the sanitized attachment projection. The encrypted media bytes are still
 * authenticated by SecureMatrixMediaLoader before they are decoded or rendered.
 */
internal fun MessageContent.authorizeProtectedAttachmentProjection(): MessageContent = copy(
    protectedMessageStatus = null,
    isContentProtectionAuthorized = true,
    clipboardSource = null,
)

/**
 * Live location is implemented by the Matrix SDK as encrypted beacon state and message events,
 * not as an m.room.message payload that can carry the YiZhe envelope. Keep this compatibility
 * bridge fail closed: it is available only after the room is confirmed encrypted and the beacon
 * metadata is structurally valid.
 */
internal fun allowEncryptedLiveLocationProjection(
    content: LiveLocationContent,
    isRoomEncrypted: Boolean,
): Boolean = isRoomEncrypted &&
    content.startTimestamp >= 0L &&
    content.timeout > 0L &&
    content.endTimestamp > content.startTimestamp

/**
 * Selects the receive path using the same two switches that enable protected outbound content.
 * A feature-only rollout decrypts YiZhe envelopes but continues to pass ordinary Matrix events
 * through. A signed strict policy additionally blocks every unsupported plaintext downgrade.
 */
internal fun classifyMatrixMessage(
    message: MessageContent,
    policyRequiresProtection: Boolean,
    contentProtectionFeatureEnabled: Boolean,
): StrictMatrixMessageDecision? = when {
    policyRequiresProtection -> classifyStrictMatrixMessage(message)
    contentProtectionFeatureEnabled &&
        message.protectedEnvelopeBody() != null ->
        StrictMatrixMessageDecision.DecryptEnvelope
    else -> null
}

internal fun MessageContent.protectedEnvelopeBody(): String? = when (val messageType = type) {
    is OtherMessageType -> messageType.body.takeIf { messageType.msgType == MessageEventContent.PROTECTED_MESSAGE_TYPE }
    is TextMessageType -> messageType.body
        .takeIf { it.startsWith(MessageEventContent.PROTECTED_BODY_PREFIX) }
        ?.removePrefix(MessageEventContent.PROTECTED_BODY_PREFIX)
    else -> null
}

private fun String.isProtectedMediaTransportName(): Boolean =
    startsWith(".yizhe-") && endsWith(".yzm") && length in MIN_PROTECTED_MEDIA_NAME_LENGTH..MAX_PROTECTED_MEDIA_NAME_LENGTH

private fun String.isBurnAfterReadMediaTransportName(): Boolean =
    startsWith(".yizhe-burn-") && isProtectedMediaTransportName()

internal fun String.protectedMediaContentIds(): List<ContentId> {
    if (!isProtectedMediaTransportName()) return emptyList()
    val values = removePrefix(".yizhe-").removePrefix("burn-").removePrefix("sticker-").removeSuffix(".yzm").split('~')
    return values.takeIf { it.isNotEmpty() && it.all(PROTECTED_MEDIA_CONTENT_ID_REGEX::matches) }
        ?.map(::ContentId)
        .orEmpty()
}

private fun String.isProtectedCaptionCarrier(): Boolean =
    startsWith(PROTECTED_CAPTION_PREFIX) && length <= MAX_PROTECTED_CAPTION_CHARS

internal fun MessageContent.hasProtectedCaptionCarrier(): Boolean =
    (type as? MessageTypeWithAttachment)?.caption?.isProtectedCaptionCarrier() == true

internal fun MessageContent.withExplicitUnprotectedClipboardSource(): MessageContent = copy(
    clipboardSource = if (
        protectedEnvelopeBody() == null &&
        protectedMediaTransportName() == null &&
        !hasProtectedCaptionCarrier() &&
        !isContentProtectionAuthorized &&
        !isBurnAfterRead &&
        protectedMessageStatus == null
    ) {
        MessageClipboardSource.Unprotected
    } else {
        null
    }
)

private fun MessageContent.protectedMediaTransportName(): String? =
    (type as? MessageTypeWithAttachment)?.filename?.takeIf(String::isProtectedMediaTransportName)

private fun MessageContent.burnAfterReadMediaContentIds(): List<ContentId> =
    protectedMediaTransportName()
        ?.takeIf(String::isBurnAfterReadMediaTransportName)
        ?.protectedMediaContentIds()
        .orEmpty()

internal enum class ProtectedMessageDisplayState(
    val status: ProtectedMessageStatus,
) {
    DECRYPTING(ProtectedMessageStatus.DECRYPTING),
    ATTACHMENT_DECRYPTING(ProtectedMessageStatus.ATTACHMENT_DECRYPTING),
    BURN_AFTER_READ_UNOPENED(ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED),
    LEGACY_BLOCKED(ProtectedMessageStatus.LEGACY_BLOCKED),
    TEMPORARILY_UNAVAILABLE(ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE),
    SEND_BINDING_PENDING(ProtectedMessageStatus.SEND_BINDING_PENDING),
    LICENSE_AUTHORIZATION_FAILED(ProtectedMessageStatus.LICENSE_AUTHORIZATION_FAILED),
    VAULT_LOCKED(ProtectedMessageStatus.VAULT_LOCKED),
    DEVICE_SECURITY_UNAVAILABLE(ProtectedMessageStatus.DEVICE_SECURITY_UNAVAILABLE),
    ACCESS_DENIED(ProtectedMessageStatus.ACCESS_DENIED),
    EXPIRED(ProtectedMessageStatus.EXPIRED),
    REVOKED(ProtectedMessageStatus.REVOKED),
    INTEGRITY_REJECTED(ProtectedMessageStatus.INTEGRITY_REJECTED),
}

internal fun MessageContent.withProtectedDisplayState(state: ProtectedMessageDisplayState): MessageContent = copy(
    body = "",
    type = TextMessageType("", formatted = null),
    protectedMessageStatus = state.status,
    isContentProtectionAuthorized = false,
    isBurnAfterRead = isBurnAfterRead || state == ProtectedMessageDisplayState.BURN_AFTER_READ_UNOPENED,
    clipboardSource = null,
)

internal fun StrictMatrixMessageDecision.shellDisplayState(): ProtectedMessageDisplayState? = when (this) {
    StrictMatrixMessageDecision.DecryptEnvelope -> ProtectedMessageDisplayState.DECRYPTING
    StrictMatrixMessageDecision.Block -> ProtectedMessageDisplayState.LEGACY_BLOCKED
    is StrictMatrixMessageDecision.AllowProtectedAttachment -> null
}

/**
 * True when a changed SDK item may temporarily render a decrypting shell while its authorized
 * projection is rebuilt. Existing projections for these items are retained by the timeline diff
 * processor, so an unrelated sync Reset cannot make an already visible message disappear.
 *
 * Burn-after-read items and hidden operation carriers deliberately remain excluded: their
 * fail-closed shells are lifecycle state, not a transient replacement for visible content.
 */
internal fun StrictMatrixMessageDecision.usesTransientDecryptingShell(
    isBurnAfterRead: Boolean,
    isOperationCarrier: Boolean,
): Boolean = !isBurnAfterRead && when (this) {
    StrictMatrixMessageDecision.DecryptEnvelope -> !isOperationCarrier
    is StrictMatrixMessageDecision.AllowProtectedAttachment -> true
    StrictMatrixMessageDecision.Block -> false
}

internal fun io.element.android.security.dataprotection.api.ProtectedPreviewAccessState.deniedDisplayState(): ProtectedMessageDisplayState =
    if (!deviceTrusted) ProtectedMessageDisplayState.DEVICE_SECURITY_UNAVAILABLE
    else if (!applicationUnlocked || !platformUnlocked) ProtectedMessageDisplayState.VAULT_LOCKED
    else ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE

internal fun Exception.protectedMessageDisplayState(): ProtectedMessageDisplayState = when (this) {
    is ContentProtectionException.SendBindingPending -> ProtectedMessageDisplayState.SEND_BINDING_PENDING
    is ContentProtectionException.KmsUnavailable,
    is ContentProtectionException.RoomEpochConflict -> ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE
    is ContentProtectionException.OfflineLicenseUnavailable,
    is ContentProtectionException.ReplayRejected -> ProtectedMessageDisplayState.LICENSE_AUTHORIZATION_FAILED
    is ContentProtectionException.DeviceKeyUnavailable -> ProtectedMessageDisplayState.DEVICE_SECURITY_UNAVAILABLE
    is ContentProtectionException.RecoveryApprovalRequired -> ProtectedMessageDisplayState.ACCESS_DENIED
    is ContentProtectionException.AccessDenied -> ProtectedMessageDisplayState.ACCESS_DENIED
    is ContentProtectionException.Expired -> ProtectedMessageDisplayState.EXPIRED
    is ContentProtectionException.Revoked -> ProtectedMessageDisplayState.REVOKED
    is StageThreeGateDenied -> when (reason) {
        StageThreeDenialReason.REMOTE_POLICY_UNAVAILABLE,
        StageThreeDenialReason.KMS_NOT_CONFIGURED,
        StageThreeDenialReason.KMS_AUTHORIZATION_UNAVAILABLE,
        StageThreeDenialReason.MATRIX_MAPPING_NOT_READY ->
            ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE
        StageThreeDenialReason.VAULT_LOCKED -> ProtectedMessageDisplayState.VAULT_LOCKED
        StageThreeDenialReason.DEVICE_NOT_TRUSTED,
        StageThreeDenialReason.INSTALLATION_MISMATCH,
        StageThreeDenialReason.HARDWARE_NOT_TRUSTED,
        StageThreeDenialReason.DEVICE_SHARE_KEY_MISMATCH,
        StageThreeDenialReason.RECIPIENT_REGISTRY_NOT_VERIFIED ->
            ProtectedMessageDisplayState.DEVICE_SECURITY_UNAVAILABLE
        StageThreeDenialReason.POLICY_NOT_ACTIVE,
        StageThreeDenialReason.POLICY_MISMATCH,
        StageThreeDenialReason.RETENTION_EXCEEDED,
        StageThreeDenialReason.OFFLINE_POLICY_MISMATCH,
        StageThreeDenialReason.POLICY_HARDWARE_REQUIREMENT_TOO_WEAK,
        StageThreeDenialReason.KMS_TRANSPORT_NOT_PINNED ->
            ProtectedMessageDisplayState.ACCESS_DENIED
    }
    else -> ProtectedMessageDisplayState.INTEGRITY_REJECTED
}

/**
 * Caches only failures that cannot become an authorization success for the same immutable envelope.
 * Availability, device-trust and policy-gate failures must be retried because management refresh can repair them.
 */
internal fun Exception.isStableProtectedRejection(): Boolean = when (this) {
    is ContentProtectionException.InvalidEnvelope,
    is ContentProtectionException.Expired,
    is ContentProtectionException.Revoked,
    is ContentProtectionException.ReplayRejected,
    is ContentProtectionException.AuthenticationFailed -> true
    else -> false
}

/**
 * Caches policy-version mismatches only for the authorization revision that observed them.
 *
 * A signed management refresh increments the revision, so a newly active policy is retried immediately. Keeping
 * this separate from the stable rejection cache avoids repeatedly evaluating old-policy events every time a room
 * is reopened without turning a recoverable authorization failure into a process-lifetime denial.
 */
internal fun Exception.isAuthorizationScopedProtectedRejection(): Boolean =
    this is StageThreeGateDenied && reason == StageThreeDenialReason.POLICY_MISMATCH

private const val PROTECTED_ATTACHMENT_LABEL = "受保护附件"
private const val MIN_PROTECTED_MEDIA_NAME_LENGTH = 16
private const val MAX_PROTECTED_MEDIA_NAME_LENGTH = 128
private const val MAX_ATTACHMENT_DISPLAY_NAME_CHARS = 255
private const val MAX_ATTACHMENT_MIME_TYPE_CHARS = 127
private val PROTECTED_MEDIA_CONTENT_ID_REGEX = Regex("[A-Za-z0-9._-]{8,96}")
internal const val PROTECTED_CAPTION_PREFIX = "yizhe-caption-v1:"
private const val MAX_PROTECTED_CAPTION_CHARS = 32 * 1024 * 1024
internal const val GROUP_BURN_AFTER_READ_MAX_MILLIS = 12 * 60 * 60_000L

/**
 * Binds a new protected message to every applicable expiry ceiling before the stage-three gate validates it.
 *
 * Protocol-v3 FOLLOW_ROOM contracts are the authoritative immutable expiry and are never shortened by a later
 * management-policy refresh. Legacy v1/v2 snapshots retain the historical ceiling calculation during coexistence.
 */
internal fun resolveProtectedContentExpiry(
    nowEpochMillis: Long,
    roomExpiresAtEpochMillis: Long,
    policyExpiresAtEpochMillis: Long?,
    policyContentRetentionMillis: Long,
    groupBurnAfterRead: Boolean,
    roomLifecycleProtocolVersion: Int = 1,
): Long {
    require(nowEpochMillis > 0)
    require(roomExpiresAtEpochMillis > nowEpochMillis)
    require(policyContentRetentionMillis > 0)
    if (roomLifecycleProtocolVersion >= 3 && !groupBurnAfterRead) {
        return roomExpiresAtEpochMillis
    }
    val policyRetentionDeadline = safeEpochAdd(nowEpochMillis, policyContentRetentionMillis)
    val ordinaryDeadline = minOf(
        roomExpiresAtEpochMillis,
        policyExpiresAtEpochMillis ?: Long.MAX_VALUE,
        policyRetentionDeadline,
    )
    return if (groupBurnAfterRead) {
        minOf(ordinaryDeadline, safeEpochAdd(nowEpochMillis, GROUP_BURN_AFTER_READ_MAX_MILLIS))
    } else {
        ordinaryDeadline
    }
}

private fun safeEpochAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

internal fun MessageContent.withProtectedCaption(caption: ProtectedCaption): MessageContent {
    val formatted = caption.formattedCaption?.let { FormattedBody(MessageFormat.HTML, it) }
    val typeWithCaption = when (val attachment = type) {
        is ImageMessageType -> {
            require(caption.attachmentMetadata == null)
            attachment.copy(caption = caption.caption, formattedCaption = formatted)
        }
        is AudioMessageType -> {
            require(caption.attachmentMetadata == null)
            attachment.copy(caption = caption.caption, formattedCaption = formatted)
        }
        is VoiceMessageType -> {
            require(caption.attachmentMetadata == null)
            attachment.copy(caption = caption.caption, formattedCaption = formatted)
        }
        is VideoMessageType -> {
            require(caption.attachmentMetadata == null)
            attachment.copy(caption = caption.caption, formattedCaption = formatted)
        }
        is FileMessageType -> {
            val metadata = caption.attachmentMetadata
            val existingInfo = attachment.info
            val restoredInfo = metadata?.let {
                existingInfo?.copy(
                    mimetype = it.mimeType,
                    size = it.size,
                    protectedWireSize = if (it.mimeType?.startsWith("video/") == true && attachment.source.json != null) {
                        existingInfo.protectedWireSize ?: existingInfo.size
                    } else existingInfo.protectedWireSize,
                ) ?: FileInfo(
                    mimetype = it.mimeType,
                    size = it.size,
                    thumbnailInfo = null,
                    thumbnailSource = null,
                )
            } ?: existingInfo
            attachment.copy(
                filename = metadata?.displayName ?: attachment.filename,
                caption = caption.caption,
                formattedCaption = formatted,
                info = restoredInfo,
            )
        }
        else -> throw SecurityException("Protected caption target is not an attachment.")
    }
    return copy(type = typeWithCaption, protectedMediaAlbum = caption.mediaAlbum)
}

private fun ProtectedAttachmentMetadata.validated(): ProtectedAttachmentMetadata = apply {
    require(displayName.isNotBlank() && displayName.length <= MAX_ATTACHMENT_DISPLAY_NAME_CHARS)
    require(displayName.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f })
    require(size >= 0)
    mimeType?.let { value ->
        require(value.length in 3..MAX_ATTACHMENT_MIME_TYPE_CHARS)
        require(value.count { it == '/' } == 1)
        require(value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f })
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMatrixProtectedContentAdapterFactory(
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val burnConsumptionStore: BurnConsumptionStore,
    private val contentProtectionService: ContentProtectionService,
    private val envelopeCodec: ContentEnvelopeCodec,
    private val recipientRegistry: DeviceRecipientRegistry,
    private val protectedMediaService: MatrixProtectedMediaService,
    private val policyProvider: DataProtectionPolicyProvider,
    private val kmsClientConfigurationProvider: KmsClientConfigurationProvider,
    private val deviceTrustService: DeviceTrustService,
    private val featureFlagService: FeatureFlagService,
    private val clock: SystemClock,
    private val decryptionCache: ProtectedTimelineDecryptionCache,
    private val pendingPayloadCache: ProtectedPendingPayloadCache,
    private val runtime: io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val burnAfterReadMediaSessionRegistry: BurnAfterReadMediaSessionRegistry,
    private val captionLineageCoordinatorFactory: ProtectedCaptionLineageCoordinatorFactory,
    private val protectedSendOriginStore: ProtectedSendOriginStore,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) : MatrixProtectedContentAdapterFactory {
    private val lifecycleWork = TimelineLifecycleWork(clock::epochMillis)

    override fun create(
        sessionId: SessionId,
        deviceId: DeviceId,
        roomId: RoomId,
        activeMemberAccountIdHashes: suspend () -> Set<String>,
        isRoomEncrypted: suspend () -> Boolean,
    ): MatrixProtectedContentAdapter = DefaultMatrixProtectedContentAdapter(
        sessionId = sessionId,
        deviceId = deviceId,
        roomId = roomId,
        appOperationalStateCoordinator = appOperationalStateCoordinator,
        burnConsumptionStore = burnConsumptionStore,
        contentProtectionService = contentProtectionService,
        envelopeCodec = envelopeCodec,
        recipientRegistry = recipientRegistry,
        protectedMediaService = protectedMediaService,
        policyProvider = policyProvider,
        kmsClientConfigurationProvider = kmsClientConfigurationProvider,
        deviceTrustService = deviceTrustService,
        featureFlagService = featureFlagService,
        clock = clock,
        activeMemberAccountIdHashes = activeMemberAccountIdHashes,
        isRoomEncrypted = isRoomEncrypted,
        decryptionCache = decryptionCache,
        pendingPayloadCache = pendingPayloadCache,
        runtime = runtime,
        protectedPreviewAuthorizationGate = protectedPreviewAuthorizationGate,
        burnAfterReadMediaSessionRegistry = burnAfterReadMediaSessionRegistry,
        captionLineageCoordinator = captionLineageCoordinatorFactory.create(sessionId, deviceId, roomId),
        protectedSendOriginStore = protectedSendOriginStore,
        lifecycleWork = lifecycleWork,
        coroutineScope = coroutineScope,
    )
}

/**
 * Process-memory-only cache for already authenticated timeline projections.
 *
 * The cache key deliberately includes the event identity, ciphertext digest and both protocol/policy versions.
 * It never writes plaintext to disk. The runtime owns its invalidation hook so app lock, logout, revoke and global
 * destroy can all clear it without a dependency from the UI layer.
 */
@Inject
@SingleIn(AppScope::class)
class ProtectedTimelineDecryptionCache(
    private val runtime: io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val clock: SystemClock,
) {
    data class Key(
        val eventId: String,
        val contentId: ContentId,
        val ciphertextHash: String,
        val policyVersion: Long,
        val envelopeVersion: Int,
        val sessionId: String,
        val roomId: String,
        val expiresAtEpochMillis: Long,
        val relatedContentIds: Set<ContentId> = emptySet(),
    )

    private var expiryJob: Job? = null

    /** Same authorized read only; never a reusable grant, key, or cross-operation authorization. */
    internal data class ReadIdentity(
        val projection: Key,
        val operationId: String,
        val matrixDeviceId: String,
        val installationId: String,
        val authorizationRevision: Long,
        val previewRevision: Long,
        val releaseClass: TimelineContentReleaseClass?,
        // Full encrypted carrier and projection metadata must match, not just the ciphertext digest.
        val encryptedMessage: MessageContent,
        val readPurpose: TimelineContentReadPurpose? = null,
    )

    private class Read(val result: Deferred<Result<EventContent>>, var waiters: Int = 0, var retiredForRetry: Boolean = false)
    private val reads = mutableMapOf<ReadIdentity, Read>()

    internal data class CarrierScope(val sessionId: String, val deviceId: String, val installationId: String, val roomId: String, val handle: String)
    internal data class ObservedCarrier(
        val projection: Key, val timestamp: Long, val sender: String, val encryptedMessage: MessageContent, val authorizationRevision: Long,
    )
    // One encrypted latest carrier per live subscription. No native handles, subscriptions, grant or plaintext.
    private val observedCarriers = LinkedHashMap<CarrierScope, ObservedCarrier>()
    private data class CarrierOwner(val sessionId: String, val deviceId: String, val roomId: String, val rawScopeId: String)
    private val carrierOwners = LinkedHashMap<CarrierOwner, String>()

    internal fun openObservedCarrierScope(sessionId: String, deviceId: String, roomId: String, rawScopeId: String, handle: String) =
        synchronized(entries) {
            val owner = CarrierOwner(sessionId, deviceId, roomId, rawScopeId)
            carrierOwners.remove(owner)?.let { previous -> removeObservedCarrierValuesLocked(sessionId, deviceId, roomId, previous) }
            if (carrierOwners.size >= MAX_SHARED_READS) {
                carrierOwners.keys.firstOrNull()?.let { oldest ->
                    carrierOwners.remove(oldest)?.let { previous ->
                        removeObservedCarrierValuesLocked(oldest.sessionId, oldest.deviceId, oldest.roomId, previous)
                    }
                }
            }
            carrierOwners[owner] = handle
            scheduleExpiryLocked()
        }

    private fun removeObservedCarrierValuesLocked(sessionId: String, deviceId: String, roomId: String, handle: String) {
        observedCarriers.keys.removeAll { it.sessionId == sessionId && it.deviceId == deviceId && it.roomId == roomId && it.handle == handle }
    }

    internal fun clearObservedCarrierValue(sessionId: String, deviceId: String, roomId: String, handle: String) = synchronized(entries) {
        removeObservedCarrierValuesLocked(sessionId, deviceId, roomId, handle)
        scheduleExpiryLocked()
    }

    internal fun removeObservedCarrierScope(sessionId: String, deviceId: String, roomId: String, handle: String) = synchronized(entries) {
        carrierOwners.entries.removeAll { (owner, current) ->
            owner.sessionId == sessionId && owner.deviceId == deviceId && owner.roomId == roomId && current == handle
        }
        removeObservedCarrierValuesLocked(sessionId, deviceId, roomId, handle)
        scheduleExpiryLocked()
    }

    internal fun updateObservedCarrier(scope: CarrierScope, carrier: ObservedCarrier?) = synchronized(entries) {
        observedCarriers.remove(scope)
        // Lifetime membership and publication share this lock. A suspended device-identity lookup
        // can finish after close/replacement, but must never reopen the old subscription's metadata.
        val liveOwner = carrierOwners.any { (owner, current) ->
            owner.sessionId == scope.sessionId && owner.deviceId == scope.deviceId && owner.roomId == scope.roomId && current == scope.handle
        }
        if (liveOwner && carrier != null && !carrier.projection.isExpired() && carrier.authorizationRevision == runtime.authorizationRevision()) {
            require(carrier.projection.sessionId == scope.sessionId && carrier.projection.roomId == scope.roomId)
            if (observedCarriers.size >= MAX_SHARED_READS) observedCarriers.keys.firstOrNull()?.let(observedCarriers::remove)
            observedCarriers[scope] = carrier
        }
        scheduleExpiryLocked()
    }

    internal fun observedEventIdentity(scope: CarrierScope, timestamp: Long, sender: String, encryptedMessage: MessageContent): String? =
        synchronized(entries) {
            val identities = observedCarriers.filter { (source, carrier) ->
                source.sessionId == scope.sessionId && source.deviceId == scope.deviceId && source.installationId == scope.installationId &&
                    source.roomId == scope.roomId && !carrier.projection.isExpired() && carrier.authorizationRevision == runtime.authorizationRevision() &&
                    carrier.timestamp == timestamp && carrier.sender == sender && carrier.encryptedMessage == encryptedMessage
            }.values.map { it.projection.eventId }.distinct()
            // A replay/ambiguous carrier must retain independent authorization, never pick one arbitrarily.
            identities.singleOrNull()
        }

    private val entries = LinkedHashMap<Key, EventContent>(PROJECTION_CACHE_CAPACITY, LOAD_FACTOR, true)
    private val entryWeights = mutableMapOf<Key, Long>()
    private val projectionReadOwners = mutableMapOf<Key, ReadIdentity>()
    private var estimatedBytes = 0L
    private data class RecentRoom(val sessionId: String, val roomId: String)
    private val recentRoomEvents = LinkedHashMap<RecentRoom, Set<String>>()

    internal fun protectRecentEvents(sessionId: String, roomId: String, eventIds: List<String>) = synchronized(entries) {
        val room = RecentRoom(sessionId, roomId)
        recentRoomEvents.remove(room)
        recentRoomEvents[room] = eventIds.take(MAX_PROTECTED_EVENTS_PER_ROOM).toSet()
        while (recentRoomEvents.size > MAX_RECENT_ROOMS) recentRoomEvents.remove(recentRoomEvents.keys.first())
    }

    private fun removeEntryLocked(key: Key): EventContent? {
        estimatedBytes -= entryWeights.remove(key) ?: 0L
        projectionReadOwners.remove(key)
        return entries.remove(key)
    }

    private fun removeEntriesLocked(predicate: (Key) -> Boolean): Boolean {
        val keys = entries.keys.filter(predicate)
        keys.forEach(::removeEntryLocked)
        return keys.isNotEmpty()
    }

    private fun trimEntriesLocked() {
        while (entries.size > PROJECTION_CACHE_CAPACITY || estimatedBytes > MAX_PROJECTION_BYTES) {
            val victim = entries.keys.firstOrNull { key ->
                key.eventId !in recentRoomEvents[RecentRoom(key.sessionId, key.roomId)].orEmpty()
            } ?: entries.keys.first()
            removeEntryLocked(victim)
        }
    }

    // Conservative text/metadata estimate; media bytes and decoded images are not stored here.
    // The cap is a cache budget, not an assertion about the JVM's exact object layout.
    private fun EventContent.estimatedWeight(): Long {
        val textChars = when (this) {
            is MessageContent -> body.length.toLong() + when (val messageType = type) {
                is TextMessageType -> messageType.body.length.toLong() + (messageType.formatted?.body?.length ?: 0)
                is EmoteMessageType -> messageType.body.length.toLong() + (messageType.formatted?.body?.length ?: 0)
                is MessageTypeWithAttachment -> messageType.filename.length.toLong() +
                    (messageType.caption?.length ?: 0) + (messageType.formattedCaption?.body?.length ?: 0)
                else -> 0L
            }
            is PollContent -> question.length.toLong() + answers.sumOf { it.text.length.toLong() }
            is LiveLocationContent -> (description?.length ?: 0).toLong()
            else -> 0L
        }
        val replyBytes = ((this as? MessageContent)?.inReplyTo as? InReplyTo.Ready)?.content?.estimatedWeight() ?: 0L
        val structuredBytes = when (this) {
            is PollContent -> answers.size * 256L + votes.entries.sumOf { (answer, voters) ->
                128L + answer.length * 4L + voters.sumOf { 128L + it.value.length * 4L }
            }
            is MessageContent -> ((type as? VideoMessageType)?.segmentedVideo?.resourceCount ?: 0).coerceAtLeast(0) * 2048L
            is LiveLocationContent -> locations.size * 1024L
            else -> 0L
        }
        return PROJECTION_ENTRY_OVERHEAD_BYTES + textChars * 4L + replyBytes + structuredBytes
    }
    private val stableRejections = object : LinkedHashMap<Key, ProtectedMessageDisplayState>(
        CACHE_CAPACITY,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, ProtectedMessageDisplayState>?,
        ): Boolean = size > CACHE_CAPACITY
    }
    private data class AuthorizationRejectionKey(val key: Key, val authorizationRevision: Long)
    private val authorizationRejections = object : LinkedHashMap<AuthorizationRejectionKey, ProtectedMessageDisplayState>(
        CACHE_CAPACITY,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AuthorizationRejectionKey, ProtectedMessageDisplayState>?,
        ): Boolean = size > CACHE_CAPACITY
    }

    init {
        runtime.registerSensitiveCacheClearer(::clear)
        runtime.registerSensitiveContentClearer(::remove)
        runtime.registerAuthorizationRefreshListener {
            synchronized(entries) {
                cancelReadsLocked { true }
                observedCarriers.clear()
            }
        }
    }

    /** Coalesces only in-flight work. Each consumer checks its own current display gate again. */
    internal suspend fun readOnce(
        identity: ReadIdentity,
        canRead: () -> Boolean,
        canConsume: () -> Boolean = { true },
        retryCancelledOwner: Boolean = true,
        decrypt: suspend () -> EventContent,
    ): EventContent {
        val operation = checkNotNull(currentLifecycleOperationContext())
        require(operation.lifecycleId == FullAppLifecycleId.LC_13 && operation.operationId == identity.operationId)
        val context = currentCoroutineContext().minusKey(Job)
        var isOwner = false
        val read = synchronized(entries) {
            requireReadable(identity, canRead)
            if (!canConsume()) throw ContentProtectionException.AccessDenied()
            reads[identity]?.also { it.waiters++ } ?: run {
                // Reserve half the registry for live reads. This registry is not another work queue.
                if (
                    reads.size >= MAX_SHARED_READS ||
                    (identity.releaseClass != TimelineContentReleaseClass.FOREGROUND_VISIBLE &&
                        reads.keys.count { it.releaseClass != TimelineContentReleaseClass.FOREGROUND_VISIBLE } >= MAX_BACKGROUND_READS)
                ) {
                    throw ContentProtectionException.KmsUnavailable()
                }
                lateinit var created: Read
                val result = coroutineScope.async(context, start = CoroutineStart.LAZY) {
                    // A denied/failed read belongs to its waiters, never to the application scope.
                    runCatching {
                        val content = decrypt()
                        synchronized(entries) {
                            if (reads[identity] !== created) throw ContentProtectionException.AccessDenied()
                            requireReadable(identity, canRead)
                            val message = content as? MessageContent ?: throw ContentProtectionException.AccessDenied()
                            if (
                                !message.isContentProtectionAuthorized || message.isBurnAfterRead ||
                                (message.type !is TextMessageType && message.type !is EmoteMessageType)
                            ) {
                                throw ContentProtectionException.AccessDenied()
                            }
                            put(identity.projection, content, identity)
                        }
                        content
                    }
                }
                created = Read(result, waiters = 1)
                reads[identity] = created
                isOwner = true
                scheduleExpiryLocked()
                created
            }
        }
        LifecycleStageTiming.point(
            if (isOwner) "receive_shared_read_owner" else "receive_shared_read_join",
            identity.projection.contentId.value,
            scope = ProtectedReadContextDiagnostics.descriptor(identity),
            operationId = identity.operationId,
        )
        try {
            val content = LifecycleStageTiming.measure("receive_shared_read_wait", identity.projection.contentId.value) {
                read.result.await().getOrThrow()
            }
            currentCoroutineContext().ensureActive()
            return synchronized(entries) {
                if (reads[identity] !== read) throw ContentProtectionException.AccessDenied()
                requireReadable(identity, canRead)
                if (!canConsume()) throw ContentProtectionException.AccessDenied()
                content
            }
        } catch (cancelled: CancellationException) {
            // A cancelled caller stays cancelled; runtime invalidation becomes a fail-closed shell.
            currentCoroutineContext().ensureActive()
            synchronized(entries) {
                requireReadable(identity, canRead)
                if (reads[identity] !== read && !read.retiredForRetry) throw ContentProtectionException.AccessDenied()
                if (!canConsume()) throw ContentProtectionException.AccessDenied()
                // The producing page's viewport may have ended while another page is still live.
                // That consumer retries once with its own existing scope, never with the old grant.
                if (reads[identity] === read) {
                    read.retiredForRetry = true
                    reads.remove(identity)
                    read.result.cancel()
                    scheduleExpiryLocked()
                }
            }
            if (retryCancelledOwner) return readOnce(identity, canRead, canConsume, retryCancelledOwner = false, decrypt = decrypt)
            throw ContentProtectionException.KmsUnavailable()
        } finally {
            synchronized(entries) {
                read.waiters--
                if (read.waiters == 0 && reads[identity] === read) {
                    reads.remove(identity)
                    read.result.cancel()
                    scheduleExpiryLocked()
                }
            }
        }
    }

    private fun requireReadable(identity: ReadIdentity, canRead: () -> Boolean) {
        if (identity.projection.isExpired()) throw ContentProtectionException.Expired()
        if (identity.authorizationRevision != runtime.authorizationRevision() || !canRead()) {
            throw ContentProtectionException.AccessDenied()
        }
    }

    /** Rechecks the existing projection after lifecycle suspension, only for its exact read owner. */
    internal fun completedProjectionForRead(identity: ReadIdentity, canRead: () -> Boolean, canConsume: () -> Boolean): EventContent? =
        synchronized(entries) {
            requireReadable(identity, canRead)
            if (!canConsume()) throw ContentProtectionException.AccessDenied()
            entries[identity.projection].takeIf { projectionReadOwners[identity.projection] == identity }.also { result ->
                ProtectedReadContextDiagnostics.observed(
                    if (result != null) "receive_read_completed_hit" else "receive_read_completed_miss",
                    identity, projectionReadOwners[identity.projection],
                )
            }
        }

    private fun cancelReadsLocked(matches: (ReadIdentity) -> Boolean) {
        val iterator = reads.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (matches(entry.key)) {
                iterator.remove()
                entry.value.result.cancel()
            }
        }
    }

    fun get(key: Key): EventContent? = synchronized(entries) {
        if (key.isExpired()) {
            removeEntryLocked(key)
            null
        } else {
            entries[key]
        }
    }

    internal fun getStableRejection(key: Key): ProtectedMessageDisplayState? = synchronized(entries) {
        stableRejections[key]
    }

    internal fun getAuthorizationRejection(key: Key, authorizationRevision: Long): ProtectedMessageDisplayState? = synchronized(entries) {
        authorizationRejections[AuthorizationRejectionKey(key, authorizationRevision)]
    }

    fun put(key: Key, content: EventContent) = put(key, content, null)

    private fun put(key: Key, content: EventContent, readOwner: ReadIdentity?) = synchronized(entries) {
        stableRejections.remove(key)
        authorizationRejections.keys.removeAll { it.key == key }
        if (!key.isExpired()) {
            val previousOwner = projectionReadOwners[key]
            runCatching {
            if (readOwner != null) {
                ProtectedReadContextDiagnostics.observed(
                    when {
                        previousOwner == null -> "receive_read_owner_publish_attempt"
                        entries[key] == content -> "receive_read_owner_replace_attempt_equal"
                        else -> "receive_read_owner_replace_attempt_different"
                    }, readOwner, previousOwner,
                )
            } else if (previousOwner != null) {
                ProtectedReadContextDiagnostics.observed("receive_read_owner_clear_attempt_unowned", previousOwner, null)
            }
            }
            removeEntryLocked(key)
            val weight = content.estimatedWeight() + (readOwner?.encryptedMessage?.estimatedWeight() ?: 0L)
            if (weight > MAX_PROJECTION_BYTES) return@synchronized
            entries[key] = content
            if (readOwner != null) projectionReadOwners[key] = readOwner
            entryWeights[key] = weight
            estimatedBytes += weight
            trimEntriesLocked()
            scheduleExpiryLocked()
        }
    }

    internal fun putStableRejection(key: Key, state: ProtectedMessageDisplayState) = synchronized(entries) {
        removeEntryLocked(key)
        authorizationRejections.keys.removeAll { it.key == key }
        stableRejections[key] = state
        scheduleExpiryLocked()
    }

    internal fun putAuthorizationRejection(
        key: Key,
        authorizationRevision: Long,
        state: ProtectedMessageDisplayState,
    ) = synchronized(entries) {
        removeEntryLocked(key)
        stableRejections.remove(key)
        authorizationRejections.keys.removeAll { it.key == key && it.authorizationRevision != authorizationRevision }
        authorizationRejections[AuthorizationRejectionKey(key, authorizationRevision)] = state
        scheduleExpiryLocked()
    }

    fun clear() = synchronized(entries) {
        cancelReadsLocked { true }
        expiryJob?.cancel()
        expiryJob = null
        entries.clear()
        entryWeights.clear()
        projectionReadOwners.clear()
        estimatedBytes = 0L
        recentRoomEvents.clear()
        observedCarriers.clear()
        stableRejections.clear()
        authorizationRejections.clear()
    }

    /**
     * Removes every cached projection for one timeline event without changing the global
     * authorization generation. Revoking one message must not force unrelated history back
     * through its fail-closed shell and decrypt pipeline.
     */
    fun remove(roomId: String, eventIdentity: String) = synchronized(entries) {
        observedCarriers.entries.removeAll { it.value.projection.roomId == roomId && it.value.projection.eventId == eventIdentity }
        cancelReadsLocked { it.projection.roomId == roomId && it.projection.eventId == eventIdentity }
        removeEntriesLocked { key -> key.roomId == roomId && key.eventId == eventIdentity }
        stableRejections.keys.removeAll { key -> key.roomId == roomId && key.eventId == eventIdentity }
        authorizationRejections.keys.removeAll { rejection ->
            rejection.key.roomId == roomId && rejection.key.eventId == eventIdentity
        }
        scheduleExpiryLocked()
    }

    fun remove(contentId: ContentId) = synchronized(entries) {
        observedCarriers.entries.removeAll { it.value.projection.contains(contentId) }
        cancelReadsLocked { it.projection.contains(contentId) }
        removeEntriesLocked { key -> key.contains(contentId) }
        stableRejections.keys.removeAll { key -> key.contains(contentId) }
        authorizationRejections.keys.removeAll { rejection -> rejection.key.contains(contentId) }
        scheduleExpiryLocked()
    }

    private fun Key.contains(contentId: ContentId): Boolean =
        this.contentId == contentId || contentId in relatedContentIds

    private fun scheduleExpiryLocked() {
        expiryJob?.cancel()
        val nextExpiry = sequence {
            yieldAll(entries.keys)
            yieldAll(stableRejections.keys)
            yieldAll(authorizationRejections.keys.map(AuthorizationRejectionKey::key))
            yieldAll(reads.keys.map(ReadIdentity::projection))
            yieldAll(observedCarriers.values.map(ObservedCarrier::projection))
        }.minOfOrNull(Key::expiresAtEpochMillis) ?: return
        expiryJob = coroutineScope.launch {
            delay((nextExpiry - clock.epochMillis()).coerceAtLeast(0L))
            val removedProtectedPlaintext = synchronized(entries) {
                cancelReadsLocked { it.projection.isExpired() }
                observedCarriers.entries.removeAll { it.value.projection.isExpired() }
                val removed = removeEntriesLocked { it.isExpired() }
                stableRejections.keys.removeAll { it.isExpired() }
                authorizationRejections.keys.removeAll { it.key.isExpired() }
                expiryJob = null
                scheduleExpiryLocked()
                removed
            }
            if (removedProtectedPlaintext) {
                // Force every active timeline to re-project from its encrypted raw item. This makes
                // an already-open room lose access at the envelope deadline without user action.
                runtime.requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
            }
        }
    }

    private fun Key.isExpired(): Boolean = clock.epochMillis() >= expiresAtEpochMillis

    private companion object {
        const val CACHE_CAPACITY = 256
        const val PROJECTION_CACHE_CAPACITY = 1024
        const val MAX_PROJECTION_BYTES = 16L * 1024 * 1024
        const val PROJECTION_ENTRY_OVERHEAD_BYTES = 4096L
        const val MAX_RECENT_ROOMS = 8
        const val MAX_PROTECTED_EVENTS_PER_ROOM = 96
        const val LOAD_FACTOR = 0.75f
        const val MAX_SHARED_READS = 64
        const val MAX_BACKGROUND_READS = 32
    }
}

/**
 * Journal failure cannot prevent any KMS erasure attempt. A false KMS result or any exception
 * prevents terminal success; every content ID is attempted before the first exception is raised.
 */
internal suspend fun destroyContentIdsRetainingLineageDebt(
    contentIds: Set<ContentId>,
    markDestructionPending: suspend (ContentId) -> Unit,
    destroyContentAccess: suspend (ContentId) -> Boolean,
): Boolean {
    var destroyedAll = true
    var firstFailure: Exception? = null
    contentIds.forEach { contentId ->
        try {
            markDestructionPending(contentId)
        } catch (failure: Exception) {
            destroyedAll = false
            if (firstFailure == null) firstFailure = failure else if (firstFailure !== failure) firstFailure?.addSuppressed(failure)
        }
        try {
            if (!destroyContentAccess(contentId)) destroyedAll = false
        } catch (failure: Exception) {
            destroyedAll = false
            if (firstFailure == null) firstFailure = failure else if (firstFailure !== failure) firstFailure?.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
    return destroyedAll
}

/** Applies authenticated plaintext without replacing the SDK-owned identity or mutable event metadata. */
internal fun MatrixTimelineItem.Event.withCachedProtectedContent(content: EventContent): MatrixTimelineItem.Event =
    copy(event = event.copy(content = content, reactions = persistentListOf()))

internal fun canShareProtectedTextRead(
    kind: ContentKind,
    lifecycleMode: MessageLifecycleMode,
    leaseMode: OfflineLeaseMode,
    allowsLocalEcho: Boolean,
    isBurnAfterRead: Boolean,
): Boolean = kind == ContentKind.TEXT && lifecycleMode == MessageLifecycleMode.FOLLOW_ROOM &&
    leaseMode == OfflineLeaseMode.ONLINE_REQUIRED && !allowsLocalEcho && !isBurnAfterRead

internal fun EventContent.withProtectedClipboardSource(
    key: ProtectedTimelineDecryptionCache.Key,
    authorizationRevision: Long,
    confirmedEventId: String?,
): EventContent {
    val message = this as? MessageContent ?: return this
    if (
        !message.isContentProtectionAuthorized ||
        message.isBurnAfterRead ||
        message.protectedMessageStatus != null ||
        confirmedEventId == null ||
        confirmedEventId != key.eventId
    ) {
        return message.copy(clipboardSource = null)
    }
    return message.copy(
        clipboardSource = MessageClipboardSource.Protected(
            sessionId = key.sessionId,
            roomId = key.roomId,
            eventId = confirmedEventId,
            contentVersion = "sha256:${key.ciphertextHash}:policy:${key.policyVersion}:envelope:${key.envelopeVersion}",
            contentIds = (sequenceOf(key.contentId) + key.relatedContentIds.asSequence())
                .map(ContentId::value)
                .toPersistentSet(),
            authorizationRevision = authorizationRevision,
            expiresAtEpochMillis = key.expiresAtEpochMillis,
        )
    )
}

/**
 * Visits the complete confirmed-own-message batch before any per-item projection work begins.
 *
 * Lifecycle registration is deliberately initiated from this batch pass: if it were started only
 * by the decrypt worker, one pending KMS release could prevent every later event in the same burst
 * from being bound to its Matrix event id.
 */
internal fun prefetchConfirmedOwnMessageEvents(
    items: List<MatrixTimelineItem>,
    action: (MatrixTimelineItem.Event, MessageContent) -> Unit,
) {
    items.forEach { item ->
        val eventItem = item as? MatrixTimelineItem.Event ?: return@forEach
        if (!eventItem.event.isOwn || eventItem.eventId == null) return@forEach
        val message = eventItem.event.content as? MessageContent ?: return@forEach
        action(eventItem, message)
    }
}

/** Process-memory-only sender echo cache; plaintext is zeroed on expiry, lock, logout or destroy. */
@Inject
@SingleIn(AppScope::class)
class ProtectedPendingPayloadCache(
    clock: SystemClock,
    runtime: io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) {
    private val delegate = PendingProtectedPayloadCache(clock::epochMillis)

    init {
        runtime.registerSensitiveCacheClearer(delegate::clear)
        runtime.registerSensitiveContentClearer(delegate::remove)
    }

    internal fun put(key: PendingProtectedPayloadCache.Key, plaintext: ByteArray, owner: PendingProtectedPayloadCache.Owner? = null) {
        delegate.put(key, plaintext, owner)
        coroutineScope.launch {
            delay(PENDING_PAYLOAD_TTL_MILLIS)
            delegate.remove(key)
        }
    }
    internal fun get(key: PendingProtectedPayloadCache.Key): ByteArray? = delegate.get(key)
    internal fun ownership(key: PendingProtectedPayloadCache.Key, identity: PendingProtectedPayloadCache.Identity) = delegate.ownership(key, identity)
    internal fun isCurrent(ticket: PendingProtectedPayloadCache.Ticket) = delegate.isCurrent(ticket)
    internal fun get(ticket: PendingProtectedPayloadCache.Ticket): ByteArray? = delegate.get(ticket)
    internal fun remove(contentId: ContentId) = delegate.remove(contentId)
    internal fun clearLiveLocation(accountIdHash: String, roomId: String) = delegate.clearLiveLocation(accountIdHash, roomId)

    private companion object {
        const val PENDING_PAYLOAD_TTL_MILLIS = 60_000L
    }
}

/**
 * Binds one Matrix subscription lifetime to the exact content-service owner token returned when
 * that lifetime opened. The opaque handle, rather than the reusable raw scope id, is carried by
 * queued decrypt work so stale work can never borrow a replacement owner's token.
 */
internal class TimelineReleaseScopeOwnerRegistry {
    internal data class Registration(
        val rawScopeId: String,
        val qualifiedScopeId: String,
        val ownerToken: TimelineReleaseScopeOwnerToken,
    )

    private val registrationsByHandle = mutableMapOf<String, Registration>()
    private val handleByRawScope = mutableMapOf<String, String>()

    @Synchronized
    fun register(
        rawScopeId: String,
        qualifiedScopeId: String,
        ownerToken: TimelineReleaseScopeOwnerToken,
    ): String {
        handleByRawScope.remove(rawScopeId)?.let(registrationsByHandle::remove)
        val handle = "$rawScopeId#${ownerToken.value}"
        registrationsByHandle[handle] = Registration(rawScopeId, qualifiedScopeId, ownerToken)
        handleByRawScope[rawScopeId] = handle
        return handle
    }

    @Synchronized
    fun resolve(handle: String): Registration? = registrationsByHandle[handle]

    @Synchronized
    fun remove(handle: String): Registration? {
        val registration = registrationsByHandle.remove(handle) ?: return null
        if (handleByRawScope[registration.rawScopeId] == handle) {
            handleByRawScope.remove(registration.rawScopeId)
        }
        return registration
    }
}

private class DefaultMatrixProtectedContentAdapter(
    private val sessionId: SessionId,
    private val deviceId: DeviceId,
    private val roomId: RoomId,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val burnConsumptionStore: BurnConsumptionStore,
    private val contentProtectionService: ContentProtectionService,
    private val envelopeCodec: ContentEnvelopeCodec,
    private val recipientRegistry: DeviceRecipientRegistry,
    private val protectedMediaService: MatrixProtectedMediaService,
    private val policyProvider: DataProtectionPolicyProvider,
    private val kmsClientConfigurationProvider: KmsClientConfigurationProvider,
    private val deviceTrustService: DeviceTrustService,
    private val featureFlagService: FeatureFlagService,
    private val clock: SystemClock,
    private val activeMemberAccountIdHashes: suspend () -> Set<String>,
    private val isRoomEncrypted: suspend () -> Boolean,
    private val decryptionCache: ProtectedTimelineDecryptionCache,
    private val pendingPayloadCache: ProtectedPendingPayloadCache,
    private val runtime: io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
    private val burnAfterReadMediaSessionRegistry: BurnAfterReadMediaSessionRegistry,
    private val captionLineageCoordinator: ProtectedCaptionLineageCoordinator,
    private val protectedSendOriginStore: ProtectedSendOriginStore,
    private val lifecycleWork: TimelineLifecycleWork,
    private val coroutineScope: CoroutineScope,
) : MatrixProtectedContentAdapter {
    private val vaultId = deriveAccountVaultId(sessionId.value, deviceId.value)
    private val accountHash = accountIdHash(sessionId.value)
    private val matrixDeviceHash = accountIdHash(deviceId.value)
    private val roomHash = accountIdHash(roomId.value)
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val secureRandom = SecureRandom()
    private data class ActiveBurnSession(
        val session: BurnAfterReadSession,
        val eventIdentity: String,
        val checkpointKey: BurnConsumptionKey,
        val viewOperationId: String,
        val operationDeadlineEpochMillis: Long,
        val expiryJob: Job,
    )
    private val burnSessions = linkedMapOf<ContentId, ActiveBurnSession>()
    private val timelineReleaseScopeOwners = TimelineReleaseScopeOwnerRegistry()

    override fun accountScopeId(): String = accountHash

    override fun roomScopeId(): String = roomId.value

    override fun captionLineageWiring(): ProtectedCaptionLineageWiring = captionLineageCoordinator

    override suspend fun authenticatedCaptionTarget(item: MatrixTimelineItem.Event): ProtectedCaptionTarget {
        val eventId = requireNotNull(item.eventId?.value)
        require(item.event.isOwn) { "Caption edits require an owned synced event." }
        val message = item.event.content as? MessageContent
            ?: throw SecurityException("Caption target is not a protected message.")
        val carrier = (message.type as? MessageTypeWithAttachment)?.caption
            ?.takeIf(String::isProtectedCaptionCarrier)
            ?: throw SecurityException("Caption target has no protected caption envelope.")
        val binding = attachmentDecryptionCacheKey(eventId, message, carrier)
        decryptCaption(carrier) // KMS release authenticates this exact envelope before its header is trusted.
        require(binding.expiresAtEpochMillis > clock.epochMillis())
        return ProtectedCaptionTarget(binding.contentId, binding.expiresAtEpochMillis)
    }

    init {
        coroutineScope.launch {
            try {
                captionLineageCoordinator.reconcileLocalCommits()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_CAPTION_LOCAL_COMMIT_RETRY")
            }
        }
    }

    override suspend fun confirmMatrixRedaction(eventId: EventId): Boolean =
        contentProtectionService.confirmMatrixRedaction(roomId.value, eventId.value)

    init {
        runtime.registerSensitiveCacheClearer {
            // Successful transport work contains only hashes and must survive plaintext invalidation.
            // Every protected read still rechecks current authorization after the sensitive cache clears.
            lifecycleWork.clearOriginatingOperations()
            val sessions = synchronized(burnSessions) {
                burnSessions.values.toList().also { burnSessions.clear() }
            }
            sessions.forEach { active ->
                active.expiryJob.cancel()
                coroutineScope.launch {
                    runCatching { endBurnSession(active, BurnAfterReadEndReason.APP_LOCKED) }
                }
            }
        }
        coroutineScope.launch {
            runCatching { recoverPendingBurnConsumptions() }
                .onFailure { failure ->
                    Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w(
                        "YZCP_BURN_RECOVERY_PENDING code=%s",
                        (failure as? Exception)?.diagnosticCode() ?: "INTERNAL_FAILURE",
                    )
                }
        }
    }

    override fun authorizationRevision(): Long = runtime.authorizationRevision()

    override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable =
        runtime.registerAuthorizationRefreshListener(listener)

    override fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable =
        runtime.registerContentInvalidationListener(listener)

    override suspend fun enabled(): Boolean {
        val policyRequiresProtection = policyProvider.current().policy.requireContentEnvelope
        return policyRequiresProtection || featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
    }

    override suspend fun protectText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = protectTextVersionRoot(
        ContentKind.TEXT,
        ProtectedMatrixPayload(
            payloadType = PAYLOAD_TEXT,
            body = body,
            htmlBody = htmlBody,
            messageType = msgType.name,
            asPlainText = asPlainText,
        ),
        contentId,
        lifecycle = lifecycle,
    )

    override suspend fun protectReplyText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        repliedToEventId: EventId,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = protectTextVersionRoot(
        ContentKind.TEXT,
        ProtectedMatrixPayload(
            payloadType = PAYLOAD_TEXT,
            body = body,
            htmlBody = htmlBody,
            messageType = msgType.name,
            asPlainText = asPlainText,
            targetEventId = repliedToEventId.value,
        ),
        contentId,
        lifecycle = lifecycle,
    )

    private suspend fun protectTextVersionRoot(kind: ContentKind, payload: ProtectedMatrixPayload, contentId: ContentId?,
        lifecycle: ContentLifecycle): PreparedProtectedContent {
        if (currentLifecycleOperationContext()?.lifecycleId != FullAppLifecycleId.LC_13) {
            return protect(kind, payload, contentId, lifecycle = lifecycle)
        }
        val root = captionLineageCoordinator.prepareRoot(contentId)
        return try { protect(kind, payload, root, lifecycle = lifecycle) }
        catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { captionLineageCoordinator.protectionOutcomeUnknown(root) }.onFailure(failure::addSuppressed) }
            throw failure
        }
    }

    override suspend fun protectTextEdit(item: MatrixTimelineItem.Event, body: String, htmlBody: String?): PreparedProtectedContent {
        require(item.event.isOwn)
        val eventId = requireNotNull(item.eventId?.value)
        val message = item.event.content as? MessageContent ?: throw SecurityException("TEXT target unavailable.")
        require(protectedContentKind(message) == ContentKind.TEXT && !message.isBurnAfterRead)
        val binding = decryptionCacheKey(eventId, message)
        val plan = captionLineageCoordinator.prepareText(eventId, binding.contentId, binding.expiresAtEpochMillis)
        return try {
            protect(ContentKind.TEXT, ProtectedMatrixPayload(payloadType = PAYLOAD_TEXT, body = body, htmlBody = htmlBody,
                messageType = MsgType.MSG_TYPE_TEXT.name, asPlainText = false), plan.childContentId)
        } catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { captionLineageCoordinator.protectionOutcomeUnknown(plan.childContentId) }
                .onFailure(failure::addSuppressed) }
            throw failure
        }
    }

    override suspend fun protectBurnAfterReadText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
    ): PreparedProtectedContent {
        return protectText(
            body = body,
            htmlBody = htmlBody,
            msgType = msgType,
            asPlainText = asPlainText,
            contentId = null,
            lifecycle = burnAfterReadLifecycle(),
        )
    }

    override suspend fun burnAfterReadLifecycle(): ContentLifecycle {
        val audienceSize = (activeMemberAccountIdHashes() + accountHash).size
        require(audienceSize >= 2)
        val conversationKind = if (audienceSize == 2) ConversationKind.DIRECT else ConversationKind.GROUP
        return ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, conversationKind)
    }

    override suspend fun protectLocation(
        body: String,
        geoUri: String,
        description: String?,
        assetType: AssetType?,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = protect(
        ContentKind.LOCATION,
        ProtectedMatrixPayload(
            payloadType = PAYLOAD_LOCATION,
            body = body,
            geoUri = geoUri,
            description = description,
            assetType = assetType?.name,
        ),
        contentId,
        lifecycle = lifecycle,
    )

    override suspend fun protectInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>?,
        contentId: ContentId?,
    ): PreparedProtectedContent {
        require(kind in INTERACTION_CONTENT_KINDS)
        if (kind in setOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION)) {
            require(audienceAccountIdHashes == null)
            val expected = liveLocationOrigin(kind)
            require(contentId == null || contentId.value == expected.contentId)
            val scope = io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value)
            val intentHash = relayEditIntentHash(payload)
            return protectedSendOriginStore.withPreparation(scope, expected.contentId) {
                // A prepared/unresolved producer remains owned by its original queue.
                // This caller cannot re-protect or re-enqueue it after response loss.
                val previous = protectedSendOriginStore.find(scope, expected.contentId)
                if (previous != null) {
                    val reservation = checkNotNull(protectedSendOriginStore.reserved(scope, expected.contentId))
                    require(reservation.origin == expected && reservation.intentSha256 == intentHash &&
                        reservation.preparationStarted == false) { "Original live producer requires reconciliation" }
                } else protectedSendOriginStore.reservePreparation(scope, expected, intentHash)
                protect(kind, payload, ContentId(expected.contentId), durableIntentSha256 = intentHash,
                    beforeKmsStore = { protectedSendOriginStore.markPreparationStarted(scope, expected, intentHash) })
            }
        }
        if (payload.operation == "RELAY_EDIT") {
            require(kind == ContentKind.RELAY_OPERATION && audienceAccountIdHashes == null)
            val expected = relayEditOrigin(payload)
            require(contentId == null || contentId.value == expected.contentId)
            val intentHash = relayEditIntentHash(payload)
            return prepareRelayEdit(
                protectedSendOriginStore,
                io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value),
                expected, intentHash,
                authorizeExisting = { carrier -> authorizePreparedRelayEdit(carrier, payload, intentHash) },
                protectFresh = { stableId, beforeStore -> protect(kind, payload, stableId,
                    durableIntentSha256 = intentHash, beforeKmsStore = beforeStore) },
                reconcileOriginal = {
                    val original = appOperationalStateCoordinator.reconcileRelayPreparation(
                        io.element.android.security.dataprotection.api.ProtectedRelayPreparationQuery(
                            accountHash, matrixDeviceHash, roomHash, accountIdHash(checkNotNull(payload.targetEventId)),
                            expected.operationId, roomId.value, expected.contentId,
                        ),
                    )
                    check(!original.status.isTerminal) { "Original relay edit has a terminal outcome." }
                },
            )
        }
        return protect(kind, payload, contentId, audienceAccountIdHashes)
    }

    override fun releaseLiveLocationProducer() {
        pendingPayloadCache.clearLiveLocation(accountHash, roomId.value)
    }

    private suspend fun liveLocationOrigin(kind: ContentKind): ProtectedEventOwnershipResolver.Origin {
        require(kind in setOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION))
        val context = checkNotNull(currentLifecycleOperationContext())
        require(context.lifecycleId == FullAppLifecycleId.LC_17)
        return ProtectedEventOwnershipResolver.Origin(context.operationId, context.lifecycleId,
            accountHash, matrixDeviceHash, deviceTrustService.current().installationId, roomHash, null, null,
            UUID.nameUUIDFromBytes("live-location-producer-v1\u0000${roomId.value}\u0000${context.operationId}".encodeToByteArray()).toString(),
            kind, "")
    }

    override suspend fun verifyLiveLocationIntent(kind: ContentKind, payload: ProtectedMatrixPayload): Boolean {
        val expected = liveLocationOrigin(kind)
        val retained = protectedSendOriginStore.prepared(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), expected.contentId,
        ) ?: return false
        retained.requireLiveLocationIntent(expected, relayEditIntentHash(payload))
        require(retained.origin.carrierSha256 == accountIdHash(retained.encodedEnvelope))
        if (retained.origin.eventId?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) != true) return false
        val admitted = appOperationalStateCoordinator.operation(expected.operationId)
        require(admitted.operationId == expected.operationId && admitted.lifecycleId == FullAppLifecycleId.LC_17 &&
            admitted.subjects.accountIdHash == accountHash && admitted.subjects.matrixDeviceIdHash == matrixDeviceHash &&
            admitted.subjects.installationId == expected.installationId && admitted.subjects.roomIdHash == roomHash)
        val accepted = admitted.steps.filter { it.component.name in setOf("MATRIX", "DATABASE", "KMS") }
        return accepted.size == 3 && accepted.all {
            it.status.name == "COMPLETE" && !it.authorityRevision.isNullOrBlank() && !it.receiptReference.isNullOrBlank()
        }
    }

    override suspend fun verifyRelayEditIntent(payload: ProtectedMatrixPayload): Boolean {
        require(protectedPreviewAuthorizationGate.canAttemptPreview())
        val expected = relayEditOrigin(payload)
        val retained = protectedSendOriginStore.prepared(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), expected.contentId,
        ) ?: return false
        retained.requireRelayEditIntent(expected, relayEditIntentHash(payload))
        require(retained.origin.eventId?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) == true)
        return true
    }

    override suspend fun retainRelayEditRecovery(payload: ProtectedMatrixPayload) {
        require(protectedPreviewAuthorizationGate.canAttemptPreview())
        val origin = relayEditOrigin(payload)
        protectedSendOriginStore.rememberRelayEditRecovery(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value),
            checkNotNull(payload.targetEventId), origin, json.encodeToString(payload),
        )
    }

    override suspend fun pendingRelayEditRecovery(targetEventId: String): io.element.android.libraries.matrix.api.timeline.RelayEditRecovery? {
        require(protectedPreviewAuthorizationGate.canAttemptPreview())
        val retained = protectedSendOriginStore.pendingRelayEditRecovery(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), targetEventId,
        ) ?: return null
        val origin = retained.origin
        require(origin.accountIdHash == accountHash && origin.matrixDeviceIdHash == matrixDeviceHash &&
            origin.installationId == deviceTrustService.current().installationId && origin.roomIdHash == roomHash &&
            origin.lifecycleId == FullAppLifecycleId.LC_15 && origin.contentKind == ContentKind.RELAY_OPERATION &&
            origin.contentId == relayEditPreparedContentId(roomId.value, origin.operationId).value)
        val payload = json.decodeFromString<ProtectedMatrixPayload>(retained.encodedIntent)
        require(payload.operation == "RELAY_EDIT" && payload.operationId == origin.operationId &&
            payload.targetEventId == targetEventId && payload.aggregateId == targetEventId && payload.payloadType == "OPERATION")
        return io.element.android.libraries.matrix.api.timeline.RelayEditRecovery(origin.operationId, targetEventId,
            checkNotNull(payload.relayEntryId), checkNotNull(payload.relayExpectedRevision), checkNotNull(payload.relayEntry))
    }

    override suspend fun resolveRelayEditRecovery(targetEventId: String, operationId: String) {
        val pending = pendingRelayEditRecovery(targetEventId) ?: return
        require(pending.operationId == operationId)
        protectedSendOriginStore.resolveRelayEditRecovery(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), targetEventId, operationId,
        )
    }

    private suspend fun relayEditOrigin(payload: ProtectedMatrixPayload): ProtectedEventOwnershipResolver.Origin {
        require(payload.operation == "RELAY_EDIT" && payload.payloadType == "OPERATION")
        require(payload.targetEventId == payload.aggregateId && !payload.targetEventId.isNullOrBlank())
        val operation = currentLifecycleOperationContext() ?: throw ContentProtectionException.KmsUnavailable()
        require(operation.lifecycleId == FullAppLifecycleId.LC_15 && payload.operationId == operation.operationId)
        return ProtectedEventOwnershipResolver.Origin(
            operation.operationId, operation.lifecycleId, accountHash, matrixDeviceHash,
            deviceTrustService.current().installationId, roomHash, null, null,
            relayEditPreparedContentId(roomId.value, operation.operationId).value, ContentKind.RELAY_OPERATION, "",
        )
    }

    private fun relayEditIntentHash(payload: ProtectedMatrixPayload): String {
        val encoded = json.encodeToString(payload).encodeToByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(encoded).toHex() } finally { encoded.fill(0) }
    }

    private suspend fun authorizePreparedRelayEdit(carrier: String, payload: ProtectedMatrixPayload, intentHash: String) {
        if (!protectedPreviewAuthorizationGate.canAttemptPreview()) throw ContentProtectionException.AccessDenied()
        val origin = relayEditOrigin(payload)
        val retained = checkNotNull(protectedSendOriginStore.prepared(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), origin.contentId,
        ))
        retained.requireRelayEditIntent(origin, intentHash)
        require(retained.encodedEnvelope == carrier)
        val encoded = carrier.decodeEnvelope()
        val envelope = try { envelopeCodec.decode(encoded) } finally { encoded.fill(0) }
        try {
            require(envelope.header.kind == ContentKind.RELAY_OPERATION && envelope.header.contentId.value == origin.contentId &&
                envelope.header.lifecycle.mode == MessageLifecycleMode.FOLLOW_ROOM)
            // The authenticated Vault journal binds the immutable intent and exact sealed carrier.
            // Retransmission releases no key: PREPARED content remains unreadable at the KMS release endpoint.
            appOperationalStateCoordinator.authorizePreparedRelayRetry(
                io.element.android.security.dataprotection.api.ProtectedEventOriginQuery(
                    accountHash, matrixDeviceHash, roomHash, accountIdHash(checkNotNull(payload.targetEventId)),
                    MessageDigest.getInstance("SHA-256").digest(carrier.encodeToByteArray()).toHex(),
                    envelope.header.immutableContextSha256.toHex(), envelope.header.contentId.value,
                    envelope.header.kind.name, envelope.header.kmsShareReference.value, envelope.header.roomEpoch?.generation,
                ), origin.operationId, roomId.value,
            )
        } finally { envelope.ciphertext.fill(0) }
    }

    override suspend fun protectMedia(file: File, kind: ContentKind, lifecycle: ContentLifecycle): ProtectedMatrixMedia {
        val request = protectionRequest(kind, lifecycle = lifecycle)
        return try {
            protectedMediaService.protect(file, request)
                .also { rememberOriginatingOperation(it.contentId) }
        } finally {
            request.recipients.forEach { it.publicKeyDer.fill(0) }
        }
    }

    override suspend fun protectMedia(
        bytes: ByteArray,
        workingDirectory: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle,
    ): ProtectedMatrixMedia {
        val request = protectionRequest(kind, lifecycle = lifecycle)
        return try {
            protectedMediaService.protect(bytes, workingDirectory, request)
                .also { rememberOriginatingOperation(it.contentId) }
        } finally {
            request.recipients.forEach { it.publicKeyDer.fill(0) }
        }
    }

    override suspend fun protectMedia(
        source: InputStream,
        workingDirectory: File,
        kind: ContentKind,
        lifecycle: ContentLifecycle,
    ): ProtectedMatrixMedia {
        val request = protectionRequest(kind, lifecycle = lifecycle)
        return try {
            protectedMediaService.protect(source, workingDirectory, request)
                .also { rememberOriginatingOperation(it.contentId) }
        } finally {
            request.recipients.forEach { it.publicKeyDer.fill(0) }
        }
    }

    override suspend fun protectCaption(
        caption: String?,
        formattedCaption: String?,
        lifecycle: ContentLifecycle,
        attachmentMetadata: ProtectedAttachmentMetadata?,
        mediaAlbum: ProtectedMediaAlbum?,
    ): PreparedProtectedContent {
        val payload = ProtectedMatrixPayload(
            version = if (attachmentMetadata == null) 1 else 2,
            payloadType = PAYLOAD_CAPTION,
            caption = caption,
            formattedCaption = formattedCaption,
            attachmentName = attachmentMetadata?.validated()?.displayName,
            attachmentMimeType = attachmentMetadata?.mimeType,
            attachmentSize = attachmentMetadata?.size,
            aggregateId = mediaAlbum?.validated()?.id,
            operation = mediaAlbum?.let { MEDIA_ALBUM_OPERATION },
            operationId = mediaAlbum?.let { "${it.index}/${it.count}" },
        )
        val contentId = captionLineageCoordinator.prepareRoot()
        return try {
            protect(ContentKind.MEDIA_CAPTION, payload, contentId = contentId, lifecycle = lifecycle)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                runCatching { captionLineageCoordinator.protectionOutcomeUnknown(contentId) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    override suspend fun decryptCaption(encodedEnvelope: String): ProtectedCaption {
        return decryptCaption(encodedEnvelope, timelineReleaseIntent = null)
    }

    private suspend fun decryptCaption(
        encodedEnvelope: String,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
    ): ProtectedCaption {
        val encoded = encodedEnvelope.removePrefix(PROTECTED_CAPTION_PREFIX).decodeEnvelope()
        val envelope = try {
            envelopeCodec.decode(encoded)
        } finally {
            encoded.fill(0)
        }
        require(envelope.header.kind == ContentKind.MEDIA_CAPTION)
        val trust = deviceTrustService.current()
        val burnSessionId = if (envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
            synchronized(burnSessions) { burnSessions[envelope.header.contentId]?.session?.sessionId }
                ?: throw ContentProtectionException.AccessDenied()
        } else {
            null
        }
        val plaintext = try {
            contentProtectionService.decryptBytes(
                ReleaseContentRequest(
                    header = envelope.header,
                    vaultId = vaultId,
                    accountIdHash = accountHash,
                    roomId = roomId.value,
                    requestingInstallationId = trust.installationId,
                    requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                    burnSessionId = burnSessionId,
                    timelineReleaseIntent = timelineReleaseIntent?.let(::registeredTimelineReleaseIntent),
                ),
                envelope.ciphertext,
            )
        } finally {
            envelope.ciphertext.fill(0)
        }
        return try {
            decodeCaptionPayload(json.decodeFromString(ProtectedMatrixPayload.serializer(), plaintext.decodeToString()))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decodeCaptionPayload(payload: ProtectedMatrixPayload): ProtectedCaption {
        require(payload.payloadType == PAYLOAD_CAPTION)
        require(payload.version in 1..2)
        val hasAttachmentMetadata = payload.attachmentName != null ||
            payload.attachmentMimeType != null ||
            payload.attachmentSize != null
        require(!hasAttachmentMetadata || payload.version == 2)
        val attachmentMetadata = payload.attachmentName?.let { displayName ->
            ProtectedAttachmentMetadata(
                displayName = displayName,
                mimeType = payload.attachmentMimeType,
                size = requireNotNull(payload.attachmentSize),
            ).validated()
        }
        require(!hasAttachmentMetadata || attachmentMetadata != null)
        val mediaAlbum = if (payload.operation == MEDIA_ALBUM_OPERATION) {
            val (index, count) = requireNotNull(payload.operationId)
                .split('/', limit = 2)
                .takeIf { it.size == 2 }
                ?.map(String::toInt)
                ?: throw SecurityException("Protected media album position is invalid.")
            ProtectedMediaAlbum(
                id = requireNotNull(payload.aggregateId),
                index = index,
                count = count,
            ).validated()
        } else {
            require(payload.operation == null)
            null
        }
        return ProtectedCaption(payload.caption, payload.formattedCaption, attachmentMetadata, mediaAlbum)
    }


    override suspend fun destroyMedia(media: ProtectedMatrixMedia, reason: ContentAccessDestroyReason): Boolean =
        contentProtectionService.destroyContentAccess(vaultId, media.contentId, reason)

    override suspend fun destroyPrepared(content: PreparedProtectedContent, reason: ContentAccessDestroyReason): Boolean =
        withContext(NonCancellable) {
            try {
                destroyContentIdsRetainingLineageDebt(
                    contentIds = setOf(content.contentId),
                    markDestructionPending = captionLineageCoordinator::markDestructionPending,
                    destroyContentAccess = { contentId ->
                        contentProtectionService.destroyContentAccess(vaultId, contentId, reason)
                    },
                )
            } finally {
                try {
                    pendingPayloadCache.remove(content.contentId)
                } finally {
                    decryptionCache.clear()
                }
            }
        }

    override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem =
        decryptItemInternal(item, timelineReleaseIntent = null)

    override suspend fun decryptItem(
        item: MatrixTimelineItem,
        timelineReleaseIntent: TimelineContentReleaseIntent,
    ): MatrixTimelineItem = decryptItemInternal(item, timelineReleaseIntent)

    private suspend fun decryptItemInternal(
        item: MatrixTimelineItem,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
    ): MatrixTimelineItem {
        val eventItem = item as? MatrixTimelineItem.Event ?: return item
        val content = eventItem.event.content
        val policy = policyProvider.current().policy
        val contentProtectionFeatureEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
        if (!policy.requireContentEnvelope && !contentProtectionFeatureEnabled) {
            return if (content is MessageContent) {
                eventItem.copy(event = eventItem.event.copy(content = content.withExplicitUnprotectedClipboardSource()))
            } else {
                item
            }
        }
        return when (content) {
            is MessageContent -> if (protectedPreviewAuthorizationGate.canAttemptPreview()) {
                decryptOrRejectMessage(
                    eventItem = eventItem,
                    message = content,
                    policyRequiresProtection = policy.requireContentEnvelope,
                    contentProtectionFeatureEnabled = contentProtectionFeatureEnabled,
                    timelineReleaseIntent = timelineReleaseIntent,
                )
            } else {
                statusItem(eventItem, content.threadInfo, protectedPreviewAuthorizationGate.accessState.value.deniedDisplayState())
            }
            is PollContent -> if (policy.requireContentEnvelope) legacyBlockedItem(eventItem, content.threadInfo) else item
            is StickerContent -> if (policy.requireContentEnvelope) legacyBlockedItem(eventItem, content.threadInfo) else item
            is LiveLocationContent -> if (
                !policy.requireContentEnvelope || allowEncryptedLiveLocationProjection(content, isRoomEncrypted())
            ) {
                item
            } else {
                legacyBlockedItem(eventItem, threadInfo = null)
            }
            is CallNotifyContent, LegacyCallInviteContent ->
                if (!policy.requireContentEnvelope ||
                    policy.allowRtc
                ) {
                    item
                } else {
                    accessDeniedItem(eventItem, threadInfo = null)
                }
            else -> item
        }
    }

    override fun cancelTimelineReleaseRequests(scopeId: String, requestIds: Set<String>) {
        val registration = timelineReleaseScopeOwners.resolve(scopeId) ?: return
        contentProtectionService.cancelTimelineReleaseRequests(
            registration.qualifiedScopeId,
            requestIds,
            registration.ownerToken,
        )
    }

    override fun updateTimelineReleaseViewport(scopeId: String, generation: Long, visibleRequestIds: List<String>) {
        val registration = timelineReleaseScopeOwners.resolve(scopeId) ?: return
        contentProtectionService.updateTimelineReleaseViewport(
            scopeId = registration.qualifiedScopeId,
            generation = generation,
            visibleRequestIds = visibleRequestIds,
            ownerToken = registration.ownerToken,
        )
    }

    override fun openTimelineReleaseScope(scopeId: String): String {
        val qualifiedScopeId = timelineReleaseScopeId(scopeId)
        val ownerToken = requireNotNull(contentProtectionService.openTimelineReleaseScope(qualifiedScopeId)) {
            "Content protection service did not register the timeline scope owner."
        }
        val handle = timelineReleaseScopeOwners.register(scopeId, qualifiedScopeId, ownerToken)
        decryptionCache.openObservedCarrierScope(sessionId.value, deviceId.value, roomId.value, scopeId, handle)
        return handle
    }

    override fun closeTimelineReleaseScope(scopeId: String) {
        val registration = timelineReleaseScopeOwners.remove(scopeId) ?: return
        decryptionCache.removeObservedCarrierScope(sessionId.value, deviceId.value, roomId.value, scopeId)
        contentProtectionService.closeTimelineReleaseScope(registration.qualifiedScopeId, registration.ownerToken)
    }

    override fun protectRecentTimelineCache(items: List<MatrixTimelineItem>) {
        decryptionCache.protectRecentEvents(
            sessionId.value,
            roomId.value,
            items.mapNotNull { (it as? MatrixTimelineItem.Event)?.protectedEventIdentity() },
        )
    }

    private suspend fun carrierScope(handle: String) = ProtectedTimelineDecryptionCache.CarrierScope(
        sessionId.value, deviceId.value, deviceTrustService.current().installationId, roomId.value, handle,
    )

    override suspend fun updateRoomSummaryCarrier(scopeId: String, latestEvent: MatrixTimelineItem.Event?) {
        if (timelineReleaseScopeOwners.resolve(scopeId) == null) return
        val message = latestEvent?.event?.content as? MessageContent
        val header = message?.let { runCatching { protectedHeader(it) }.getOrNull() }
        val caption = (message?.type as? MessageTypeWithAttachment)?.caption?.takeIf(String::isProtectedCaptionCarrier)
        val eligible = latestEvent?.event?.isRemote == true && latestEvent.eventId != null &&
            message != null && (header != null || caption != null) && !message.isBurnAfterRead &&
            header?.lifecycle?.mode != MessageLifecycleMode.BURN_AFTER_READ
        val carrier = if (eligible) {
            runCatching { ProtectedTimelineDecryptionCache.ObservedCarrier(
                if (caption != null) attachmentDecryptionCacheKey(requireNotNull(latestEvent?.eventId?.value), message, caption)
                else decryptionCacheKey(requireNotNull(latestEvent?.eventId?.value), message),
                latestEvent.event.timestamp, latestEvent.event.sender.value, message, authorizationRevision(),
            ) }.getOrNull()
        } else {
            null
        }
        if (carrier == null) {
            // Non-text/terminal/local/BAR carriers need only clear metadata, not another hardware identity check.
            decryptionCache.clearObservedCarrierValue(sessionId.value, deviceId.value, roomId.value, scopeId)
        } else {
            val currentScope = optionalCarrierIdentity { carrierScope(scopeId) }
            if (currentScope != null) {
                decryptionCache.updateObservedCarrier(currentScope, carrier)
            } else {
                decryptionCache.clearObservedCarrierValue(sessionId.value, deviceId.value, roomId.value, scopeId)
            }
        }
    }

    override suspend fun observedRoomSummaryEventIdentity(timestamp: Long, sender: String, message: MessageContent): String? {
        // Identity metadata is shared for all protected carriers; it does not grant plaintext access.
        if (message.isBurnAfterRead) return null
        val currentScope = optionalCarrierIdentity { carrierScope("summary-lookup") } ?: return null
        return decryptionCache.observedEventIdentity(currentScope, timestamp, sender, message)?.also {
            LifecycleStageTiming.point("room_summary_identity_bound", it)
        }
    }

    override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem): MatrixTimelineItem? {
        val eventItem = item as? MatrixTimelineItem.Event ?: return null
        val message = eventItem.event.content as? MessageContent ?: return null
        val policy = policyProvider.current().policy
        val contentProtectionFeatureEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
        if (!policy.requireContentEnvelope && !contentProtectionFeatureEnabled) return null
        if (!protectedPreviewAuthorizationGate.canAttemptPreview()) return null
        if (burnAfterReadContentIds(message).isNotEmpty()) return null
        val eventIdentity = eventItem.protectedEventIdentity()
        val cacheKey = when (
            classifyMatrixMessage(message, policy.requireContentEnvelope, contentProtectionFeatureEnabled)
        ) {
            StrictMatrixMessageDecision.DecryptEnvelope ->
                runCatching { decryptionCacheKey(eventIdentity, message) }.getOrNull()
            is StrictMatrixMessageDecision.AllowProtectedAttachment -> {
                val caption = (message.type as? MessageTypeWithAttachment)?.caption
                    ?.takeIf(String::isProtectedCaptionCarrier)
                    ?: return null
                runCatching { attachmentDecryptionCacheKey(eventIdentity, message, caption) }.getOrNull()
            }
            else -> null
        } ?: return null
        val authorizationRevision = authorizationRevision()
        return decryptionCache.get(cacheKey)?.let { cachedContent ->
            eventItem.withCachedProtectedContent(
                cachedContent.withProtectedClipboardSource(cacheKey, authorizationRevision, eventItem.eventId?.value)
            )
        }
            ?: decryptionCache.getStableRejection(cacheKey)?.let { state ->
                statusItem(eventItem, message.threadInfo, state)
            }
            ?: decryptionCache.getAuthorizationRejection(cacheKey, authorizationRevision)?.let { state ->
                statusItem(eventItem, message.threadInfo, state)
            }
    }

    override suspend fun pendingOriginalSendProjection(source: MatrixTimelineItem, replacement: MatrixTimelineItem): MatrixTimelineItem? {
        val original = source as? MatrixTimelineItem.Event ?: return null
        val confirmed = replacement as? MatrixTimelineItem.Event ?: return null
        if (!original.event.isOwn || !confirmed.event.isOwn || original.eventId != null ||
            original.transactionId == null || confirmed.eventId?.value?.let(ProtectedEventOwnershipResolver::isCanonicalEvent) != true ||
            original.uniqueId != confirmed.uniqueId || original.event.sender != confirmed.event.sender ||
            (confirmed.transactionId != null && confirmed.transactionId != original.transactionId)) return null
        val message = original.event.content as? MessageContent ?: return null
        val replacementMessage = confirmed.event.content as? MessageContent ?: return null
        val carrier = message.protectedEnvelopeBody() ?: return null
        if (replacementMessage.protectedEnvelopeBody() != carrier) return null
        val header = runCatching { protectedHeader(message) }.getOrNull() ?: return null
        // Only the visible LC-15 relay root has this handoff. Follow-up operations, burn content,
        // incoming events and edited carriers continue through their existing fail-closed path.
        if (header.kind != ContentKind.RELAY || header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) return null
        val revision = authorizationRevision()
        val previewRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
        val origin = protectedSendOriginStore.find(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), header.contentId.value,
        ) ?: return null
        if (origin.eventId != null && origin.eventId != confirmed.eventId?.value) return null
        val trust = deviceTrustService.current()
        val input = ProtectedEventOwnershipResolver.Input(accountHash, matrixDeviceHash, trust.installationId,
            accountIdHash(original.event.sender.value), header.senderInstallationId, roomHash, null,
            original.transactionId?.value, header.contentId.value, header.kind, accountIdHash(carrier), header.roomEpoch?.generation)
        val owner = ProtectedEventOwnershipResolver.resolve(input, origin).sendContext ?: return null
        if (owner.lifecycleId != FullAppLifecycleId.LC_15) return null
        val encoded = carrier.decodeEnvelope()
        val envelope = try { envelopeCodec.decode(encoded) } finally { encoded.fill(0) }
        try {
            val ticket = pendingPayloadCache.ownership(envelope.pendingPayloadKey(), PendingProtectedPayloadCache.Identity(
                matrixDeviceHash, trust.installationId, accountIdHash(carrier), revision, previewRevision,
            )) ?: return null
            if (ticket.owner.operation.operationId != owner.operationId ||
                ticket.owner.operation.lifecycleId != owner.lifecycleId || !pendingPayloadCache.isCurrent(ticket)) return null
            val cached = cachedProjectionForCurrentAuthorization(original) ?: return null
            if (authorizationRevision() != revision || protectedPreviewAuthorizationGate.authorizationRevision.value != previewRevision ||
                !protectedPreviewAuthorizationGate.canAttemptPreview() || !pendingPayloadCache.isCurrent(ticket)) return null
            // Keep the original transaction identity and sending state. The async worker alone may
            // publish canonical metadata after exact event binding and fresh authorization succeed.
            val pending = cached as? MatrixTimelineItem.Event ?: return null
            if ((pending.event.content as? MessageContent)?.isContentProtectionAuthorized != true) return null
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_RELAY_ORIGINAL_SEND_PENDING")
            return pending.copy(event = pending.event.copy(localSendState = LocalEventSendState.Sending.Event))
        } finally { envelope.ciphertext.fill(0) }
    }

    override suspend fun openBurnAfterRead(item: MatrixTimelineItem): MatrixTimelineItem {
        val eventItem = item as? MatrixTimelineItem.Event ?: return item
        val message = eventItem.event.content as? MessageContent ?: return item
        val eventIdentity = eventItem.protectedEventIdentity()
        val contentIds = burnAfterReadContentIds(message)
        require(contentIds.isNotEmpty()) { "This Matrix event is not a burn-after-read message." }
        val openedNow = mutableListOf<ActiveBurnSession>()
        return try {
            contentIds.forEach { contentId ->
                if (synchronized(burnSessions) { burnSessions[contentId] } == null) {
                    openedNow += beginBurnSession(contentId, eventIdentity)
                }
            }
            decryptionCache.remove(roomId.value, eventIdentity)
            val authorizedItem = decryptOrRejectMessage(
                eventItem,
                message,
                policyProvider.current().policy.requireContentEnvelope,
                featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection),
            )
            val authorizedContent = (authorizedItem as? MatrixTimelineItem.Event)?.event?.content as? MessageContent
            check(authorizedContent?.isContentProtectionAuthorized == true) {
                "Burn-after-read authorization did not produce readable content."
            }
            authorizedItem
        } catch (failure: Exception) {
            openedNow.forEach { active ->
                runCatching { endBurnSession(active, BurnAfterReadEndReason.VIEW_CLOSED) }
            }
            throw failure
        }
    }

    override suspend fun endBurnAfterRead(item: MatrixTimelineItem, reason: BurnAfterReadEndReason): Boolean {
        val message = ((item as? MatrixTimelineItem.Event)?.event?.content as? MessageContent) ?: return false
        val contentIds = burnAfterReadContentIds(message)
        val sessions = contentIds.mapNotNull { contentId ->
            synchronized(burnSessions) { burnSessions[contentId] }
        }
        var firstFailure: Throwable? = null
        sessions.forEach { active ->
            runCatching { endBurnSession(active, reason) }
                .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
        }
        val activeContentIds = sessions.mapTo(mutableSetOf()) { it.session.contentId.value }
        val pending = burnConsumptionStore.pending().filter { checkpoint ->
            checkpoint.key.accountIdHash == accountHash &&
                checkpoint.key.roomIdHash == roomHash &&
                checkpoint.key.contentId in contentIds.map(ContentId::value) &&
                checkpoint.key.contentId !in activeContentIds &&
                checkpoint.state == BurnConsumptionState.END_PENDING
        }
        pending.forEach { checkpoint ->
            runCatching { finishPendingBurnConsumption(checkpoint, reason) }
                .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
        }
        if (pending.isNotEmpty()) decryptionCache.clear()
        firstFailure?.let { throw it }
        return sessions.isNotEmpty() || pending.isNotEmpty()
    }

    override suspend fun isBurnAfterRead(item: MatrixTimelineItem): Boolean {
        val message = ((item as? MatrixTimelineItem.Event)?.event?.content as? MessageContent) ?: return false
        return burnAfterReadContentIds(message).isNotEmpty()
    }

    override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> {
        val policy = policyProvider.current().policy
        val contentProtectionFeatureEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
        if (!policy.requireContentEnvelope && !contentProtectionFeatureEnabled) return items
        // Queue the complete own-event binding batch before any decrypt worker can wait on one
        // PREPARED binding. This changes ordering only; it neither exposes plaintext nor bypasses
        // the server-side room/event/content verification required to make a binding ACTIVE.
        prefetchConfirmedOwnMessageEvents(items, ::scheduleLifecycleRegistration)
        return items.map { item ->
            val eventItem = item as? MatrixTimelineItem.Event ?: return@map item
            when (val content = eventItem.event.content) {
                is MessageContent -> {
                    when (
                        val decision = classifyMatrixMessage(content, policy.requireContentEnvelope, contentProtectionFeatureEnabled)
                    ) {
                        null -> eventItem
                        StrictMatrixMessageDecision.DecryptEnvelope -> if (protectedContentKind(content) in OPERATION_CONTENT_KINDS) {
                            hiddenOperationItem(eventItem)
                        } else if (protectedHeader(content)?.lifecycle?.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                            statusItem(eventItem, content.threadInfo, ProtectedMessageDisplayState.BURN_AFTER_READ_UNOPENED)
                        } else {
                            statusItem(eventItem, content.threadInfo, checkNotNull(decision.shellDisplayState()))
                        }
                        is StrictMatrixMessageDecision.AllowProtectedAttachment -> if (burnAfterReadContentIds(content).isNotEmpty()) {
                            statusItem(eventItem, content.threadInfo, ProtectedMessageDisplayState.BURN_AFTER_READ_UNOPENED)
                        } else if (content.hasProtectedCaptionCarrier()) {
                            statusItem(eventItem, content.threadInfo, ProtectedMessageDisplayState.ATTACHMENT_DECRYPTING)
                        } else {
                            eventItem.copy(
                                event = eventItem.event.copy(
                                    content = decision.content.authorizeProtectedAttachmentProjection()
                                )
                            )
                        }
                        StrictMatrixMessageDecision.Block ->
                            statusItem(eventItem, content.threadInfo, checkNotNull(decision.shellDisplayState()))
                    }
                }
                is PollContent -> if (policy.requireContentEnvelope) legacyBlockedItem(eventItem, content.threadInfo) else item
                is StickerContent -> if (policy.requireContentEnvelope) legacyBlockedItem(eventItem, content.threadInfo) else item
                is LiveLocationContent -> if (
                    !policy.requireContentEnvelope || allowEncryptedLiveLocationProjection(content, isRoomEncrypted())
                ) {
                    item
                } else {
                    legacyBlockedItem(eventItem, threadInfo = null)
                }
                is CallNotifyContent, LegacyCallInviteContent -> {
                    scheduleCallRecordRegistration(eventItem)
                    if (!policy.requireContentEnvelope ||
                        policy.allowRtc
                    ) {
                        item
                    } else {
                        accessDeniedItem(eventItem, threadInfo = null)
                    }
                }
                else -> item
            }
        }
    }

    override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem): Boolean {
        val message = ((item as? MatrixTimelineItem.Event)?.event?.content as? MessageContent) ?: return false
        val policyRequiresProtection = policyProvider.current().policy.requireContentEnvelope
        val contentProtectionFeatureEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
        val decision = classifyMatrixMessage(
            message = message,
            policyRequiresProtection = policyRequiresProtection,
            contentProtectionFeatureEnabled = contentProtectionFeatureEnabled,
        ) ?: return false
        val isBurnAfterRead = burnAfterReadContentIds(message).isNotEmpty()
        if (decision is StrictMatrixMessageDecision.AllowProtectedAttachment &&
            !message.hasProtectedCaptionCarrier()
        ) {
            return false
        }
        return decision.usesTransientDecryptingShell(
            isBurnAfterRead = isBurnAfterRead,
            isOperationCarrier = protectedContentKind(message) in OPERATION_CONTENT_KINDS,
        )
    }

    private suspend fun decryptOrRejectMessage(
        eventItem: MatrixTimelineItem.Event,
        message: MessageContent,
        policyRequiresProtection: Boolean,
        contentProtectionFeatureEnabled: Boolean,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
    ): MatrixTimelineItem {
        scheduleLifecycleRegistration(eventItem, message)
        val burnHeader = protectedHeader(message)?.takeIf { it.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ }
        if (burnHeader != null) {
            val active = synchronized(burnSessions) { burnSessions[burnHeader.contentId] }
            if (active == null || clock.epochMillis() >= active.session.viewExpiresAtEpochMillis) {
                return statusItem(eventItem, message.threadInfo, ProtectedMessageDisplayState.BURN_AFTER_READ_UNOPENED)
            }
        }
        val burnMediaContentIds = burnAfterReadContentIds(message)
        if (burnMediaContentIds.isNotEmpty()) {
            val now = clock.epochMillis()
            val allActive = burnMediaContentIds.all { contentId ->
                synchronized(burnSessions) { burnSessions[contentId] }
                    ?.session
                    ?.viewExpiresAtEpochMillis
                    ?.let { now < it } == true
            }
            if (!allActive) {
                return statusItem(eventItem, message.threadInfo, ProtectedMessageDisplayState.BURN_AFTER_READ_UNOPENED)
            }
        }
        return when (
        val decision = classifyMatrixMessage(message, policyRequiresProtection, contentProtectionFeatureEnabled)
    ) {
        null -> eventItem.copy(
            event = eventItem.event.copy(content = message.withExplicitUnprotectedClipboardSource())
        )
        StrictMatrixMessageDecision.DecryptEnvelope -> try {
            val cacheKey = decryptionCacheKey(eventItem.protectedEventIdentity(), message)
            val authorizationRevision = authorizationRevision()
            val previewRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
            decryptionCache.get(cacheKey)?.let { cachedContent ->
                // Cache only the authenticated projection. The Matrix SDK owns identity and mutable
                // event metadata: local echoes, server confirmations and a rebuilt room timeline may
                // all represent the same event with different UniqueId/transaction/send-state values.
                // Rebinding the content to the current item prevents a cache hit from resurrecting an
                // obsolete list key or stale send state after leaving and reopening the room.
                eventItem.withCachedProtectedContent(
                    cachedContent.withProtectedClipboardSource(cacheKey, authorizationRevision, eventItem.eventId?.value)
                ).also {
                    timelinePerf("decrypt_cache_hit")
                }
            } ?: decryptionCache.getStableRejection(cacheKey)?.let { cachedState ->
                statusItem(eventItem, message.threadInfo, cachedState).also {
                    timelinePerf("decrypt_rejection_cache_hit")
                }
            } ?: decryptionCache.getAuthorizationRejection(cacheKey, authorizationRevision)?.let { cachedState ->
                statusItem(eventItem, message.threadInfo, cachedState).also {
                    timelinePerf("decrypt_authorization_rejection_cache_hit")
                }
            } ?: run {
                timelinePerf("decrypt_cache_miss")
                val startedAt = System.nanoTime()
                try {
                    val ordinaryText = protectedHeader(message)?.let {
                        it.kind == ContentKind.TEXT && it.lifecycle.mode == MessageLifecycleMode.FOLLOW_ROOM && !message.isBurnAfterRead
                    } == true
                    // A locally protected sender echo belongs to SEND, not to a new RECEIVE with
                    // non-existent Matrix/KMS read facts. Cache misses still perform a genuine read.
                    val localEcho = when {
                        eventItem.event.isOwn && ordinaryText ->
                            decryptSendOwnedLocalEcho(cacheKey, message, authorizationRevision, previewRevision, timelineReleaseIntent,
                                eventItem.protectedEventIdentity(), accountIdHash(eventItem.event.sender.value), eventItem.transactionId?.value)
                        eventItem.event.isOwn && !message.isBurnAfterRead ->
                            decryptInteractionSendOwnedLocalEcho(cacheKey, message, authorizationRevision, previewRevision, timelineReleaseIntent,
                                eventItem.protectedEventIdentity(), accountIdHash(eventItem.event.sender.value), eventItem.transactionId?.value)
                        else -> null
                    }
                    val decrypted = localEcho ?: withProtectedReadLifecycle(eventItem, checkNotNull(message.protectedEnvelopeBody()), timelineReleaseIntent) {
                        decryptProtectedProjection(
                            cacheKey, message, authorizationRevision, previewRevision,
                            allowPendingLocalPayload = false,
                            timelineReleaseIntent = timelineReleaseIntent,
                        )
                    }
                    requireCurrentProjection(cacheKey, decrypted, authorizationRevision, previewRevision, timelineReleaseIntent)
                    val decryptedEvent = eventItem.withCachedProtectedContent(decrypted)
                    timelinePerf("decrypt_duration", (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
                    decryptedEvent.copy(
                        event = decryptedEvent.event.copy(
                            content = decryptedEvent.event.content.withProtectedClipboardSource(
                                cacheKey,
                                authorizationRevision,
                                eventItem.eventId?.value,
                            )
                        )
                    )
                } catch (failure: Exception) {
                    if (failure.isStableProtectedRejection()) {
                        decryptionCache.putStableRejection(cacheKey, failure.protectedMessageDisplayState())
                    } else if (failure.isAuthorizationScopedProtectedRejection()) {
                        decryptionCache.putAuthorizationRejection(
                            cacheKey,
                            authorizationRevision,
                            failure.protectedMessageDisplayState(),
                        )
                    }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Never log the event id, room id, envelope or message body. The stable code is enough to
            // diagnose fail-closed behavior on a real device without creating another plaintext carrier.
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_DECRYPT_REJECTED code=%s", failure.diagnosticCode())
            statusItem(eventItem, message.threadInfo, failure.protectedMessageDisplayState())
        }
        is StrictMatrixMessageDecision.AllowProtectedAttachment -> try {
            val rawCaption = (message.type as? MessageTypeWithAttachment)?.caption
            if (rawCaption?.isProtectedCaptionCarrier() != true || burnMediaContentIds.isNotEmpty()) {
                val authorized = if (rawCaption?.isProtectedCaptionCarrier() == true) {
                    decision.content.withProtectedCaption(
                        withProtectedReadLifecycle(eventItem, rawCaption.removePrefix(PROTECTED_CAPTION_PREFIX), timelineReleaseIntent) {
                            decryptCaption(
                                rawCaption,
                                timelineReleaseIntent = timelineReleaseIntent,
                            )
                        }
                    )
                } else {
                    decision.content
                }
                val projected = authorized.authorizeProtectedAttachmentProjection().let { content ->
                    if (burnMediaContentIds.isNotEmpty()) content.copy(isBurnAfterRead = true) else content
                }
                eventItem.copy(event = eventItem.event.copy(content = projected))
            } else {
                val cacheKey = attachmentDecryptionCacheKey(eventItem.protectedEventIdentity(), message, rawCaption)
                val authorizationRevision = authorizationRevision()
                decryptionCache.get(cacheKey)?.let { cachedContent ->
                    eventItem.withCachedProtectedContent(
                        cachedContent.withProtectedClipboardSource(cacheKey, authorizationRevision, eventItem.eventId?.value)
                    ).also {
                        timelinePerf("attachment_decrypt_cache_hit")
                    }
                } ?: decryptionCache.getStableRejection(cacheKey)?.let { cachedState ->
                    statusItem(eventItem, message.threadInfo, cachedState).also {
                        timelinePerf("attachment_decrypt_rejection_cache_hit")
                    }
                } ?: decryptionCache.getAuthorizationRejection(cacheKey, authorizationRevision)?.let { cachedState ->
                    statusItem(eventItem, message.threadInfo, cachedState).also {
                        timelinePerf("attachment_decrypt_authorization_rejection_cache_hit")
                    }
                } ?: run {
                    timelinePerf("attachment_decrypt_cache_miss")
                    try {
                        val ownedCaption = if (eventItem.event.isOwn) {
                            decryptCaptionSendOwnedLocalEcho(rawCaption, authorizationRevision,
                                protectedPreviewAuthorizationGate.authorizationRevision.value, timelineReleaseIntent,
                                eventItem.protectedEventIdentity(), accountIdHash(eventItem.event.sender.value), eventItem.transactionId?.value)
                        } else null
                        val caption = ownedCaption ?: withProtectedReadLifecycle(eventItem, rawCaption.removePrefix(PROTECTED_CAPTION_PREFIX), timelineReleaseIntent) {
                            decryptCaption(rawCaption, timelineReleaseIntent)
                        }
                        val projected = decision.content.withProtectedCaption(caption).authorizeProtectedAttachmentProjection()
                        decryptionCache.put(cacheKey, projected)
                        eventItem.copy(
                            event = eventItem.event.copy(
                                content = projected.withProtectedClipboardSource(
                                    cacheKey,
                                    authorizationRevision,
                                    eventItem.eventId?.value,
                                )
                            )
                        )
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (failure.isStableProtectedRejection()) {
                            decryptionCache.putStableRejection(cacheKey, failure.protectedMessageDisplayState())
                        } else if (failure.isAuthorizationScopedProtectedRejection()) {
                            decryptionCache.putAuthorizationRejection(
                                cacheKey,
                                authorizationRevision,
                                failure.protectedMessageDisplayState(),
                            )
                        }
                        Timber.tag(PROTECTED_DIAGNOSTIC_TAG)
                            .w("YZCP_ATTACHMENT_DECRYPT_REJECTED code=%s", failure.diagnosticCode())
                        statusItem(eventItem, message.threadInfo, failure.protectedMessageDisplayState())
                    }
                }
            }
        }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_ATTACHMENT_PROJECTION_PENDING code=%s", failure.diagnosticCode())
            statusItem(eventItem, message.threadInfo, failure.protectedMessageDisplayState())
        }
        StrictMatrixMessageDecision.Block -> legacyBlockedItem(eventItem, message.threadInfo)
        }
    }

    override suspend fun decryptMessageContent(eventIdentity: String, message: MessageContent): MessageContent =
        decryptPreviewMessage(eventIdentity, message, null)

    override suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        diagnosticOrigin: ProtectedReadDiagnosticOrigin,
    ): MessageContent = decryptPreviewMessage(eventIdentity, message, null, diagnosticOrigin)

    override suspend fun decryptMessageContent(
        eventIdentity: String,
        message: MessageContent,
        timelineReleaseIntent: TimelineContentReleaseIntent,
    ): MessageContent = decryptPreviewMessage(eventIdentity, message, timelineReleaseIntent)

    override suspend fun decryptMessageContent(
        eventIdentity: String, message: MessageContent, timelineReleaseIntent: TimelineContentReleaseIntent,
        senderAccountIdHash: String,
    ): MessageContent = decryptPreviewMessage(eventIdentity, message, timelineReleaseIntent, senderAccountIdHash = senderAccountIdHash)

    override suspend fun decryptMessageContent(
        eventIdentity: String, message: MessageContent, diagnosticOrigin: ProtectedReadDiagnosticOrigin,
        senderAccountIdHash: String,
    ): MessageContent = decryptPreviewMessage(eventIdentity, message, null, diagnosticOrigin, senderAccountIdHash)

    private suspend fun decryptPreviewMessage(
        eventIdentity: String,
        message: MessageContent,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
        diagnosticOrigin: ProtectedReadDiagnosticOrigin = ProtectedReadDiagnosticOrigin.UNATTRIBUTED,
        senderAccountIdHash: String? = null,
    ): MessageContent {
        require(eventIdentity.isNotBlank())
        timelineReleaseIntent?.let(::registeredTimelineReleaseIntent)
        if (!protectedPreviewAuthorizationGate.canAttemptPreview()) {
            return statusMessage(message, protectedPreviewAuthorizationGate.accessState.value.deniedDisplayState())
        }
        val policy = policyProvider.current().policy
        val featureEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.ContentProtection)
        return when (val decision = classifyMatrixMessage(message, policy.requireContentEnvelope, featureEnabled)) {
            null -> message.withExplicitUnprotectedClipboardSource()
            StrictMatrixMessageDecision.DecryptEnvelope -> try {
                val cacheKey = decryptionCacheKey(eventIdentity, message)
                val authorizationRevision = authorizationRevision()
                val previewRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
                (decryptionCache.get(cacheKey) as? MessageContent)
                    ?: decryptionCache.getStableRejection(cacheKey)?.let { cachedState ->
                        statusMessage(message, cachedState)
                    }
                    ?: decryptionCache.getAuthorizationRejection(cacheKey, authorizationRevision)?.let { cachedState ->
                        statusMessage(message, cachedState)
                    }
                    ?: try {
                        val ownedEcho = decryptInteractionSendOwnedLocalEcho(
                            cacheKey, message, authorizationRevision, previewRevision, timelineReleaseIntent,
                            eventIdentity, senderAccountIdHash,
                        )
                        (ownedEcho ?: withProtectedReadLifecycle(eventIdentity, checkNotNull(message.protectedEnvelopeBody()),
                            timelineReleaseIntent, senderAccountIdHash = senderAccountIdHash) {
                            LifecycleStageTiming.point(
                                "receive_preview_origin", cacheKey.contentId.value,
                                scope = "${diagnosticOrigin.token}:${accountIdHash(eventIdentity)}",
                            )
                            decryptProtectedProjection(cacheKey, message, authorizationRevision, previewRevision,
                                timelineReleaseIntent = timelineReleaseIntent, diagnosticOrigin = diagnosticOrigin)
                        }).let { decrypted ->
                            requireCurrentProjection(cacheKey, decrypted, authorizationRevision, previewRevision, timelineReleaseIntent)
                            decrypted as? MessageContent ?: protectedInteractionPreview(message, decrypted)
                        }
                    } catch (failure: Exception) {
                        if (failure.isStableProtectedRejection()) {
                            decryptionCache.putStableRejection(cacheKey, failure.protectedMessageDisplayState())
                        } else if (failure.isAuthorizationScopedProtectedRejection()) {
                            decryptionCache.putAuthorizationRejection(
                                cacheKey,
                                authorizationRevision,
                                failure.protectedMessageDisplayState(),
                            )
                        }
                        throw failure
                    }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_PREVIEW_REJECTED code=%s", failure.diagnosticCode())
                statusMessage(message, failure.protectedMessageDisplayState())
            }
        is StrictMatrixMessageDecision.AllowProtectedAttachment ->
                decision.content.authorizeProtectedAttachmentProjection()
            StrictMatrixMessageDecision.Block ->
                statusMessage(message, ProtectedMessageDisplayState.LEGACY_BLOCKED)
        }.let { projected ->
            projected.withMediaEventOrigin(roomId.value, eventIdentity.takeIf(::isCanonicalProtectedEventIdentity)) as MessageContent
        }
    }

    private fun MatrixTimelineItem.Event.protectedEventIdentity(): String =
        eventId?.value ?: transactionId?.value ?: uniqueId.value

    private suspend fun <T> withReceiveLifecycleOperation(
        eventIdentity: String,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
        isForeground: Boolean = timelineReleaseIntent?.releaseClass != TimelineContentReleaseClass.HISTORY_PREFETCH,
        block: suspend () -> T,
    ): T {
        // A transaction/UniqueId or a list-generated key is not a Matrix receive fact. An owned
        // producer echo is resolved before this boundary; unresolved local echoes wait for sync.
        if (!isCanonicalProtectedEventIdentity(eventIdentity)) throw ContentProtectionException.KmsUnavailable()
        timelineReleaseIntent?.let(::registeredTimelineReleaseIntent)
        val activeOperation = currentLifecycleOperationContext()
        if (activeOperation?.lifecycleId == FullAppLifecycleId.LC_13) {
            return block()
        }
        return lifecycleWork.receive(
            coordinator = appOperationalStateCoordinator,
            isForeground = isForeground,
            request = lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_13,
                trigger = "receive",
                accountIdHash = accountHash,
                matrixDeviceIdHash = matrixDeviceHash,
                roomIdHash = roomHash,
                eventIdHash = accountIdHash(eventIdentity),
                operationId = receiveLifecycleOperationId(
                    accountIdHash = accountHash,
                    matrixDeviceIdHash = matrixDeviceHash,
                    roomIdHash = roomHash,
                    eventIdHash = accountIdHash(eventIdentity),
                ),
            ),
        ) {
            block()
        }
    }

    private fun decryptionCacheKey(
        eventIdentity: String,
        message: MessageContent,
    ): ProtectedTimelineDecryptionCache.Key = decryptionCacheKey(
        eventIdentity = eventIdentity,
        encodedCarrier = checkNotNull(message.protectedEnvelopeBody()) {
            "A protected Matrix event is missing its envelope transport body."
        },
    )

    private fun attachmentDecryptionCacheKey(
        eventIdentity: String,
        message: MessageContent,
        protectedCaptionCarrier: String,
    ): ProtectedTimelineDecryptionCache.Key {
        require(protectedCaptionCarrier.isProtectedCaptionCarrier())
        return decryptionCacheKey(
            eventIdentity = eventIdentity,
            encodedCarrier = protectedCaptionCarrier.removePrefix(PROTECTED_CAPTION_PREFIX),
        ).copy(
            relatedContentIds = message.protectedMediaTransportName()
                ?.protectedMediaContentIds()
                .orEmpty()
                .toSet(),
        )
    }

    private fun decryptionCacheKey(
        eventIdentity: String,
        encodedCarrier: String,
    ): ProtectedTimelineDecryptionCache.Key {
        val encodedEnvelope = encodedCarrier.decodeEnvelope()
        val envelope = try {
            envelopeCodec.decode(encodedEnvelope)
        } finally {
            encodedEnvelope.fill(0)
        }
        return try {
            ProtectedTimelineDecryptionCache.Key(
                eventId = eventIdentity,
                contentId = envelope.header.contentId,
                ciphertextHash = MessageDigest.getInstance("SHA-256").digest(envelope.ciphertext).toHex(),
                policyVersion = envelope.header.policy.policyVersion,
                envelopeVersion = envelope.header.formatVersion,
                sessionId = sessionId.value,
                roomId = roomId.value,
                expiresAtEpochMillis = envelope.header.policy.expiresAtEpochMillis,
            )
        } finally {
            envelope.ciphertext.fill(0)
        }
    }

    private fun protectedContentKind(message: MessageContent): ContentKind? {
        val body = message.protectedEnvelopeBody() ?: return null
        val encoded = body.decodeEnvelope()
        val envelope = try {
            envelopeCodec.decode(encoded)
        } finally {
            encoded.fill(0)
        }
        return try {
            envelope.header.kind
        } finally {
            envelope.ciphertext.fill(0)
        }
    }

    private fun statusItem(
        eventItem: MatrixTimelineItem.Event,
        threadInfo: io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo?,
        state: ProtectedMessageDisplayState,
    ): MatrixTimelineItem {
        val header = runCatching { (eventItem.event.content as? MessageContent)?.let(::protectedHeader) }.getOrNull()
        return projectProtectedMessageStatus(
            eventItem, threadInfo, state, header?.kind,
            header?.contentId?.value?.takeIf { eventItem.event.isOwn && eventItem.eventId != null },
        )
    }

    private fun statusMessage(message: MessageContent, state: ProtectedMessageDisplayState): MessageContent =
        message.withProtectedDisplayState(state)

    private fun legacyBlockedItem(
        eventItem: MatrixTimelineItem.Event,
        threadInfo: io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo?,
    ): MatrixTimelineItem = statusItem(eventItem, threadInfo, ProtectedMessageDisplayState.LEGACY_BLOCKED)

    private fun accessDeniedItem(
        eventItem: MatrixTimelineItem.Event,
        threadInfo: io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo?,
    ): MatrixTimelineItem = statusItem(eventItem, threadInfo, ProtectedMessageDisplayState.ACCESS_DENIED)

    private fun hiddenOperationItem(eventItem: MatrixTimelineItem.Event): MatrixTimelineItem = hiddenProtectedOperationItem(eventItem)

    override suspend fun bindPreparedTransaction(prepared: PreparedProtectedContent, transactionId: String) {
        require(transactionId.isNotBlank())
        val scope = io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value)
        val origin = protectedSendOriginStore.find(scope, prepared.contentId.value)
            ?: throw ContentProtectionException.KmsUnavailable()
        val operation = currentLifecycleOperationContext() ?: throw ContentProtectionException.KmsUnavailable()
        check(origin.operationId == operation.operationId && origin.lifecycleId == operation.lifecycleId &&
            origin.accountIdHash == accountHash && origin.matrixDeviceIdHash == matrixDeviceHash &&
            origin.installationId == deviceTrustService.current().installationId && origin.roomIdHash == roomHash &&
            origin.carrierSha256 == accountIdHash(prepared.encodedEnvelope) &&
            (origin.transactionId == null || origin.transactionId == transactionId))
        protectedSendOriginStore.remember(scope, origin.copy(transactionId = transactionId))
    }

    private suspend fun localProjectionOriginContext(
        eventIdentity: String,
        senderAccountIdHash: String?,
        transactionId: String?,
        carrier: String,
        header: io.element.android.security.contentprotection.api.ContentProtectionHeader,
    ): LifecycleOperationContext? {
        val origin = protectedSendOriginStore.find(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value),
            header.contentId.value,
        ) ?: return null
        val carrierHash = accountIdHash(carrier)
        val sender = senderAccountIdHash ?: origin.takeIf {
            it.eventId == eventIdentity && it.carrierSha256 == carrierHash
        }?.accountIdHash ?: return null
        val trust = deviceTrustService.current()
        // Producer bytes prove the payload, but cannot bind a copied carrier to an
        // arbitrary Matrix event or summary. Every memory fast path uses the same
        // authenticated sender and original event/transaction resolver as KMS reads.
        val input = ProtectedEventOwnershipResolver.Input(
                accountHash, matrixDeviceHash, trust.installationId, sender, header.senderInstallationId,
                roomHash, eventIdentity.takeIf(ProtectedEventOwnershipResolver::isCanonicalEvent), transactionId,
                header.contentId.value, header.kind, carrierHash, header.roomEpoch?.generation,
        )
        val boundOrigin = awaitOriginalSendBinding(input, origin)
        return ProtectedEventOwnershipResolver.resolve(input, boundOrigin).sendContext
    }

    private fun sendBindingRegistrationKey(contentId: String, eventId: String): String =
        accountIdHash("mapping:$accountHash:$matrixDeviceHash:$roomHash:$contentId:$eventId")

    private suspend fun awaitOriginalSendBinding(
        input: ProtectedEventOwnershipResolver.Input,
        origin: ProtectedEventOwnershipResolver.Origin?,
    ): ProtectedEventOwnershipResolver.Origin? {
        if (!ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, origin)) return origin
        Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_SEND_BINDING_WAIT kind=%s", input.contentKind.name)
        val completed = withTimeoutOrNull(10_000L) {
            lifecycleWork.awaitRegistration(sendBindingRegistrationKey(input.contentId, checkNotNull(input.eventId)))
                ?: false
        } ?: throw ContentProtectionException.SendBindingPending()
        Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_SEND_BINDING_WAIT_FINISHED completed=%s", completed)
        if (!completed) return origin
        // Completion is only a scheduling signal. Re-read the encrypted record and run
        // the exact resolver again; a transaction or waiter result never authorizes display.
        return protectedSendOriginStore.find(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), input.contentId,
        )
    }

    private suspend fun decryptSendOwnedLocalEcho(
        cacheKey: ProtectedTimelineDecryptionCache.Key,
        message: MessageContent,
        authorizationRevision: Long,
        previewRevision: Long,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
        eventIdentity: String,
        senderAccountIdHash: String?,
        transactionId: String? = null,
    ): EventContent? {
        val carrier = checkNotNull(message.protectedEnvelopeBody())
        val encoded = carrier.decodeEnvelope()
        val envelope = try { envelopeCodec.decode(encoded) } finally { encoded.fill(0) }
        try {
            val originContext = localProjectionOriginContext(eventIdentity, senderAccountIdHash, transactionId, carrier, envelope.header)
                ?: return null
            val installationId = deviceTrustService.current().installationId
            val ticket = pendingPayloadCache.ownership(
                envelope.pendingPayloadKey(),
                PendingProtectedPayloadCache.Identity(
                    matrixDeviceHash, installationId, accountIdHash(carrier), authorizationRevision, previewRevision,
                ),
            ) ?: return null
            if (ticket.owner.operation.operationId != originContext.operationId ||
                ticket.owner.operation.lifecycleId != originContext.lifecycleId) return null
            val registeredIntent = timelineReleaseIntent?.let(::registeredTimelineReleaseIntent)
            return withContext(ticket.owner.operation) {
                LifecycleStageTiming.point("send_local_echo_content_bind", envelope.header.contentId.value,
                    scope = timelineReleaseIntent?.scopeId.orEmpty())
                decryptionCache.readOnce(
                    identity = ProtectedTimelineDecryptionCache.ReadIdentity(
                        cacheKey, ticket.owner.operation.operationId, deviceId.value, installationId,
                        authorizationRevision, previewRevision, timelineReleaseIntent?.releaseClass, message,
                        timelineReleaseIntent?.readPurpose,
                    ),
                    canRead = {
                        pendingPayloadCache.isCurrent(ticket) && protectedPreviewAuthorizationGate.canAttemptPreview() &&
                            protectedPreviewAuthorizationGate.authorizationRevision.value == previewRevision
                    },
                    canConsume = {
                        timelineReleaseIntent == null ||
                            timelineReleaseScopeOwners.resolve(timelineReleaseIntent.scopeId)?.ownerToken == registeredIntent?.scopeOwnerToken
                    },
                ) {
                    // The producer owns its byte copy, including if a joining page cancels. Existing
                    // shared-read invalidation rejects late results after lock/revoke/destroy.
                    val plaintext = pendingPayloadCache.get(ticket) ?: throw ContentProtectionException.AccessDenied()
                    try {
                        LifecycleStageTiming.measure("send_local_echo_projection", envelope.header.contentId.value) {
                            decodePayload(
                                json.decodeFromString(ProtectedMatrixPayload.serializer(), plaintext.decodeToString()),
                                message, envelope.header.policy.policyId.value, envelope.header.policy.policyVersion,
                            ).let { decoded -> if (decoded is MessageContent) decoded.copy(clipboardSource = null) else decoded }
                        }
                    } finally { plaintext.fill(0) }
                }
            }
        } finally { envelope.ciphertext.fill(0) }
    }

    private suspend fun decryptInteractionSendOwnedLocalEcho(
        cacheKey: ProtectedTimelineDecryptionCache.Key,
        message: MessageContent,
        authorizationRevision: Long,
        previewRevision: Long,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
        eventIdentity: String,
        senderAccountIdHash: String?,
        transactionId: String? = null,
    ): EventContent? {
        val decoded = withInteractionSendOwnedPayload(
            checkNotNull(message.protectedEnvelopeBody()), authorizationRevision, previewRevision, timelineReleaseIntent,
            eventIdentity, senderAccountIdHash, transactionId,
        ) { payload, header ->
            decodePayload(payload, message, header.policy.policyId.value, header.policy.policyVersion)
                .let { if (it is MessageContent) it.copy(clipboardSource = null) else it }
        } ?: return null
        decryptionCache.put(cacheKey, decoded)
        return decoded
    }

    private suspend fun decryptCaptionSendOwnedLocalEcho(
        captionCarrier: String,
        authorizationRevision: Long,
        previewRevision: Long,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
        eventIdentity: String,
        senderAccountIdHash: String?,
        transactionId: String? = null,
    ): ProtectedCaption? = withInteractionSendOwnedPayload(
        captionCarrier.removePrefix(PROTECTED_CAPTION_PREFIX), authorizationRevision, previewRevision, timelineReleaseIntent,
        eventIdentity, senderAccountIdHash, transactionId,
    ) { payload, header ->
        require(header.kind == ContentKind.MEDIA_CAPTION)
        decodeCaptionPayload(payload)
    }

    private suspend fun <T> withInteractionSendOwnedPayload(
        carrier: String,
        authorizationRevision: Long,
        previewRevision: Long,
        timelineReleaseIntent: TimelineContentReleaseIntent?,
        eventIdentity: String,
        senderAccountIdHash: String?,
        transactionId: String?,
        decode: (ProtectedMatrixPayload, io.element.android.security.contentprotection.api.ContentProtectionHeader) -> T,
    ): T? {
        val encoded = carrier.decodeEnvelope()
        val envelope = try { envelopeCodec.decode(encoded) } finally { encoded.fill(0) }
        try {
            // Burn content must always use the authenticated consumption session and genuine release.
            if (envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) return null
            val originContext = localProjectionOriginContext(eventIdentity, senderAccountIdHash, transactionId, carrier, envelope.header)
                ?: return null
            val installationId = deviceTrustService.current().installationId
            val ticket = pendingPayloadCache.ownership(
                envelope.pendingPayloadKey(),
                PendingProtectedPayloadCache.Identity(
                    matrixDeviceHash, installationId, accountIdHash(carrier), authorizationRevision, previewRevision,
                ),
            )?.takeIf {
                it.owner.operation.operationId == originContext.operationId &&
                    it.owner.operation.lifecycleId == originContext.lifecycleId &&
                    isInteractionProjectionOwner(it.owner.operation.lifecycleId, envelope.header.kind) &&
                    it.owner.contentKind == envelope.header.kind
            } ?: return null
            val registeredIntent = timelineReleaseIntent?.let(::registeredTimelineReleaseIntent)
            fun requireCurrentOwner() {
                if (!pendingPayloadCache.isCurrent(ticket) || authorizationRevision() != authorizationRevision ||
                    !protectedPreviewAuthorizationGate.canAttemptPreview() ||
                    protectedPreviewAuthorizationGate.authorizationRevision.value != previewRevision ||
                    (timelineReleaseIntent != null &&
                        timelineReleaseScopeOwners.resolve(timelineReleaseIntent.scopeId)?.ownerToken != registeredIntent?.scopeOwnerToken)
                ) throw ContentProtectionException.AccessDenied()
            }
            return withContext(ticket.owner.operation) {
                requireCurrentOwner()
                val plaintext = pendingPayloadCache.get(ticket) ?: throw ContentProtectionException.AccessDenied()
                val decoded = try {
                    decode(json.decodeFromString(ProtectedMatrixPayload.serializer(), plaintext.decodeToString()), envelope.header)
                } finally { plaintext.fill(0) }
                requireCurrentOwner()
                // Producer memory cannot supply RECEIVE or KMS-read facts, nor retire caption lineage.
                decoded
            }
        } finally { envelope.ciphertext.fill(0) }
    }

    private suspend fun decryptProtectedProjection(
        cacheKey: ProtectedTimelineDecryptionCache.Key,
        message: MessageContent,
        authorizationRevision: Long,
        previewRevision: Long,
        allowPendingLocalPayload: Boolean = false,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
        diagnosticOrigin: ProtectedReadDiagnosticOrigin = ProtectedReadDiagnosticOrigin.UNATTRIBUTED,
    ): EventContent {
        val header = checkNotNull(protectedHeader(message))
        if (
            !canShareProtectedTextRead(
                header.kind, header.lifecycle.mode, header.offlineLeaseMode, allowPendingLocalPayload, message.isBurnAfterRead,
            )
        ) {
            return LifecycleStageTiming.measure("receive_source_decrypt", header.contentId.value,
                scope = diagnosticOrigin.token) {
                decryptProtectedContent(message, allowPendingLocalPayload, timelineReleaseIntent)
            }.also { decryptionCache.put(cacheKey, it) }
        }
        val operation = checkNotNull(currentLifecycleOperationContext())
        val installationId = deviceTrustService.current().installationId
        // Every joining consumer validates its own scope; the producer still registers its KMS intent normally.
        val registeredIntent = timelineReleaseIntent?.let(::registeredTimelineReleaseIntent)
        val identity = ProtectedTimelineDecryptionCache.ReadIdentity(
                projection = cacheKey,
                operationId = operation.operationId,
                matrixDeviceId = deviceId.value,
                installationId = installationId,
                authorizationRevision = authorizationRevision,
                previewRevision = previewRevision,
                releaseClass = timelineReleaseIntent?.releaseClass,
                encryptedMessage = message,
                readPurpose = timelineReleaseIntent?.readPurpose,
            )
        val canRead = {
                protectedPreviewAuthorizationGate.canAttemptPreview() &&
                    protectedPreviewAuthorizationGate.authorizationRevision.value == previewRevision
            }
        val canConsume = {
                timelineReleaseIntent == null ||
                    timelineReleaseScopeOwners.resolve(timelineReleaseIntent.scopeId)?.ownerToken == registeredIntent?.scopeOwnerToken
            }
        decryptionCache.completedProjectionForRead(identity, canRead, canConsume)?.let { return it }
        return decryptionCache.readOnce(
            identity = identity,
            canRead = canRead,
            canConsume = canConsume,
        ) {
            // The existing path still owns nonce creation, KMS release, grant checks and byte zeroing.
            decryptionCache.completedProjectionForRead(identity, canRead, canConsume)
                ?: LifecycleStageTiming.measure("receive_source_decrypt", header.contentId.value,
                    scope = diagnosticOrigin.token) {
                    decryptProtectedContent(message, allowPendingLocalPayload = false, timelineReleaseIntent = timelineReleaseIntent)
                }
        }
    }

    private fun requireCurrentProjection(
        key: ProtectedTimelineDecryptionCache.Key,
        content: EventContent,
        authorizationRevision: Long,
        previewRevision: Long,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
    ) {
        if (clock.epochMillis() >= key.expiresAtEpochMillis) throw ContentProtectionException.Expired()
        // Independent live/history reads can publish equal authenticated copies. Object identity
        // must not turn the earlier successful read into a denial when the later copy is published.
        if (
            authorizationRevision() != authorizationRevision ||
            protectedPreviewAuthorizationGate.authorizationRevision.value != previewRevision ||
            !protectedPreviewAuthorizationGate.canAttemptPreview() || decryptionCache.get(key) != content ||
            (timelineReleaseIntent != null && timelineReleaseScopeOwners.resolve(timelineReleaseIntent.scopeId) == null)
        ) {
            throw ContentProtectionException.AccessDenied()
        }
    }

    private suspend fun decryptProtectedContent(
        message: MessageContent,
        allowPendingLocalPayload: Boolean = false,
        timelineReleaseIntent: TimelineContentReleaseIntent? = null,
    ): EventContent {
        val encodedEnvelope = checkNotNull(message.protectedEnvelopeBody()) {
            "A protected Matrix event is missing its envelope transport body."
        }.decodeEnvelope()
        val envelope = try {
            envelopeCodec.decode(encodedEnvelope)
        } finally {
            encodedEnvelope.fill(0)
        }
        LifecycleStageTiming.point("receive_content_bind", envelope.header.contentId.value,
            scope = timelineReleaseIntent?.scopeId.orEmpty())
        val content = run {
            val pendingPayload = if (allowPendingLocalPayload) {
                pendingPayloadCache.get(envelope.pendingPayloadKey())
            } else {
                null
            }
            if (pendingPayload != null) {
                return@run try {
                    decodePayload(
                        json.decodeFromString(ProtectedMatrixPayload.serializer(), pendingPayload.decodeToString()),
                        message,
                        envelope.header.policy.policyId.value,
                        envelope.header.policy.policyVersion,
                    )
                        .also { Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_DECRYPT_OK source=pending_local_echo") }
                } finally {
                    pendingPayload.fill(0)
                    envelope.ciphertext.fill(0)
                }
            }
            val trust = deviceTrustService.current()
            val burnSessionId = if (envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ) {
                synchronized(burnSessions) { burnSessions[envelope.header.contentId]?.session?.sessionId }
                    ?: throw ContentProtectionException.AccessDenied()
            } else {
                null
            }
            val plaintext = LifecycleStageTiming.measure("receive_decrypt_bytes", envelope.header.contentId.value,
                scope = timelineReleaseIntent?.scopeId.orEmpty()) { contentProtectionService.decryptBytes(
                ReleaseContentRequest(
                    header = envelope.header,
                    vaultId = vaultId,
                    accountIdHash = accountHash,
                    roomId = roomId.value,
                    requestingInstallationId = trust.installationId,
                    requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                    burnSessionId = burnSessionId,
                    timelineReleaseIntent = timelineReleaseIntent?.let(::registeredTimelineReleaseIntent),
                ),
                envelope.ciphertext,
            ) }
            try {
                LifecycleStageTiming.measure("receive_payload_decode", envelope.header.contentId.value) { decodePayload(
                    json.decodeFromString(ProtectedMatrixPayload.serializer(), plaintext.decodeToString()),
                    message,
                    envelope.header.policy.policyId.value,
                    envelope.header.policy.policyVersion,
                ) }
            } finally {
                plaintext.fill(0)
                envelope.ciphertext.fill(0)
            }
        }
        return content.also {
            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_DECRYPT_OK source=release")
        }.let { decoded ->
            if (decoded is MessageContent) {
                decoded.copy(
                    isBurnAfterRead = envelope.header.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ,
                    clipboardSource = null,
                    authenticatedContentId = envelope.header.contentId.value,
                )
            } else {
                decoded
            }
        }
    }

    private fun timelineReleaseScopeId(scopeId: String): String = "$roomHash:$scopeId"

    private fun registeredTimelineReleaseIntent(intent: TimelineContentReleaseIntent): TimelineContentReleaseIntent {
        val registration = timelineReleaseScopeOwners.resolve(intent.scopeId)
            ?: throw CancellationException("Timeline release scope is not owned by this active adapter.")
        return intent.copy(
            scopeId = registration.qualifiedScopeId,
            roomId = roomId.value,
            scopeOwnerToken = registration.ownerToken,
        )
    }

    private fun protectedHeader(message: MessageContent): io.element.android.security.contentprotection.api.ContentProtectionHeader? {
        val carrier = message.protectedEnvelopeBody() ?: return null
        val encoded = carrier.decodeEnvelope()
        val envelope = try {
            envelopeCodec.decode(encoded)
        } finally {
            encoded.fill(0)
        }
        return try {
            envelope.header
        } finally {
            envelope.ciphertext.fill(0)
        }
    }

    private suspend fun beginBurnSession(contentId: ContentId, eventIdentity: String): ActiveBurnSession {
        val trust = deviceTrustService.current()
        val checkpointKey = BurnConsumptionKey(
            accountIdHash = accountHash,
            roomIdHash = roomHash,
            eventIdHash = accountIdHash(eventIdentity),
            contentId = contentId.value,
        )
        val current = clock.epochMillis()
        val checkpoint = pendingBurnConsumptionForOpen(
            existing = burnConsumptionStore.get(checkpointKey),
            key = checkpointKey,
            installationId = trust.installationId,
            nowEpochMillis = current,
            newOperationId = UUID.randomUUID().toString(),
            newSessionId = UUID.randomUUID().toString(),
            operationDeadlineEpochMillis = current + BURN_LIFECYCLE_OPERATION_TIMEOUT_MILLIS,
        )
        burnConsumptionStore.persist(checkpoint)
        val operationRequest = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_21,
            trigger = "burn_consumed",
            accountIdHash = accountHash,
            matrixDeviceIdHash = matrixDeviceHash,
            roomIdHash = roomHash,
            eventIdHash = checkpointKey.eventIdHash,
            operationId = checkpoint.viewOperationId,
            deadlineEpochMillis = checkpoint.operationDeadlineEpochMillis,
        )
        val session = try {
            appOperationalStateCoordinator.withOperation(operationRequest) {
                contentProtectionService.beginBurnAfterRead(
                    OpenBurnAfterReadRequest(
                        contentId = contentId,
                        accountIdHash = accountHash,
                        requestingInstallationId = trust.installationId,
                        sessionId = checkpoint.sessionId,
                        requestNonce = ByteArray(24).also(secureRandom::nextBytes),
                    )
                )
            }
        } catch (failure: Exception) {
            burnConsumptionStore.persist(
                checkpoint.copy(
                    lastAttemptAtEpochMillis = clock.epochMillis(),
                    failureCode = failure.diagnosticCode(),
                )
            )
            throw failure
        }
        val viewingCheckpoint = burnConsumptionStore.persist(
            checkpoint.copy(
                state = BurnConsumptionState.VIEWING,
                viewExpiresAtEpochMillis = session.viewExpiresAtEpochMillis,
                lastAttemptAtEpochMillis = clock.epochMillis(),
                failureCode = null,
            )
        )
        try {
            burnAfterReadMediaSessionRegistry.register(session)
        } catch (failure: Exception) {
            runCatching {
                finishBurnSession(
                    session = session,
                    eventIdentity = eventIdentity,
                    checkpoint = viewingCheckpoint,
                    reason = BurnAfterReadEndReason.VIEW_CLOSED,
                )
            }
            throw failure
        }
        lateinit var active: ActiveBurnSession
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val remaining = (session.viewExpiresAtEpochMillis - session.serverTimeEpochMillis).coerceAtLeast(0L)
            delay(remaining)
            endBurnSession(active, BurnAfterReadEndReason.WINDOW_EXPIRED)
        }
        active = ActiveBurnSession(
            session = session,
            eventIdentity = eventIdentity,
            checkpointKey = checkpointKey,
            viewOperationId = checkpoint.viewOperationId,
            operationDeadlineEpochMillis = checkpoint.operationDeadlineEpochMillis,
            expiryJob = job,
        )
        return active.also {
            synchronized(burnSessions) { burnSessions[contentId] = active }
            job.start()
        }
    }

    private suspend fun endBurnSession(
        active: ActiveBurnSession,
        reason: BurnAfterReadEndReason,
    ) {
        synchronized(burnSessions) { burnSessions.remove(active.session.contentId)?.expiryJob?.cancel() }
        val checkpoint = checkNotNull(burnConsumptionStore.get(active.checkpointKey)) {
            "Burn consumption checkpoint is unavailable."
        }
        finishBurnSession(active.session, active.eventIdentity, checkpoint, reason)
    }

    private suspend fun finishBurnSession(
        session: BurnAfterReadSession,
        eventIdentity: String,
        checkpoint: PendingBurnConsumption,
        reason: BurnAfterReadEndReason,
    ) {
        try {
            finishPendingBurnConsumption(checkpoint, reason)
        } finally {
            decryptionCache.remove(roomId.value, eventIdentity)
        }
    }

    private suspend fun recoverPendingBurnConsumptions() {
        val current = clock.epochMillis()
        pendingBurnConsumptionsToResume(burnConsumptionStore.pending(), accountHash, roomHash, current)
            .asSequence()
            .forEach { checkpoint ->
                val reason = checkpoint.endReason
                    ?.let { runCatching { BurnAfterReadEndReason.valueOf(it) }.getOrNull() }
                    ?: BurnAfterReadEndReason.WINDOW_EXPIRED
                runCatching { finishPendingBurnConsumption(checkpoint, reason) }
            }
    }

    private suspend fun finishPendingBurnConsumption(
        checkpoint: PendingBurnConsumption,
        reason: BurnAfterReadEndReason,
    ) {
        val endPending = burnConsumptionStore.persist(
            pendingBurnConsumptionForEnd(checkpoint, reason, clock.epochMillis())
        )
        try {
            val contentId = ContentId(endPending.key.contentId)
            burnAfterReadMediaSessionRegistry.remove(
                endPending.key.accountIdHash,
                contentId,
                endPending.sessionId,
            )
            // DefaultContentProtectionService persists the local revocation in a finally block. Do not
            // swallow a failed server acknowledgement: callers need to distinguish a fully confirmed
            // terminal transition from an offline, locally fail-closed one.
            val request = lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_21,
                trigger = "burn_consumed",
                accountIdHash = accountHash,
                matrixDeviceIdHash = matrixDeviceHash,
                roomIdHash = roomHash,
                eventIdHash = endPending.key.eventIdHash,
                operationId = endPending.viewOperationId,
                deadlineEpochMillis = endPending.operationDeadlineEpochMillis,
            )
            appOperationalStateCoordinator.withOperation(request) {
                contentProtectionService.endBurnAfterRead(
                    vaultId,
                    EndBurnAfterReadRequest(
                        contentId = contentId,
                        accountIdHash = endPending.key.accountIdHash,
                        requestingInstallationId = endPending.installationId,
                        sessionId = endPending.sessionId,
                        reason = reason,
                    ),
                )
            }
            burnConsumptionStore.persist(
                endPending.copy(
                    state = BurnConsumptionState.TERMINAL_CONFIRMED,
                    lastAttemptAtEpochMillis = clock.epochMillis(),
                )
            )
            burnConsumptionStore.removeConfirmed(endPending.key)
        } catch (failure: Exception) {
            burnConsumptionStore.persist(
                endPending.copy(
                    lastAttemptAtEpochMillis = clock.epochMillis(),
                    failureCode = failure.diagnosticCode(),
                )
            )
            throw failure
        }
    }

    private fun BurnAfterReadSession.endRequest(reason: BurnAfterReadEndReason) = EndBurnAfterReadRequest(
        contentId = contentId,
        accountIdHash = accountIdHash,
        requestingInstallationId = installationId,
        sessionId = sessionId,
        reason = reason,
    )

    override suspend fun destroyItem(item: MatrixTimelineItem, reason: ContentAccessDestroyReason): Boolean =
        withContext(NonCancellable) {
            val eventItem = item as? MatrixTimelineItem.Event ?: return@withContext false
            val content = eventItem.event.content as? MessageContent ?: return@withContext false
            val contentIds = lifecycleContentIdsForDestruction(content).takeIf(Set<ContentId>::isNotEmpty) ?: return@withContext false
            try {
                destroyContentIdsRetainingLineageDebt(
                    contentIds = contentIds,
                    markDestructionPending = captionLineageCoordinator::markDestructionPending,
                    destroyContentAccess = { contentId ->
                        contentProtectionService.destroyContentAccess(vaultId, contentId, reason)
                    },
                )
            } finally {
                decryptionCache.remove(roomId.value, eventItem.protectedEventIdentity())
            }
        }

    override suspend fun revokeItemLocally(item: MatrixTimelineItem): Boolean {
        val eventItem = item as? MatrixTimelineItem.Event ?: return false
        val content = eventItem.event.content as? MessageContent ?: return false
        val contentIds = lifecycleContentIdsForDestruction(content).takeIf(Set<ContentId>::isNotEmpty) ?: return false
        var revokedAll = true
        contentIds.forEach { contentId ->
            if (!contentProtectionService.revokeLocalContent(vaultId, contentId)) revokedAll = false
        }
        if (revokedAll) decryptionCache.remove(roomId.value, eventItem.protectedEventIdentity())
        return revokedAll
    }

    override fun clearDerivedPlaintextForRedaction(eventIdentities: Set<String>) {
        if (eventIdentities.isEmpty()) return
        eventIdentities.forEach { eventIdentity ->
            decryptionCache.remove(roomId.value, eventIdentity)
        }
    }

    override fun containsContent(item: MatrixTimelineItem, contentId: ContentId): Boolean {
        val message = ((item as? MatrixTimelineItem.Event)?.event?.content as? MessageContent) ?: return false
        return runCatching { contentId in lifecycleContentIds(message) }.getOrDefault(false)
    }

    private fun scheduleLifecycleRegistration(eventItem: MatrixTimelineItem.Event, message: MessageContent) {
        if (!protectedPreviewAuthorizationGate.canAttemptPreview()) return
        if (!eventItem.event.isOwn) return
        val eventId = eventItem.eventId?.value ?: return
        val contentIds = runCatching { lifecycleContentIds(message) }.getOrElse { return }
        if (contentIds.isEmpty()) return
        val mediaUris = protectedMediaUris(message)
        contentIds.forEach { contentId ->
            val registrationKey = sendBindingRegistrationKey(contentId.value, eventId)
            val shouldRegister = lifecycleWork.claimRegistration(registrationKey)
            if (!shouldRegister) return@forEach
            coroutineScope.launch {
                val originScopeKey = originatingOperationScopeKey(contentId)
                val carrier = protectedCarrierForContent(message, contentId)
                val storedOrigin = carrier?.let { matchingStoredSendOrigin(it, contentId, eventId) }
                val currentOperation = lifecycleWork.originatingOperation(originScopeKey)
                val originatingOperation = storedOrigin?.let { LifecycleOperationContext(it.operationId, it.lifecycleId) }
                    ?: currentOperation
                val isLiveSend = ProtectedEventOwnershipResolver.needsImmediateEventBinding(storedOrigin, currentOperation)
                Timber.tag(PERFORMANCE_TAG).d(
                    "stage=event_registration key=%s live=%s", registrationKey, isLiveSend,
                )
                val registered = runCatching {
                    lifecycleWork.registrationWork(isLiveSend = isLiveSend) {
                        if (originatingOperation != null) {
                            withContext(originatingOperation) {
                                lifecycleWork.registrationTransport(isForeground = isLiveSend, isLiveSend = isLiveSend) {
                                    check(contentProtectionService.registerMessageEvent(contentId, roomId.value, eventId, mediaUris))
                                    if (storedOrigin != null && storedOrigin.eventId == null) {
                                        protectedSendOriginStore.remember(
                                            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value),
                                            storedOrigin.copy(eventId = eventId),
                                        )
                                        // Event mapping changes identity metadata, not Vault/KMS
                                        // authority. Joining readers re-resolve this exact record;
                                        // a global authorization refresh would blank unrelated rows.
                                    }
                                    true
                                }
                            }
                        } else {
                            // Sender mapping repair cannot supply a receive/decrypt fact. The
                            // server binding worker owns cold recovery; retry until SEND is known.
                            Timber.tag(PROTECTED_DIAGNOSTIC_TAG).d("YZCP_MAPPING_SEND_OWNER_UNAVAILABLE")
                            false
                        }
                    }
                }.getOrDefault(false)
                if (!registered) {
                    lifecycleWork.finishRegistration(registrationKey, success = false)
                    Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_LIFECYCLE_MAPPING_RETRY")
                } else {
                    val observed = runCatching {
                        if (captionLineageCoordinator.hasPendingContent(contentId)) {
                            val captionCarrier = (message.type as? MessageTypeWithAttachment)?.caption
                                ?.takeIf(String::isProtectedCaptionCarrier)
                            val binding = if (captionCarrier != null) {
                                attachmentDecryptionCacheKey(eventId, message, captionCarrier)
                            } else {
                                require(protectedContentKind(message) in setOf(ContentKind.MEDIA_CAPTION, ContentKind.TEXT))
                                decryptionCacheKey(eventId, message)
                            }
                            require(binding.contentId == contentId)
                            captionLineageCoordinator.observeBoundEvent(
                                contentId, eventId, binding.expiresAtEpochMillis,
                                liveSendOperation = currentOperation.takeIf { isLiveSend },
                            )
                        }
                    }
                    if (observed.isFailure) {
                        lifecycleWork.finishRegistration(registrationKey, success = false)
                        Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_CAPTION_LINEAGE_BINDING_RETRY")
                    } else {
                        lifecycleWork.finishRegistration(registrationKey, success = true)
                        lifecycleWork.forgetOriginatingOperation(originScopeKey, originatingOperation)
                    }
                }
            }.invokeOnCompletion { failure ->
                if (failure != null) lifecycleWork.abortRegistration(registrationKey)
            }
        }
    }

    private suspend fun rememberOriginatingOperation(contentId: ContentId) {
        val context = currentLifecycleOperationContext() ?: return
        lifecycleWork.rememberOriginatingOperation(originatingOperationScopeKey(contentId), context)
    }

    private fun originatingOperationScopeKey(contentId: ContentId): String =
        accountIdHash("origin:$accountHash:$matrixDeviceHash:$roomHash:${contentId.value}")

    private fun protectedCarrierForContent(message: MessageContent, contentId: ContentId): String? {
        val candidates = listOfNotNull(message.protectedEnvelopeBody(),
            (message.type as? MessageTypeWithAttachment)?.caption?.takeIf(String::isProtectedCaptionCarrier)
                ?.removePrefix(PROTECTED_CAPTION_PREFIX))
        return candidates.firstOrNull { carrier ->
            val bytes = carrier.decodeEnvelope()
            val envelope = try { envelopeCodec.decode(bytes) } finally { bytes.fill(0) }
            try { envelope.header.contentId == contentId } finally { envelope.ciphertext.fill(0) }
        }
    }

    private suspend fun matchingStoredSendOrigin(
        carrier: String, contentId: ContentId, eventId: String,
    ): ProtectedEventOwnershipResolver.Origin? {
        val origin = protectedSendOriginStore.find(
            io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value), contentId.value,
        ) ?: return null
        return origin.takeIf {
            it.accountIdHash == accountHash && it.matrixDeviceIdHash == matrixDeviceHash &&
                it.installationId == deviceTrustService.current().installationId && it.roomIdHash == roomHash &&
                it.contentId == contentId.value && it.carrierSha256 == accountIdHash(carrier) &&
                (it.eventId == null || it.eventId == eventId) &&
                ProtectedEventOwnershipResolver.acceptsSendLifecycle(it.lifecycleId, it.contentKind)
        }
    }

    private suspend fun <T> withProtectedReadLifecycle(
        item: MatrixTimelineItem.Event,
        carrier: String,
        releaseIntent: TimelineContentReleaseIntent?,
        read: suspend () -> T,
    ): T = withProtectedReadLifecycle(
        item.eventId?.value ?: item.protectedEventIdentity(), carrier, releaseIntent,
        senderAccountIdHash = accountIdHash(item.event.sender.value), transactionId = item.transactionId?.value, read = read,
    )

    private suspend fun <T> withProtectedReadLifecycle(
        eventIdentity: String,
        carrier: String,
        releaseIntent: TimelineContentReleaseIntent?,
        senderAccountIdHash: String? = null,
        transactionId: String? = null,
        read: suspend () -> T,
    ): T {
        val bytes = carrier.decodeEnvelope()
        val envelope = try { envelopeCodec.decode(bytes) } finally { bytes.fill(0) }
        val header = envelope.header
        envelope.ciphertext.fill(0)
        val trust = deviceTrustService.current()
        val scope = io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value)
        var origin = protectedSendOriginStore.find(scope, header.contentId.value)
        // A bound encrypted producer record can identify an observed summary carrier. An
        // unbound header cannot stand in for the SDK's authenticated Matrix sender.
        val sender = senderAccountIdHash ?: origin?.takeIf { it.eventId == eventIdentity && it.carrierSha256 == accountIdHash(carrier) }
            ?.accountIdHash ?: throw ContentProtectionException.KmsUnavailable()
        val input = ProtectedEventOwnershipResolver.Input(
            accountHash, matrixDeviceHash, trust.installationId, sender,
            header.senderInstallationId, roomHash, eventIdentity.takeIf(ProtectedEventOwnershipResolver::isCanonicalEvent), transactionId,
            header.contentId.value, header.kind, accountIdHash(carrier), header.roomEpoch?.generation,
        )
        origin = awaitOriginalSendBinding(input, origin)
        var recoveredOrigin = false
        if ((origin == null || ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, origin)) &&
            sender == accountHash && header.senderInstallationId == trust.installationId &&
            ProtectedEventOwnershipResolver.isCanonicalEvent(eventIdentity)
        ) {
            // A legacy producer journal may be absent after an upgrade. Recover only the
            // original SEND identity, using the current signed authority and live Matrix
            // binding. This request admits no new operation and grants no content lease.
            val context = lifecycleWork.registrationTransport(
                // Signed legacy-origin recovery is metadata repair, including visible old rows.
                // Keep its queue outside the shared slots used by a current original send.
                isForeground = false,
            ) {
                appOperationalStateCoordinator.protectedEventOrigin(
                    io.element.android.security.dataprotection.api.ProtectedEventOriginQuery(
                        accountHash, matrixDeviceHash, roomHash, accountIdHash(eventIdentity), accountIdHash(carrier),
                        header.immutableContextSha256.toHex(), header.contentId.value, header.kind.name,
                        header.kmsShareReference.value, header.roomEpoch?.generation,
                    ),
                )
            }
            if (context != null) {
                // An existing producer record must reconcile to the SAME original operation.
                // A signed lookup cannot replace a conflicting owner or relax carrier checks.
                if (origin != null && (origin.operationId != context.operationId || origin.lifecycleId != context.lifecycleId)) {
                    throw ContentProtectionException.AccessDenied()
                }
                origin = ProtectedEventOwnershipResolver.Origin(
                    context.operationId, context.lifecycleId, accountHash, matrixDeviceHash, trust.installationId,
                    roomHash, eventIdentity, origin?.transactionId ?: transactionId, header.contentId.value, header.kind, accountIdHash(carrier),
                    header.roomEpoch?.generation,
                )
                recoveredOrigin = true
            }
        }
        val result = ProtectedEventOwnershipResolver.resolve(input, origin)
        return when (result.ownership) {
            ProtectedEventOwnershipResolver.Ownership.OWN_LOCAL_SEND -> withContext(checkNotNull(result.sendContext)) {
                val value = read()
                // Persist a recovered carrier hash only after normal KMS admission and
                // AEAD verification succeeded; the lookup merely echoes that hash.
                if (recoveredOrigin) protectedSendOriginStore.remember(scope, checkNotNull(result.origin))
                value
            }
            ProtectedEventOwnershipResolver.Ownership.OWN_OTHER_DEVICE,
            ProtectedEventOwnershipResolver.Ownership.TRUE_REMOTE_RECEIVE ->
                withReceiveLifecycleOperation(eventIdentity, releaseIntent, block = read)
            ProtectedEventOwnershipResolver.Ownership.UNKNOWN -> {
                Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_EVENT_ORIGIN_UNRESOLVED reason=%s", result.reason.name)
                if (ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, origin) ||
                    origin == null && sender == accountHash && header.senderInstallationId == trust.installationId
                ) throw ContentProtectionException.SendBindingPending()
                throw ContentProtectionException.AccessDenied()
            }
        }
    }

    private fun scheduleCallRecordRegistration(eventItem: MatrixTimelineItem.Event) {
        if (!protectedPreviewAuthorizationGate.canAttemptPreview()) return
        if (!eventItem.event.isOwn) return
        val eventId = eventItem.eventId?.value ?: return
        val registrationKey = accountIdHash("call-record:$accountHash:$matrixDeviceHash:$roomHash:$eventId")
        val shouldRegister = lifecycleWork.claimRegistration(registrationKey)
        if (!shouldRegister) return
        coroutineScope.launch {
            val registered = runCatching {
                lifecycleWork.registrationWork(isLiveSend = false) {
                    lifecycleWork.registrationTransport { contentProtectionService.registerCallRecord(roomId.value, eventId) }
                }
            }.getOrDefault(false)
            lifecycleWork.finishRegistration(registrationKey, registered)
            if (!registered) {
                Timber.tag(PROTECTED_DIAGNOSTIC_TAG).w("YZCP_CALL_RECORD_LIFECYCLE_RETRY")
            }
        }.invokeOnCompletion { failure ->
            if (failure != null) lifecycleWork.abortRegistration(registrationKey)
        }
    }

    private suspend fun lifecycleContentIdsForDestruction(message: MessageContent): Set<ContentId> {
        val contentIds = lifecycleContentIds(message).toMutableSet()
        protectedHeader(message)?.takeIf { it.kind == ContentKind.TEXT && it.lifecycle.mode == MessageLifecycleMode.FOLLOW_ROOM }?.let {
            contentIds += captionLineageCoordinator.textContentIdsForDestruction(it.contentId)
        }
        val attachment = message.type as? MessageTypeWithAttachment ?: return contentIds
        val rootContentId = protectedCaptionContentId(attachment) ?: return contentIds
        contentIds += captionLineageCoordinator.contentIdsForDestruction(rootContentId)
        return contentIds
    }

    private fun lifecycleContentIds(message: MessageContent): Set<ContentId> {
        val contentIds = linkedSetOf<ContentId>()
        protectedHeader(message)?.let { contentIds += it.contentId }
        val attachment = message.type as? MessageTypeWithAttachment ?: return contentIds
        contentIds += attachment.filename.protectedMediaContentIds()
        protectedCaptionContentId(attachment)?.let { contentIds += it }
        return contentIds
    }

    private fun protectedCaptionContentId(attachment: MessageTypeWithAttachment): ContentId? {
        val captionCarrier = attachment.caption?.takeIf(String::isProtectedCaptionCarrier) ?: return null
        val encodedCaption = captionCarrier.removePrefix(PROTECTED_CAPTION_PREFIX).decodeEnvelope()
        val captionEnvelope = try {
            envelopeCodec.decode(encodedCaption)
        } finally {
            encodedCaption.fill(0)
        }
        return try {
            require(captionEnvelope.header.kind == ContentKind.MEDIA_CAPTION)
            captionEnvelope.header.contentId
        } finally {
            captionEnvelope.ciphertext.fill(0)
        }
    }

    /**
     * Returns every content slice governed by the same one-time viewing action. Media captions are protected
     * independently, so opening only the media/thumbnail sessions would either reject the title or create a
     * second unbound read path. The transport's burn marker binds all slices to one UI action and lifecycle.
     */
    private fun burnAfterReadContentIds(message: MessageContent): Set<ContentId> {
        protectedHeader(message)
            ?.takeIf { it.lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ }
            ?.let { return setOf(it.contentId) }
        return if (message.burnAfterReadMediaContentIds().isNotEmpty()) {
            lifecycleContentIds(message)
        } else {
            emptySet()
        }
    }

    private fun protectedMediaUris(message: MessageContent): Set<String> = buildSet {
        fun addMxc(value: String?) {
            value?.takeIf { it.startsWith("mxc://") }?.let(::add)
        }
        when (val type = message.type) {
            is ImageMessageType -> {
                addMxc(type.source.safeUrl)
                addMxc(type.info?.thumbnailSource?.safeUrl)
            }
            is AudioMessageType -> addMxc(type.source.safeUrl)
            is VoiceMessageType -> addMxc(type.source.safeUrl)
            is VideoMessageType -> {
                addMxc(type.source.safeUrl)
                addMxc(type.info?.thumbnailSource?.safeUrl)
            }
            is FileMessageType -> {
                addMxc(type.source.safeUrl)
                addMxc(type.info?.thumbnailSource?.safeUrl)
            }
            is io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType -> {
                addMxc(type.source.safeUrl)
                addMxc(type.info?.thumbnailSource?.safeUrl)
            }
            else -> Unit
        }
    }

    private suspend fun protect(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        contentId: ContentId?,
        audienceAccountIdHashes: Set<String>? = null,
        lifecycle: ContentLifecycle = ContentLifecycle(),
        durableIntentSha256: String? = null,
        beforeKmsStore: (suspend () -> Unit)? = null,
    ): PreparedProtectedContent {
        val echoOperation = currentLifecycleOperationContext()?.takeIf { PendingProtectedPayloadCache.acceptsOwner(it, kind) }
        check(durableIntentSha256 == null || echoOperation != null)
        val echoAuthorizationRevision = authorizationRevision()
        val echoPreviewRevision = protectedPreviewAuthorizationGate.authorizationRevision.value
        val request = LifecycleStageTiming.measure("send_policy_and_recipients", contentId?.value.orEmpty()) {
            val fresh = protectionRequest(kind, contentId, audienceAccountIdHashes, lifecycle)
            val parentExpiry = contentId?.let { captionLineageCoordinator.parentExpiry(it) }
            if (parentExpiry == null) fresh else {
                require(parentExpiry > clock.epochMillis())
                fresh.copy(policy = fresh.policy.copy(expiresAtEpochMillis = minOf(parentExpiry, fresh.policy.expiresAtEpochMillis)))
            }
        }
        LifecycleStageTiming.point("send_content_bind", request.contentId.value)
        val plaintext = LifecycleStageTiming.measure("send_payload_encode", request.contentId.value) {
            json.encodeToString(payload).encodeToByteArray()
        }
        val envelope = try {
            if (beforeKmsStore == null) contentProtectionService.protectBytes(request, plaintext)
            else contentProtectionService.protectBytes(request, plaintext, beforeKmsStore)
        } catch (failure: Exception) {
            plaintext.fill(0)
            request.recipients.forEach { it.publicKeyDer.fill(0) }
            throw failure
        }
        val encodedEnvelope = try {
            LifecycleStageTiming.measure("send_envelope_encode", request.contentId.value) { envelopeCodec.encode(envelope) }
        } catch (failure: Exception) {
            plaintext.fill(0)
            envelope.ciphertext.fill(0)
            request.recipients.forEach { it.publicKeyDer.fill(0) }
            throw failure
        }
        val transportEnvelope = Base64.getEncoder().encodeToString(encodedEnvelope)
        return try {
            rememberOriginatingOperation(request.contentId)
            if (echoOperation != null) {
                val origin = ProtectedEventOwnershipResolver.Origin(
                        echoOperation.operationId, echoOperation.lifecycleId, accountHash, matrixDeviceHash,
                        envelope.header.senderInstallationId, roomHash, null, null, request.contentId.value,
                        kind, accountIdHash(transportEnvelope), envelope.header.roomEpoch?.generation,
                    )
                val scope = io.element.android.security.contentprotection.api.ContentVersionLineageScope(vaultId, roomId.value)
                if (durableIntentSha256 != null) {
                    protectedSendOriginStore.rememberPrepared(scope, origin, transportEnvelope, durableIntentSha256)
                } else {
                    protectedSendOriginStore.remember(scope, origin)
                }
            }
            pendingPayloadCache.put(envelope.pendingPayloadKey(), plaintext, echoOperation?.let { operation ->
                PendingProtectedPayloadCache.Owner(
                    operation,
                    PendingProtectedPayloadCache.Identity(
                        matrixDeviceHash, envelope.header.senderInstallationId, accountIdHash(transportEnvelope),
                        echoAuthorizationRevision, echoPreviewRevision,
                    ),
                    envelope.header.policy.expiresAtEpochMillis,
                    kind,
                )
            })
            PreparedProtectedContent(
                encodedEnvelope = transportEnvelope,
                contentId = request.contentId,
            )
        } finally {
            plaintext.fill(0)
            encodedEnvelope.fill(0)
            envelope.ciphertext.fill(0)
            request.recipients.forEach { it.publicKeyDer.fill(0) }
        }
    }

    private fun io.element.android.security.contentprotection.api.ProtectedContentEnvelope.pendingPayloadKey() =
        PendingProtectedPayloadCache.Key(
            contentId = header.contentId,
            accountIdHash = accountHash,
            roomId = roomId.value,
            ciphertextHash = MessageDigest.getInstance("SHA-256").digest(ciphertext).toHex(),
        )

    private suspend fun protectionRequest(
        kind: ContentKind,
        contentId: ContentId? = null,
        requestedAudienceAccountIdHashes: Set<String>? = null,
        lifecycle: ContentLifecycle = ContentLifecycle(),
    ): ProtectContentRequest {
        val active = policyProvider.current().policy
        val now = clock.epochMillis()
        val activeRoomAudience = LifecycleStageTiming.measure("send_member_projection", contentId?.value.orEmpty()) {
            activeMemberAccountIdHashes() + accountHash
        }
        val audienceAccountIdHashes = (requestedAudienceAccountIdHashes ?: activeRoomAudience).toSortedSet()
        require(accountHash in audienceAccountIdHashes)
        require(audienceAccountIdHashes.all { it in activeRoomAudience })
        require(audienceAccountIdHashes.size in 1..ContentProtectionFormat.MAX_ROOM_AUDIENCE_ACCOUNTS)
        val audienceAccounts = audienceAccountIdHashes.toList()
        val recipientsByInstallation = linkedMapOf<String, io.element.android.security.contentprotection.api.DeviceShareRecipient>()
        val (roomLifecycle, snapshots) = readProtectedSendPrerequisites(
            resolveRoom = {
                LifecycleStageTiming.measure("send_room_contract", contentId?.value.orEmpty()) {
                    contentProtectionService.resolveRoomLifecycle(roomId.value)
                }
            },
            resolveRecipients = {
                LifecycleStageTiming.measure("send_recipient_registry", contentId?.value.orEmpty()) {
                    recipientRegistry.approvedRecipients(audienceAccountIdHashes)
                }
            },
        )
        val trust = deviceTrustService.current()
        require(snapshots.keys == audienceAccountIdHashes)
        for (recipientAccountHash in audienceAccounts) {
            val snapshot = requireNotNull(snapshots[recipientAccountHash])
            require(snapshot.accountIdHash == recipientAccountHash && snapshot.recipients.isNotEmpty())
            snapshot.recipients.forEach { recipient ->
                val existing = recipientsByInstallation.putIfAbsent(recipient.installationId, recipient)
                require(existing == null || existing.keyId == recipient.keyId && existing.algorithm == recipient.algorithm &&
                    existing.allowsBoundedOffline == recipient.allowsBoundedOffline &&
                    java.security.MessageDigest.isEqual(existing.publicKeyDer, recipient.publicKeyDer))
            }
        }
        val recipients = recipientsByInstallation.values.toList()
        require(recipients.size in 1..ContentProtectionFormat.MAX_ROOM_AUTHORIZED_INSTALLATIONS)
        val expiresAt = resolveProtectedContentExpiry(
            nowEpochMillis = now,
            roomExpiresAtEpochMillis = roomLifecycle.messageExpiresAtEpochMillis,
            policyExpiresAtEpochMillis = active.expiresAtEpochMillis,
            policyContentRetentionMillis = active.contentRetentionMillis,
            groupBurnAfterRead = lifecycle.mode == MessageLifecycleMode.BURN_AFTER_READ &&
                lifecycle.conversationKind == ConversationKind.GROUP,
            roomLifecycleProtocolVersion = roomLifecycle.protocolVersion,
        )
        return ProtectContentRequest(
            contentId = contentId ?: ContentId(UUID.randomUUID().toString()),
            vaultId = vaultId,
            kind = kind,
            policy = ContentPolicyReference(PolicyId(active.policyId), active.version, expiresAt),
            accountIdHash = accountHash,
            roomId = roomId.value,
            senderInstallationId = trust.installationId,
            createdAtEpochMillis = now,
            audienceAccountIdHashes = audienceAccountIdHashes,
            recipients = recipients,
            offlineLeaseMode = resolveOfflineLeaseMode(
                lifecycleMode = lifecycle.mode,
                policyMode = active.offlineAccessMode,
                policyMaximumOfflineMillis = active.maximumOfflineMillis,
                roomMaximumOfflineMillis = roomLifecycle.maximumOfflineMillis,
                clientMaximumOfflineMillis = kmsClientConfigurationProvider.configuration()?.maximumOfflineLeaseMillis ?: 0L,
                deviceAllowsBoundedOffline = trust.trustState != DeviceTrustState.MANAGED,
                recipientsAllowBoundedOffline = recipients.all { it.allowsBoundedOffline },
            ),
            lifecycle = lifecycle,
            roomLifecycle = roomLifecycle,
        )
    }

    private fun decodePayload(
        payload: ProtectedMatrixPayload,
        outer: MessageContent,
        protectedPolicyId: String,
        protectedPolicyVersion: Long,
    ): EventContent = when (payload.payloadType) {
        PAYLOAD_TEXT -> {
            val formatted = payload.htmlBody?.let { FormattedBody(MessageFormat.HTML, it) }
            val type = if (payload.messageType == MsgType.MSG_TYPE_EMOTE.name) {
                EmoteMessageType(payload.body, formatted)
            } else {
                TextMessageType(payload.body, formatted)
            }
            outer.copy(
                body = payload.body,
                inReplyTo = payload.targetEventId?.let { targetEventId ->
                    InReplyTo.NotLoaded(EventId(targetEventId))
                } ?: outer.inReplyTo,
                type = type,
                protectedMessageStatus = null,
                isContentProtectionAuthorized = true,
            )
        }
        PAYLOAD_LOCATION -> outer.copy(
            body = payload.body,
            type = LocationMessageType(
                body = payload.body,
                geoUri = requireNotNull(payload.geoUri),
                description = payload.description,
                assetType = payload.assetType?.let(AssetType::valueOf),
            ),
            protectedMessageStatus = null,
            isContentProtectionAuthorized = true,
        )
        PAYLOAD_POLL_START -> {
            val answers = payload.pollAnswers.map { PollAnswer(it.id, it.text) }
            require(answers.size >= 2 && answers.map(PollAnswer::id).toSet().size == answers.size)
            PollContent(
                question = requireNotNull(payload.question).takeIf(String::isNotBlank)
                    ?: throw SecurityException("Protected poll question is empty."),
                kind = PollKind.valueOf(requireNotNull(payload.pollKind)),
                maxSelections = requireNotNull(payload.maxSelections).toULong(),
                answers = persistentListOf(*answers.toTypedArray()),
                votes = persistentMapOf(),
                endTime = null,
                isEdited = false,
                threadInfo = outer.threadInfo,
            )
        }
        PAYLOAD_LIVE_LOCATION_START -> LiveLocationContent(
            isLive = true,
            description = payload.description,
            startTimestamp = requireNotNull(payload.timestamp),
            timeout = requireNotNull(payload.timeoutMillis),
            assetType = payload.assetType?.let(AssetType::valueOf),
            locations = payload.geoUri?.let { geoUri ->
                listOf(
                LiveLocationInfo(
                    description = payload.description,
                    geoUri = geoUri,
                    timestamp = requireNotNull(payload.timestamp),
                )
            )
            } ?: emptyList(),
            protectedAggregateId = requireNotNull(payload.aggregateId),
        )
        PAYLOAD_RELAY_START -> {
            val title = requireNotNull(payload.question).takeIf { it.isNotBlank() && it.length <= MAX_RELAY_TITLE_LENGTH }
                ?: throw SecurityException("Protected relay title is invalid.")
            val entries = payload.relayEntry
                ?.takeIf { it.isNotBlank() && it.length <= MAX_RELAY_ENTRY_LENGTH }
                ?.let(::listOf)
                .orEmpty()
            val body = relayBody(title, entries, closed = false)
            outer.copy(
                body = body,
                type = TextMessageType(body, formatted = null),
                protectedMessageStatus = null,
                isContentProtectionAuthorized = true,
                protectedRelayInitialEntryId = payload.relayEntryId,
            )
        }
        PAYLOAD_OPERATION -> ProtectedOperationContent(
            protocolVersion = payload.version,
            operationId = requireNotNull(payload.operationId),
            operation = ProtectedOperationContent.Operation.valueOf(requireNotNull(payload.operation)),
            targetEventId = requireNotNull(payload.targetEventId),
            aggregateId = payload.aggregateId,
            emoji = payload.emoji,
            enabled = payload.enabled,
            question = payload.question,
            pollKind = payload.pollKind?.let(PollKind::valueOf),
            maxSelections = payload.maxSelections?.toULong(),
            pollAnswers = payload.pollAnswers.map { PollAnswer(it.id, it.text) },
            selectedAnswerIds = payload.selectedAnswerIds,
            pollVotes = payload.pollVotes.mapValues { (_, voters) -> voters.map(::UserId) },
            caption = payload.caption,
            formattedCaption = payload.formattedCaption,
            geoUri = payload.geoUri,
            description = payload.description,
            timestamp = payload.timestamp,
            relayEntry = payload.relayEntry,
            relayEntryId = payload.relayEntryId,
            relayExpectedRevision = payload.relayExpectedRevision,
            callLifecycleState = payload.callLifecycleState?.let(CallLifecycleState::valueOf),
            durationMillis = payload.durationMillis,
        )
        PAYLOAD_SEGMENTED_VIDEO -> {
            if (!java.lang.Boolean.getBoolean(SEGMENTED_RECEIVE_PROPERTY)) {
                throw SecurityException("Segmented protected-media receive is disabled.")
            }
            val segmentedVideo = requireNotNull(payload.segmentedVideo)
            if (segmentedVideo.contentContractId != protectedPolicyId) {
                throw SecurityException("Segmented protected-media contract mismatched the authenticated envelope policy.")
            }
            val candidate = segmentedVideo.toCandidate(protectedPolicyVersion).validated()
            val actualRoomHash = MessageDigest.getInstance("SHA-256").digest(roomId.value.encodeToByteArray()).toHex()
            if (candidate.roomIdSha256Hex != actualRoomHash) {
                throw SecurityException("Segmented protected-media room binding mismatched the authenticated event.")
            }
            outer.copy(
                body = payload.body.ifBlank { payload.attachmentName ?: "Protected video" },
                type = VideoMessageType(
                    filename = payload.attachmentName ?: "protected-video.mp4",
                    caption = payload.caption,
                    formattedCaption = payload.formattedCaption?.let { FormattedBody(MessageFormat.HTML, it) },
                    source = MediaSource(candidate.init.locator.mxcUri),
                    info = VideoInfo(
                        duration = candidate.durationUs.microseconds,
                        height = null,
                        width = null,
                        mimetype = "video/mp4",
                        size = candidate.totalPlainBytes,
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                    ),
                    segmentedVideo = candidate,
                ),
                protectedMessageStatus = null,
                isContentProtectionAuthorized = true,
            )
        }
        else -> throw SecurityException("Unsupported protected Matrix payload type.")
    }

    private fun relayBody(title: String, entries: List<String>, closed: Boolean): String = buildString {
        append("接龙：")
        append(title)
        append("\n回复本消息即可参与；发起人回复“结束接龙”可结束")
        entries.forEachIndexed { index, entry ->
            append('\n')
            append(index + 1)
            append(". ")
            append(entry)
        }
        if (closed) append("\n（已结束）")
    }

    private fun String.decodeEnvelope(): ByteArray = try {
        require(length <= MAX_BASE64_ENVELOPE_CHARS)
        Base64.getDecoder().decode(this)
    } catch (failure: Exception) {
        throw SecurityException("Invalid protected Matrix envelope.", failure)
    }

    private companion object {
        const val PAYLOAD_TEXT = "TEXT"
        const val PAYLOAD_LOCATION = "LOCATION"
        const val PAYLOAD_POLL_START = "POLL_START"
        const val PAYLOAD_LIVE_LOCATION_START = "LIVE_LOCATION_START"
        const val PAYLOAD_RELAY_START = "RELAY_START"
        const val PAYLOAD_OPERATION = "OPERATION"
        const val PAYLOAD_CAPTION = "CAPTION"
        const val PAYLOAD_SEGMENTED_VIDEO = "SEGMENTED_VIDEO"
        const val SEGMENTED_RECEIVE_PROPERTY = "io.yizhe.next.segmented_media_receive"
        const val MEDIA_ALBUM_OPERATION = "MEDIA_ALBUM"
        const val MAX_BASE64_ENVELOPE_CHARS = 32 * 1024 * 1024
        const val MAX_RELAY_TITLE_LENGTH = 500
        const val MAX_RELAY_ENTRY_LENGTH = 2_000
        const val PROTECTED_DIAGNOSTIC_TAG = "YiZheProtectedContent"
        const val PERFORMANCE_TAG = "YiZheTimelinePerf"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private fun timelinePerf(stage: String, durationMillis: Long? = null) {
        if (durationMillis == null) {
            Timber.tag(PERFORMANCE_TAG).d("stage=%s", stage)
        } else {
            Timber.tag(PERFORMANCE_TAG).d("stage=%s duration_ms=%d", stage, durationMillis)
        }
    }
}

internal fun isCanonicalProtectedEventIdentity(value: String): Boolean =
    value.startsWith("$") && value.length in 2..512 && value.none(Char::isWhitespace)

private fun ProtectedMediaAlbum.validated(): ProtectedMediaAlbum = apply {
    require(id.matches(Regex("[A-Za-z0-9_-]{16,64}")))
    require(count in 2..100)
    require(index in 0 until count)
}

/**
 * Reuses one LC-13 receive operation for the same Matrix event projection on the same device.
 * Timeline recomposition and authorization refresh may retry decryption, but must not create a
 * second authority operation for an event that is already pending recovery or receipts.
 */
internal fun receiveLifecycleOperationId(
    accountIdHash: String,
    matrixDeviceIdHash: String,
    roomIdHash: String,
    eventIdHash: String,
): String {
    val identity = listOf(
        FullAppLifecycleId.LC_13.wireValue,
        "receive",
        accountIdHash,
        matrixDeviceIdHash,
        roomIdHash,
        eventIdHash,
    ).joinToString("\u0000").encodeToByteArray()
    return "lc13-receive-${MessageDigest.getInstance("SHA-256").digest(identity).toHex()}"
}

internal fun pendingBurnConsumptionForOpen(
    existing: PendingBurnConsumption?,
    key: BurnConsumptionKey,
    installationId: String,
    nowEpochMillis: Long,
    newOperationId: String,
    newSessionId: String,
    operationDeadlineEpochMillis: Long,
): PendingBurnConsumption {
    if (existing == null) {
        return PendingBurnConsumption(
            key = key,
            rootOperationId = null,
            viewOperationId = newOperationId,
            sessionId = newSessionId,
            installationId = installationId,
            policySnapshotSha256 = null,
            operationDeadlineEpochMillis = operationDeadlineEpochMillis,
            state = BurnConsumptionState.OPENING,
            viewExpiresAtEpochMillis = null,
            endReason = null,
            lastAttemptAtEpochMillis = nowEpochMillis,
            failureCode = null,
        )
    }
    check(existing.key == key) { "Burn consumption identity changed." }
    check(existing.installationId == installationId) {
        "Burn consumption belongs to another installation."
    }
    check(existing.state in setOf(BurnConsumptionState.OPENING, BurnConsumptionState.VIEWING)) {
        "Burn consumption is already ending or terminal."
    }
    return existing.copy(lastAttemptAtEpochMillis = nowEpochMillis, failureCode = null)
}

private const val BURN_LIFECYCLE_OPERATION_TIMEOUT_MILLIS = 13 * 60 * 60_000L

internal fun pendingBurnConsumptionsToResume(
    checkpoints: List<PendingBurnConsumption>,
    accountIdHash: String,
    roomIdHash: String,
    nowEpochMillis: Long,
): List<PendingBurnConsumption> = checkpoints.filter {
    it.key.accountIdHash == accountIdHash &&
        it.key.roomIdHash == roomIdHash &&
        (it.state == BurnConsumptionState.END_PENDING ||
            it.state == BurnConsumptionState.VIEWING &&
            (it.viewExpiresAtEpochMillis ?: Long.MAX_VALUE) <= nowEpochMillis)
}

internal fun pendingBurnConsumptionForEnd(
    existing: PendingBurnConsumption,
    reason: BurnAfterReadEndReason,
    nowEpochMillis: Long,
): PendingBurnConsumption {
    check(existing.state in setOf(BurnConsumptionState.VIEWING, BurnConsumptionState.END_PENDING)) {
        "Burn consumption is not eligible for terminal continuation."
    }
    return existing.copy(
        state = BurnConsumptionState.END_PENDING,
        endReason = existing.endReason ?: reason.name,
        lastAttemptAtEpochMillis = nowEpochMillis,
        failureCode = null,
    )
}

internal fun resolveOfflineLeaseMode(
    lifecycleMode: MessageLifecycleMode,
    policyMode: OfflineAccessMode,
    policyMaximumOfflineMillis: Long,
    roomMaximumOfflineMillis: Long,
    clientMaximumOfflineMillis: Long,
    deviceAllowsBoundedOffline: Boolean = true,
    recipientsAllowBoundedOffline: Boolean = true,
): OfflineLeaseMode = when {
    !deviceAllowsBoundedOffline || !recipientsAllowBoundedOffline -> OfflineLeaseMode.ONLINE_REQUIRED
    lifecycleMode == MessageLifecycleMode.BURN_AFTER_READ -> OfflineLeaseMode.ONLINE_REQUIRED
    policyMode == OfflineAccessMode.ONLINE_REQUIRED -> OfflineLeaseMode.ONLINE_REQUIRED
    clientMaximumOfflineMillis <= 0 -> OfflineLeaseMode.ONLINE_REQUIRED
    clientMaximumOfflineMillis > policyMaximumOfflineMillis -> OfflineLeaseMode.ONLINE_REQUIRED
    clientMaximumOfflineMillis > roomMaximumOfflineMillis -> OfflineLeaseMode.ONLINE_REQUIRED
    else -> OfflineLeaseMode.BOUNDED_OFFLINE
}

internal class PendingProtectedPayloadCache(
    private val nowEpochMillis: () -> Long,
    private val maximumEntries: Int = 64,
    private val ttlMillis: Long = 60_000,
) {
    data class Key(
        val contentId: ContentId,
        val accountIdHash: String,
        val roomId: String,
        val ciphertextHash: String,
    )

    data class Identity(
        val matrixDeviceIdHash: String,
        val installationId: String,
        val envelopeHash: String,
        val authorizationRevision: Long,
        val previewRevision: Long,
    )
    data class Owner(
        val operation: LifecycleOperationContext,
        val identity: Identity,
        val expiresAtEpochMillis: Long,
        val contentKind: ContentKind = ContentKind.TEXT,
    )
    class Ticket internal constructor(val key: Key, val owner: Owner, internal val token: Any)
    private data class Entry(val storedAtEpochMillis: Long, val plaintext: ByteArray, val owner: Owner?, val token: Any = Any())

    private val entries = LinkedHashMap<Key, Entry>()

    fun put(key: Key, plaintext: ByteArray, owner: Owner? = null) = synchronized(entries) {
        purgeExpiredLocked()
        entries.remove(key)?.plaintext?.fill(0)
        entries[key] = Entry(nowEpochMillis(), plaintext.copyOf(), owner)
        while (entries.size > maximumEntries) {
            val oldest = entries.entries.first()
            entries.remove(oldest.key)?.plaintext?.fill(0)
        }
    }

    fun get(key: Key): ByteArray? = synchronized(entries) {
        purgeExpiredLocked()
        entries[key]?.plaintext?.copyOf()
    }

    fun ownership(key: Key, identity: Identity): Ticket? = synchronized(entries) {
        purgeExpiredLocked()
        val entry = entries[key] ?: return null
        val owner = entry.owner ?: return null
        if (!acceptsOwner(owner.operation, owner.contentKind) || owner.identity != identity ||
            nowEpochMillis() >= owner.expiresAtEpochMillis) return null
        Ticket(key, owner, entry.token)
    }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(entries) {
        purgeExpiredLocked()
        entries[ticket.key]?.token === ticket.token && nowEpochMillis() < ticket.owner.expiresAtEpochMillis
    }

    fun get(ticket: Ticket): ByteArray? = synchronized(entries) {
        if (isCurrent(ticket)) entries[ticket.key]?.plaintext?.copyOf() else null
    }

    fun remove(contentId: ContentId) = synchronized(entries) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key.contentId == contentId) {
                iterator.remove()
                entry.plaintext.fill(0)
            }
        }
    }

    fun remove(key: Key) = synchronized(entries) {
        entries.remove(key)?.plaintext?.fill(0)
    }

    fun clear() = synchronized(entries) {
        entries.values.forEach { entry -> entry.plaintext.fill(0) }
        entries.clear()
    }

    fun clearLiveLocation(accountIdHash: String, roomId: String) = synchronized(entries) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key.accountIdHash == accountIdHash && key.roomId == roomId &&
                entry.owner?.contentKind in setOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION)) {
                iterator.remove()
                entry.plaintext.fill(0)
            }
        }
    }

    private fun purgeExpiredLocked() {
        val cutoff = nowEpochMillis() - ttlMillis
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.storedAtEpochMillis <= cutoff) {
                iterator.remove()
                entry.plaintext.fill(0)
            }
        }
    }

    companion object {
        internal fun acceptsOwner(operation: LifecycleOperationContext, kind: ContentKind): Boolean = when (operation.lifecycleId) {
            FullAppLifecycleId.LC_13 -> kind == ContentKind.TEXT
            FullAppLifecycleId.LC_17 -> kind in setOf(ContentKind.LOCATION, ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION)
            FullAppLifecycleId.LC_15 -> kind in INTERACTION_CONTENT_KINDS
            FullAppLifecycleId.LC_20 -> kind == ContentKind.CALL_OPERATION
            else -> false
        }
    }
}

private fun Exception.diagnosticCode(): String = when (this) {
    is StageThreeGateDenied -> "STAGE_THREE_${reason.name}"
    is ContentProtectionException.InvalidEnvelope -> "INVALID_ENVELOPE"
    is ContentProtectionException.DeviceKeyUnavailable -> "DEVICE_KEY_UNAVAILABLE"
    is ContentProtectionException.KmsUnavailable -> "KMS_UNAVAILABLE"
    is ContentProtectionException.SendBindingPending -> "SEND_BINDING_PENDING"
    is ContentProtectionException.RecoveryApprovalRequired -> "RECOVERY_APPROVAL_REQUIRED"
    is ContentProtectionException.AccessDenied -> "ACCESS_DENIED"
    is ContentProtectionException.Expired -> "EXPIRED"
    is ContentProtectionException.Revoked -> "REVOKED"
    is ContentProtectionException.OfflineLicenseUnavailable -> "OFFLINE_LICENSE_UNAVAILABLE"
    is ContentProtectionException.ClockRollbackDetected -> "CLOCK_ROLLBACK"
    is ContentProtectionException.ReplayRejected -> "REPLAY_REJECTED"
    is ContentProtectionException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
    is IllegalStateException -> "STATE_INVALID"
    is IllegalArgumentException -> "INPUT_INVALID"
    else -> "INTERNAL_FAILURE"
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

object DisabledMatrixProtectedContentAdapter : MatrixProtectedContentAdapter {
    override suspend fun enabled(): Boolean = false
    override suspend fun protectText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = error("disabled")
    override suspend fun protectBurnAfterReadText(
        body: String,
        htmlBody: String?,
        msgType: MsgType,
        asPlainText: Boolean,
    ): PreparedProtectedContent = error("disabled")
    override suspend fun burnAfterReadLifecycle(): ContentLifecycle = error("disabled")
    override suspend fun protectLocation(
        body: String,
        geoUri: String,
        description: String?,
        assetType: AssetType?,
        contentId: ContentId?,
        lifecycle: ContentLifecycle,
    ): PreparedProtectedContent = error("disabled")
    override suspend fun protectInteraction(
        kind: ContentKind,
        payload: ProtectedMatrixPayload,
        audienceAccountIdHashes: Set<String>?,
        contentId: ContentId?,
    ): PreparedProtectedContent = error("disabled")
    override suspend fun protectMedia(file: File, kind: ContentKind, lifecycle: ContentLifecycle): ProtectedMatrixMedia = error("disabled")
    override suspend fun protectCaption(
        caption: String?,
        formattedCaption: String?,
        lifecycle: ContentLifecycle,
        attachmentMetadata: ProtectedAttachmentMetadata?,
        mediaAlbum: ProtectedMediaAlbum?,
    ): PreparedProtectedContent = error("disabled")
    override suspend fun decryptCaption(encodedEnvelope: String): ProtectedCaption = error("disabled")
    override suspend fun destroyPrepared(content: PreparedProtectedContent, reason: ContentAccessDestroyReason): Boolean = false
    override suspend fun destroyMedia(media: ProtectedMatrixMedia, reason: ContentAccessDestroyReason): Boolean = false
    override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem = item
    override suspend fun destroyItem(item: MatrixTimelineItem, reason: ContentAccessDestroyReason): Boolean = false
}

object DisabledMatrixProtectedContentAdapterFactory : MatrixProtectedContentAdapterFactory {
    override fun create(
        sessionId: SessionId,
        deviceId: DeviceId,
        roomId: RoomId,
        activeMemberAccountIdHashes: suspend () -> Set<String>,
        isRoomEncrypted: suspend () -> Boolean,
    ): MatrixProtectedContentAdapter = DisabledMatrixProtectedContentAdapter
}

object DisabledMatrixContentProtectionRuntime : io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime {
    override fun attach() = Unit
    override fun detach() = Unit
    override fun isConnected(): Boolean = false
}

@Serializable
data class ProtectedMatrixPayload(
    @SerialName("v") val version: Int = 1,
    @SerialName("type") val payloadType: String,
    @SerialName("body") val body: String = "",
    @SerialName("html_body") val htmlBody: String? = null,
    @SerialName("message_type") val messageType: String? = null,
    @SerialName("as_plain_text") val asPlainText: Boolean = false,
    @SerialName("geo_uri") val geoUri: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("aggregate_id") val aggregateId: String? = null,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("operation") val operation: String? = null,
    @SerialName("target_event_id") val targetEventId: String? = null,
    @SerialName("emoji") val emoji: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("question") val question: String? = null,
    @SerialName("poll_kind") val pollKind: String? = null,
    @SerialName("max_selections") val maxSelections: Long? = null,
    @SerialName("poll_answers") val pollAnswers: List<ProtectedPollAnswer> = emptyList(),
    @SerialName("selected_answer_ids") val selectedAnswerIds: List<String> = emptyList(),
    @SerialName("poll_votes") val pollVotes: Map<String, List<String>> = emptyMap(),
    @SerialName("caption") val caption: String? = null,
    @SerialName("formatted_caption") val formattedCaption: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_mime_type") val attachmentMimeType: String? = null,
    @SerialName("attachment_size") val attachmentSize: Long? = null,
    @SerialName("timestamp") val timestamp: Long? = null,
    @SerialName("timeout_millis") val timeoutMillis: Long? = null,
    @SerialName("relay_entry") val relayEntry: String? = null,
    @SerialName("relay_entry_id") val relayEntryId: String? = null,
    @SerialName("relay_expected_revision") val relayExpectedRevision: Long? = null,
    @SerialName("call_lifecycle_state") val callLifecycleState: String? = null,
    @SerialName("duration_millis") val durationMillis: Long? = null,
    @SerialName("segmented_video") val segmentedVideo: ProtectedSegmentedVideo? = null,
)

@Serializable
data class ProtectedSegmentedVideo(
    @SerialName("schema") val schema: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("media_version") val mediaVersion: Int,
    @SerialName("room_id_sha256") val roomIdSha256Hex: String,
    @SerialName("content_contract_id") val contentContractId: String,
    @SerialName("expires_at_ms") val expiresAtEpochMillis: Long,
    @SerialName("container") val container: String,
    @SerialName("codecs") val codecs: List<String>,
    @SerialName("duration_us") val durationUs: Long,
    @SerialName("total_plain_bytes") val totalPlainBytes: Long,
    @SerialName("init") val init: ProtectedSegmentedVideoResource,
    @SerialName("segments") val segments: List<ProtectedSegmentedVideoResource>,
    @SerialName("resource_count") val resourceCount: Int,
)

@Serializable
data class ProtectedSegmentedVideoResource(
    @SerialName("resource_id") val resourceId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("kind") val kind: String,
    @SerialName("content_kind") val protectedContentKind: String,
    @SerialName("index") val index: Int,
    @SerialName("start_us") val startUs: Long,
    @SerialName("duration_us") val durationUs: Long,
    @SerialName("plain_length") val plainLength: Long,
    @SerialName("immutable_context_sha256") val immutableContextSha256Hex: String,
    @SerialName("locator") val locator: ProtectedSegmentedVideoLocator,
    @SerialName("independent_random_access") val declaredIndependentRandomAccess: Boolean,
)

@Serializable
data class ProtectedSegmentedVideoLocator(
    @SerialName("authority") val authority: String,
    @SerialName("mxc_uri") val mxcUri: String,
    @SerialName("outer_cipher_sha256") val outerCipherSha256Hex: String,
    @SerialName("outer_cipher_length") val outerCipherLength: Long,
    @SerialName("encrypted_file") val encryptedFile: ProtectedSegmentedVideoEncryptedFile,
)

@Serializable
data class ProtectedSegmentedVideoEncryptedFile(
    @SerialName("v") val version: String,
    @SerialName("alg") val algorithm: String,
    @SerialName("kty") val keyType: String,
    @SerialName("key_ops") val keyOperations: Set<String>,
    @SerialName("ext") val extractable: Boolean,
    @SerialName("k") val keyBase64Url: String,
    @SerialName("iv") val ivBase64: String,
) {
    override fun toString(): String =
        "ProtectedSegmentedVideoEncryptedFile(version=$version, algorithm=$algorithm, keyType=$keyType, " +
            "keyOperations=$keyOperations, extractable=$extractable, keyBase64Url=<redacted>, ivBase64=<redacted>)"
}

private fun ProtectedSegmentedVideo.toCandidate(contentPolicyVersion: Long) = SegmentedVideoCandidate(
    schema, assetId, mediaVersion, roomIdSha256Hex, contentContractId, contentPolicyVersion, expiresAtEpochMillis,
    container, codecs, durationUs, totalPlainBytes, init.toCandidate(), segments.map { it.toCandidate() }, resourceCount,
)

private fun ProtectedSegmentedVideoResource.toCandidate() = SegmentedVideoResource(
    resourceId, contentId, kind, protectedContentKind, index, startUs, durationUs, plainLength, immutableContextSha256Hex,
    SegmentedVideoLocator(
        locator.authority, locator.mxcUri, locator.outerCipherSha256Hex, locator.outerCipherLength,
        SegmentedVideoEncryptedFile(
            locator.encryptedFile.version, locator.encryptedFile.algorithm, locator.encryptedFile.keyType,
            locator.encryptedFile.keyOperations, locator.encryptedFile.extractable,
            locator.encryptedFile.keyBase64Url, locator.encryptedFile.ivBase64,
        ),
    ),
    declaredIndependentRandomAccess,
)

private fun SegmentedVideoCandidate.validated(): SegmentedVideoCandidate = apply {
    val sha256 = Regex("[0-9a-f]{64}")
    val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,255}")
    require(schema == "io.yizhe.protected.segmented_media.v1")
    require(
        mediaVersion == 1 &&
            contentPolicyVersion > 0L &&
            assetId.matches(identifier) &&
            contentContractId.matches(identifier)
    )
    require(roomIdSha256Hex.matches(sha256) && expiresAtEpochMillis > 0L)
    require(container == "fmp4" && codecs.isNotEmpty())
    require(codecs.count { it.matches(Regex("avc1\\.[0-9A-Fa-f]{6}")) } == 1)
    require(codecs.all { it.matches(Regex("avc1\\.[0-9A-Fa-f]{6}")) || it == "mp4a.40.2" })
    require(durationUs > 0L && segments.isNotEmpty() && segments.size <= 512)
    require(resourceCount == segments.size + 2)
    require(init.kind == "MEDIA_INIT" && init.index == 0 && init.startUs == 0L && init.durationUs == 0L)
    require(init.plainLength in 1L..1_048_576L)
    var previousEnd = 0L
    segments.forEachIndexed { expectedIndex, resource ->
        require(resource.kind == "MEDIA_SEGMENT" && resource.index == expectedIndex)
        require(resource.startUs >= previousEnd && resource.durationUs > 0L)
        previousEnd = Math.addExact(resource.startUs, resource.durationUs)
        require(previousEnd <= durationUs && resource.plainLength in 1L..2_097_152L)
    }
    val resources = listOf(init) + segments
    require(resources.sumOf { it.plainLength } == totalPlainBytes)
    require(resources.map { it.resourceId }.toSet().size == resources.size)
    require(resources.map { it.contentId }.toSet().size == resources.size)
    require(resources.map { it.locator.mxcUri }.toSet().size == resources.size)
    resources.forEach { resource ->
        require(resource.resourceId.matches(identifier) && resource.contentId.matches(identifier))
        require(resource.protectedContentKind == "VIDEO")
        require(resource.immutableContextSha256Hex.matches(sha256))
        with(resource.locator) {
            require(authority == "MATRIX_MEDIA" && mxcUri.matches(Regex("mxc://[^/\\s]+/[^\\s]+")))
            require(outerCipherSha256Hex.matches(sha256) && outerCipherLength in 1L..2_200_000L)
            require(encryptedFile.version == "v2" && encryptedFile.algorithm == "A256CTR")
            require(encryptedFile.keyType == "oct" && encryptedFile.extractable)
            require(encryptedFile.keyOperations == setOf("encrypt", "decrypt"))
            require(encryptedFile.keyBase64Url.isNotBlank() && encryptedFile.ivBase64.isNotBlank())
        }
    }
}

@Serializable
data class ProtectedPollAnswer(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
)

private val INTERACTION_CONTENT_KINDS = setOf(
    ContentKind.REACTION,
    ContentKind.POLL,
    ContentKind.POLL_OPERATION,
    ContentKind.MEDIA_CAPTION,
    ContentKind.FORWARD,
    ContentKind.LIVE_LOCATION,
    ContentKind.LIVE_LOCATION_OPERATION,
    ContentKind.RELAY,
    ContentKind.RELAY_OPERATION,
    ContentKind.CALL_OPERATION,
)

internal val OPERATION_CONTENT_KINDS = setOf(
    ContentKind.REACTION,
    ContentKind.MEDIA_CAPTION,
    ContentKind.POLL_OPERATION,
    ContentKind.LIVE_LOCATION_OPERATION,
    ContentKind.RELAY_OPERATION,
    ContentKind.CALL_OPERATION,
)
