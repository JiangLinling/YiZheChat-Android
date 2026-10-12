/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.A_TRANSACTION_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID_2
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixProtectedContentProjectionTest {
    @Test
    fun `protected attachment without caption carrier does not require caption decryption shell`() {
        val content = aMessageContent(
            messageType = ImageMessageType(
                filename = ".yizhe-content1234567890.yzm",
                caption = null,
                formattedCaption = null,
                source = MediaSource("mxc://example/image"),
                info = null,
            )
        )

        assertThat(content.hasProtectedCaptionCarrier()).isFalse()
    }

    @Test
    fun `protected attachment with caption carrier requires caption decryption shell`() {
        val content = aMessageContent(
            messageType = ImageMessageType(
                filename = ".yizhe-content1234567890.yzm",
                caption = PROTECTED_CAPTION_PREFIX + "encrypted-envelope",
                formattedCaption = null,
                source = MediaSource("mxc://example/image"),
                info = null,
            )
        )

        assertThat(content.hasProtectedCaptionCarrier()).isTrue()
    }

    @Test
    fun `safe shell prefetch visits the complete confirmed own message burst in order`() {
        val ownMessages = (1..10).map { index ->
            MatrixTimelineItem.Event(
                uniqueId = UniqueId("own-$index"),
                event = anEventTimelineItem(
                    isOwn = true,
                    isRemote = true,
                    content = aMessageContent(body = "protected-$index"),
                ),
            )
        }
        val foreignMessage = MatrixTimelineItem.Event(
            uniqueId = UniqueId("foreign"),
            event = anEventTimelineItem(
                isOwn = false,
                isRemote = true,
                content = aMessageContent(body = "foreign"),
            ),
        )
        val ownNonMessage = MatrixTimelineItem.Event(
            uniqueId = UniqueId("own-state"),
            event = anEventTimelineItem(isOwn = true, isRemote = true),
        )
        val prefetched = mutableListOf<UniqueId>()

        prefetchConfirmedOwnMessageEvents(
            items = ownMessages + foreignMessage + ownNonMessage + MatrixTimelineItem.Other,
        ) { event, _ -> prefetched += event.uniqueId }

        assertThat(prefetched).containsExactlyElementsIn(ownMessages.map(MatrixTimelineItem.Event::uniqueId)).inOrder()
    }

    @Test
    fun `new protected content uses the signed policy retention when the room lasts longer`() {
        val now = 1_000_000L

        val expiresAt = resolveProtectedContentExpiry(
            nowEpochMillis = now,
            roomExpiresAtEpochMillis = now + 30 * 24 * 60 * 60_000L,
            policyExpiresAtEpochMillis = null,
            policyContentRetentionMillis = 24 * 60 * 60_000L,
            groupBurnAfterRead = false,
        )

        assertThat(expiresAt).isEqualTo(now + 24 * 60 * 60_000L)
    }

    @Test
    fun `protocol three room contract is the exact ordinary message expiry`() {
        val now = 1_000_000L
        val roomDeadline = now + 30 * 24 * 60 * 60_000L

        val expiresAt = resolveProtectedContentExpiry(
            nowEpochMillis = now,
            roomExpiresAtEpochMillis = roomDeadline,
            policyExpiresAtEpochMillis = now + 2 * 24 * 60 * 60_000L,
            policyContentRetentionMillis = 24 * 60 * 60_000L,
            groupBurnAfterRead = false,
            roomLifecycleProtocolVersion = 3,
        )

        assertThat(expiresAt).isEqualTo(roomDeadline)
    }

    @Test
    fun `protocol three never broadens group burn deadline`() {
        val now = 1_000_000L

        val expiresAt = resolveProtectedContentExpiry(
            nowEpochMillis = now,
            roomExpiresAtEpochMillis = now + 30 * 24 * 60 * 60_000L,
            policyExpiresAtEpochMillis = null,
            policyContentRetentionMillis = 30 * 24 * 60 * 60_000L,
            groupBurnAfterRead = true,
            roomLifecycleProtocolVersion = 3,
        )

        assertThat(expiresAt).isEqualTo(now + GROUP_BURN_AFTER_READ_MAX_MILLIS)
    }

    @Test
    fun `new protected content keeps the earliest room policy and group burn deadline`() {
        val now = 1_000_000L
        val roomDeadline = now + 30 * 24 * 60 * 60_000L
        val policyDeadline = now + 20 * 24 * 60 * 60_000L

        assertThat(
            resolveProtectedContentExpiry(
                nowEpochMillis = now,
                roomExpiresAtEpochMillis = roomDeadline,
                policyExpiresAtEpochMillis = policyDeadline,
                policyContentRetentionMillis = 25 * 24 * 60 * 60_000L,
                groupBurnAfterRead = false,
            )
        ).isEqualTo(policyDeadline)
        assertThat(
            resolveProtectedContentExpiry(
                nowEpochMillis = now,
                roomExpiresAtEpochMillis = roomDeadline,
                policyExpiresAtEpochMillis = null,
                policyContentRetentionMillis = 30 * 24 * 60 * 60_000L,
                groupBurnAfterRead = true,
            )
        ).isEqualTo(now + GROUP_BURN_AFTER_READ_MAX_MILLIS)
    }

    @Test
    fun `cached protected content keeps current sdk event identity and metadata`() {
        val transportContent = aMessageContent(body = "protected-transport")
        val authenticatedContent = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)
        val currentItem = MatrixTimelineItem.Event(
            uniqueId = A_UNIQUE_ID_2,
            event = anEventTimelineItem(
                eventId = AN_EVENT_ID_2,
                transactionId = A_TRANSACTION_ID,
                isOwn = true,
                isRemote = true,
                localSendState = LocalEventSendState.Sent(AN_EVENT_ID_2),
                timestamp = 1234L,
                content = transportContent,
            ),
        )

        val projected = currentItem.withCachedProtectedContent(authenticatedContent)

        assertThat(projected).isEqualTo(
            currentItem.copy(event = currentItem.event.copy(content = authenticatedContent))
        )
        assertThat(projected.uniqueId).isEqualTo(A_UNIQUE_ID_2)
        assertThat(projected.eventId).isEqualTo(AN_EVENT_ID_2)
        assertThat(projected.transactionId).isEqualTo(A_TRANSACTION_ID)
        assertThat(projected.event.localSendState).isEqualTo(LocalEventSendState.Sent(AN_EVENT_ID_2))
    }

    @Test
    fun `authorized projection binds clipboard capability to confirmed event and complete content set`() {
        val key = ProtectedTimelineDecryptionCache.Key(
            eventId = AN_EVENT_ID_2.value,
            contentId = ContentId("caption-content"),
            ciphertextHash = "ciphertext-hash",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
            relatedContentIds = setOf(ContentId("media-content")),
        )
        val content = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)

        val projected = content.withProtectedClipboardSource(
            key = key,
            authorizationRevision = 11L,
            confirmedEventId = AN_EVENT_ID_2.value,
        ) as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
        val source = projected.clipboardSource as MessageClipboardSource.Protected

        assertThat(source.eventId).isEqualTo(AN_EVENT_ID_2.value)
        assertThat(source.contentIds).containsExactly("caption-content", "media-content")
        assertThat(source.authorizationRevision).isEqualTo(11L)
        assertThat(source.expiresAtEpochMillis).isEqualTo(2_000L)
    }

    @Test
    fun `local echo or mismatched event cannot receive protected clipboard capability`() {
        val key = ProtectedTimelineDecryptionCache.Key(
            eventId = AN_EVENT_ID_2.value,
            contentId = ContentId("content"),
            ciphertextHash = "ciphertext-hash",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )
        val content = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)

        val localEcho = content.withProtectedClipboardSource(key, 11L, confirmedEventId = null)
            as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
        val mismatched = content.withProtectedClipboardSource(key, 11L, confirmedEventId = "\$other:example.org")
            as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent

        assertThat(localEcho.clipboardSource).isNull()
        assertThat(mismatched.clipboardSource).isNull()
    }

    @Test
    fun `ordinary plaintext receives explicit unprotected clipboard source`() {
        val projected = aMessageContent(body = "ordinary")
            .withExplicitUnprotectedClipboardSource()

        assertThat(projected.clipboardSource).isEqualTo(MessageClipboardSource.Unprotected)
    }

    @Test
    fun `authorized plaintext cannot receive unprotected clipboard source`() {
        val projected = aMessageContent(body = "authorized")
            .copy(isContentProtectionAuthorized = true)
            .withExplicitUnprotectedClipboardSource()

        assertThat(projected.clipboardSource).isNull()
    }

    @Test
    fun `burn plaintext cannot receive unprotected clipboard source`() {
        val projected = aMessageContent(body = "burn")
            .copy(isBurnAfterRead = true)
            .withExplicitUnprotectedClipboardSource()

        assertThat(projected.clipboardSource).isNull()
    }

    @Test
    fun `protected status plaintext cannot receive unprotected clipboard source`() {
        ProtectedMessageStatus.entries.forEach { status ->
            val projected = aMessageContent(body = "status")
                .copy(protectedMessageStatus = status)
                .withExplicitUnprotectedClipboardSource()

            assertThat(projected.clipboardSource).isNull()
        }
    }

    @Test
    fun `only immutable envelope failures are stable rejection cache candidates`() {
        assertThat(ContentProtectionException.InvalidEnvelope().isStableProtectedRejection()).isTrue()
        assertThat(ContentProtectionException.Expired().isStableProtectedRejection()).isTrue()
        assertThat(ContentProtectionException.Revoked().isStableProtectedRejection()).isTrue()
        assertThat(ContentProtectionException.ReplayRejected().isStableProtectedRejection()).isTrue()
        assertThat(ContentProtectionException.AuthenticationFailed().isStableProtectedRejection()).isTrue()

        assertThat(ContentProtectionException.KmsUnavailable().isStableProtectedRejection()).isFalse()
        assertThat(ContentProtectionException.AccessDenied().isStableProtectedRejection()).isFalse()
        assertThat(ContentProtectionException.RecoveryApprovalRequired().isStableProtectedRejection()).isFalse()
        assertThat(ContentProtectionException.ClockRollbackDetected().isStableProtectedRejection()).isFalse()
    }

    @Test
    fun `only policy mismatch is an authorization scoped rejection cache candidate`() {
        assertThat(
            StageThreeGateDenied(StageThreeDenialReason.POLICY_MISMATCH).isAuthorizationScopedProtectedRejection()
        ).isTrue()

        assertThat(
            StageThreeGateDenied(StageThreeDenialReason.POLICY_NOT_ACTIVE).isAuthorizationScopedProtectedRejection()
        ).isFalse()
        assertThat(
            StageThreeGateDenied(StageThreeDenialReason.KMS_AUTHORIZATION_UNAVAILABLE).isAuthorizationScopedProtectedRejection()
        ).isFalse()
        assertThat(ContentProtectionException.KmsUnavailable().isAuthorizationScopedProtectedRejection()).isFalse()
    }

    @Test
    fun `stable rejection cache never replaces success and clears with sensitive runtime caches`() = runTest {
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { 1_000L })
        val key = ProtectedTimelineDecryptionCache.Key(
            eventId = "event",
            contentId = ContentId("content"),
            ciphertextHash = "ciphertext",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )
        val authenticatedContent = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)

        cache.putStableRejection(key, ProtectedMessageDisplayState.INTEGRITY_REJECTED)
        assertThat(cache.get(key)).isNull()
        assertThat(cache.getStableRejection(key)).isEqualTo(ProtectedMessageDisplayState.INTEGRITY_REJECTED)

        cache.put(key, authenticatedContent)
        assertThat(cache.get(key)).isEqualTo(authenticatedContent)
        assertThat(cache.getStableRejection(key)).isNull()

        cache.putStableRejection(key, ProtectedMessageDisplayState.REVOKED)
        assertThat(cache.get(key)).isNull()
        runtime.clearSensitiveCaches()
        assertThat(cache.getStableRejection(key)).isNull()
    }

    @Test
    fun `authorization scoped rejection is reused only within its authorization revision`() = runTest {
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { 1_000L })
        val key = ProtectedTimelineDecryptionCache.Key(
            eventId = "event",
            contentId = ContentId("content"),
            ciphertextHash = "ciphertext",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )

        cache.putAuthorizationRejection(
            key,
            authorizationRevision = 4L,
            state = ProtectedMessageDisplayState.ACCESS_DENIED,
        )

        assertThat(cache.getAuthorizationRejection(key, authorizationRevision = 4L))
            .isEqualTo(ProtectedMessageDisplayState.ACCESS_DENIED)
        assertThat(cache.getAuthorizationRejection(key, authorizationRevision = 5L)).isNull()
        assertThat(cache.getStableRejection(key)).isNull()

        runtime.clearSensitiveCaches()
        assertThat(cache.getAuthorizationRejection(key, authorizationRevision = 4L)).isNull()
    }

    @Test
    fun `targeted eviction removes one event without invalidating unrelated history`() = runTest {
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { 1_000L })
        val target = ProtectedTimelineDecryptionCache.Key(
            eventId = "target",
            contentId = ContentId("target-content"),
            ciphertextHash = "target-success",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )
        val targetStableRejection = target.copy(ciphertextHash = "target-stable")
        val targetAuthorizationRejection = target.copy(ciphertextHash = "target-authorization")
        val unrelated = target.copy(eventId = "history", ciphertextHash = "history")
        val sameEventOtherRoom = target.copy(roomId = "other-room", ciphertextHash = "other-room")
        val authenticated = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)

        cache.put(target, authenticated)
        cache.putStableRejection(targetStableRejection, ProtectedMessageDisplayState.REVOKED)
        cache.putAuthorizationRejection(
            targetAuthorizationRejection,
            authorizationRevision = 4L,
            state = ProtectedMessageDisplayState.ACCESS_DENIED,
        )
        cache.put(unrelated, authenticated)
        cache.put(sameEventOtherRoom, authenticated)

        cache.remove(roomId = "room", eventIdentity = "target")

        assertThat(cache.get(target)).isNull()
        assertThat(cache.getStableRejection(targetStableRejection)).isNull()
        assertThat(cache.getAuthorizationRejection(targetAuthorizationRejection, authorizationRevision = 4L)).isNull()
        assertThat(cache.get(unrelated)).isEqualTo(authenticated)
        assertThat(cache.get(sameEventOtherRoom)).isEqualTo(authenticated)
        assertThat(runtime.authorizationRefreshCount).isEqualTo(0)
    }

    @Test
    fun `content invalidation evicts every target share and preserves unrelated cache entries`() = runTest {
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { 1_000L })
        val target = ProtectedTimelineDecryptionCache.Key(
            eventId = "target",
            contentId = ContentId("target-content"),
            ciphertextHash = "target-success",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )
        val secondTargetShare = target.copy(eventId = "target-operation", ciphertextHash = "target-operation")
        val targetStableRejection = target.copy(eventId = "target-stable", ciphertextHash = "target-stable")
        val targetAuthorizationRejection = target.copy(eventId = "target-auth", ciphertextHash = "target-auth")
        val unrelated = target.copy(
            eventId = "unrelated",
            contentId = ContentId("unrelated-content"),
            ciphertextHash = "unrelated",
        )
        val authenticated = aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true)

        cache.put(target, authenticated)
        cache.put(secondTargetShare, authenticated)
        cache.putStableRejection(targetStableRejection, ProtectedMessageDisplayState.REVOKED)
        cache.putAuthorizationRejection(
            targetAuthorizationRejection,
            authorizationRevision = 4L,
            state = ProtectedMessageDisplayState.ACCESS_DENIED,
        )
        cache.put(unrelated, authenticated)

        runtime.requestContentInvalidation(target.contentId)

        assertThat(cache.get(target)).isNull()
        assertThat(cache.get(secondTargetShare)).isNull()
        assertThat(cache.getStableRejection(targetStableRejection)).isNull()
        assertThat(cache.getAuthorizationRejection(targetAuthorizationRejection, authorizationRevision = 4L)).isNull()
        assertThat(cache.get(unrelated)).isEqualTo(authenticated)
        assertThat(runtime.authorizationRefreshCount).isEqualTo(0)
    }

    @Test
    fun `attachment media revocation evicts projection cached by its caption envelope`() = runTest {
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { 1_000L })
        val mediaContentId = ContentId("media-content")
        val attachmentProjection = ProtectedTimelineDecryptionCache.Key(
            eventId = "attachment-event",
            contentId = ContentId("caption-content"),
            ciphertextHash = "caption-ciphertext",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
            relatedContentIds = setOf(mediaContentId),
        )
        val authenticated = aMessageContent(body = "attachment").copy(isContentProtectionAuthorized = true)
        cache.put(attachmentProjection, authenticated)

        runtime.requestContentInvalidation(mediaContentId)

        assertThat(cache.get(attachmentProjection)).isNull()
    }

    @Test
    fun `authenticated plaintext is evicted and timelines refresh exactly at content expiry`() = runTest {
        var now = 1_000L
        val runtime = RecordingMatrixContentProtectionRuntime()
        val cache = ProtectedTimelineDecryptionCache(runtime, backgroundScope, SystemClock { now })
        val key = ProtectedTimelineDecryptionCache.Key(
            eventId = "event",
            contentId = ContentId("content"),
            ciphertextHash = "ciphertext",
            policyVersion = 7L,
            envelopeVersion = 3,
            sessionId = "session",
            roomId = "room",
            expiresAtEpochMillis = 2_000L,
        )
        cache.put(key, aMessageContent(body = "authenticated").copy(isContentProtectionAuthorized = true))

        now = 2_000L
        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(cache.get(key)).isNull()
        assertThat(runtime.authorizationRefreshCount).isEqualTo(1)
        assertThat(runtime.lastAuthorizationRefreshMode)
            .isEqualTo(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
    }
}

private class RecordingMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
    private var clearer: (() -> Unit)? = null
    private var contentClearer: ((ContentId) -> Unit)? = null
    var authorizationRefreshCount = 0
        private set
    var lastAuthorizationRefreshMode: MatrixAuthorizationRefreshMode? = null
        private set

    override fun attach() = Unit

    override fun detach() = Unit

    override fun isConnected(): Boolean = true

    override fun registerSensitiveCacheClearer(clearer: () -> Unit) {
        this.clearer = clearer
    }

    override fun registerSensitiveContentClearer(clearer: (ContentId) -> Unit) {
        contentClearer = clearer
    }

    override fun clearSensitiveCaches() {
        clearer?.invoke()
    }

    override fun requestContentInvalidation(contentId: ContentId) {
        contentClearer?.invoke(contentId)
    }

    override fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode) {
        authorizationRefreshCount++
        lastAuthorizationRefreshMode = mode
    }
}
