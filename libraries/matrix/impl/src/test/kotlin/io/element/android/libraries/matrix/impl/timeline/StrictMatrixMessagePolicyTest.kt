/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LiveLocationContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.OtherMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.impl.util.MessageEventContent
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import org.junit.Test

class StrictMatrixMessagePolicyTest {
    @org.junit.Test fun `preview denial distinguishes app lock from device trust failure`() {
        val allowed = io.element.android.security.dataprotection.api.ProtectedPreviewAccessState(true, true, true)
        assertThat(allowed.copy(applicationUnlocked = false).deniedDisplayState()).isEqualTo(ProtectedMessageDisplayState.VAULT_LOCKED)
        assertThat(allowed.copy(platformUnlocked = false).deniedDisplayState()).isEqualTo(ProtectedMessageDisplayState.VAULT_LOCKED)
        assertThat(allowed.copy(deviceTrusted = false).deniedDisplayState()).isEqualTo(ProtectedMessageDisplayState.DEVICE_SECURITY_UNAVAILABLE)
    }

    @Test
    fun `ordinary Matrix text is blocked in strict mode`() {
        val message = message(TextMessageType(body = "secret", formatted = null))

        assertThat(classifyStrictMatrixMessage(message)).isEqualTo(StrictMatrixMessageDecision.Block)
    }

    @Test
    fun `standard Matrix text carrier is routed to authenticated decryption`() {
        val message = message(
            TextMessageType(
                body = MessageEventContent.PROTECTED_BODY_PREFIX + "opaque-envelope",
                formatted = null,
            )
        )

        assertThat(classifyStrictMatrixMessage(message)).isEqualTo(StrictMatrixMessageDecision.DecryptEnvelope)
    }

    @Test
    fun `protected envelope uses a decrypting shell instead of a blocked downgrade shell`() {
        assertThat(StrictMatrixMessageDecision.DecryptEnvelope.shellDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.DECRYPTING)
    }

    @Test
    fun `ordinary Matrix text remains visibly blocked in strict mode`() {
        assertThat(StrictMatrixMessageDecision.Block.shellDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.LEGACY_BLOCKED)
    }

    @Test
    fun `feature-only rollout decrypts standard Matrix text carrier`() {
        val message = message(
            TextMessageType(
                body = MessageEventContent.PROTECTED_BODY_PREFIX + "opaque-envelope",
                formatted = null,
            )
        )

        assertThat(
            classifyMatrixMessage(
                message = message,
                policyRequiresProtection = false,
                contentProtectionFeatureEnabled = true,
            )
        ).isEqualTo(StrictMatrixMessageDecision.DecryptEnvelope)
    }

    @Test
    fun `legacy custom YiZhe envelope remains recognized when supplied by the SDK`() {
        val message = message(
            OtherMessageType(
                msgType = MessageEventContent.PROTECTED_MESSAGE_TYPE,
                body = "opaque-envelope",
            )
        )

        assertThat(classifyStrictMatrixMessage(message)).isEqualTo(StrictMatrixMessageDecision.DecryptEnvelope)
    }

    @Test
    fun `feature-only rollout passes ordinary Matrix text through`() {
        val message = message(TextMessageType(body = "ordinary", formatted = null))

        assertThat(
            classifyMatrixMessage(
                message = message,
                policyRequiresProtection = false,
                contentProtectionFeatureEnabled = true,
            )
        ).isNull()
    }

    @Test
    fun `disabled protection does not intercept standard Matrix text carrier`() {
        val message = message(
            TextMessageType(
                body = MessageEventContent.PROTECTED_BODY_PREFIX + "opaque-envelope",
                formatted = null,
            )
        )

        assertThat(
            classifyMatrixMessage(
                message = message,
                policyRequiresProtection = false,
                contentProtectionFeatureEnabled = false,
            )
        ).isNull()
    }

    @Test
    fun `protected media transport hides its original name`() {
        val message = message(
            FileMessageType(
                filename = ".yizhe-1234567890.yzm",
                caption = null,
                formattedCaption = null,
                source = MediaSource("mxc://example.invalid/opaque"),
                info = FileInfo(
                    mimetype = MimeTypes.Pdf,
                    size = 42L,
                    thumbnailInfo = null,
                    thumbnailSource = null,
                ),
            )
        )

        val decision = classifyStrictMatrixMessage(message)

        assertThat(decision).isInstanceOf(StrictMatrixMessageDecision.AllowProtectedAttachment::class.java)
        val content = (decision as StrictMatrixMessageDecision.AllowProtectedAttachment).content
        assertThat(content.body).isEqualTo("受保护附件")
        val file = content.type as FileMessageType
        assertThat(file.filename).isEqualTo("受保护附件")
        assertThat(file.info?.mimetype).isEqualTo(MimeTypes.Pdf)
    }

    @Test
    fun `protected media projection is authorized only after its transport metadata is sanitized`() {
        val message = message(
            FileMessageType(
                filename = ".yizhe-1234567890.yzm",
                caption = null,
                formattedCaption = null,
                source = MediaSource("mxc://example.invalid/opaque"),
                info = null,
            )
        )

        val decision = classifyStrictMatrixMessage(message) as StrictMatrixMessageDecision.AllowProtectedAttachment
        val projected = decision.content.authorizeProtectedAttachmentProjection()

        assertThat(projected.isContentProtectionAuthorized).isTrue()
        assertThat(projected.protectedMessageStatus).isNull()
        assertThat(projected.body).isEqualTo("受保护附件")
        assertThat((projected.type as FileMessageType).filename).isEqualTo("受保护附件")
    }

    @Test
    fun `protected attachment shell is transient so an existing projection survives sync reset`() {
        val decision = StrictMatrixMessageDecision.AllowProtectedAttachment(
            message(
                FileMessageType(
                    filename = ".yizhe-1234567890.yzm",
                    caption = null,
                    formattedCaption = null,
                    source = MediaSource("mxc://example.invalid/opaque"),
                    info = null,
                )
            )
        )

        assertThat(
            decision.usesTransientDecryptingShell(
                isBurnAfterRead = false,
                isOperationCarrier = false,
            )
        ).isTrue()
    }

    @Test
    fun `burn after read attachment shell is never replaced by an old projection`() {
        val decision = StrictMatrixMessageDecision.AllowProtectedAttachment(
            message(
                FileMessageType(
                    filename = ".yizhe-burn-1234567890.yzm",
                    caption = null,
                    formattedCaption = null,
                    source = MediaSource("mxc://example.invalid/opaque"),
                    info = null,
                )
            )
        )

        assertThat(
            decision.usesTransientDecryptingShell(
                isBurnAfterRead = true,
                isOperationCarrier = false,
            )
        ).isFalse()
    }

    @Test
    fun `authorized encrypted attachment metadata restores the file card without exposing the transport`() {
        val transport = message(
            FileMessageType(
                filename = "受保护附件",
                caption = MessageEventContent.PROTECTED_BODY_PREFIX + "opaque-caption-envelope",
                formattedCaption = null,
                source = MediaSource("mxc://example.invalid/opaque"),
                info = null,
            )
        )

        val restored = transport.withProtectedCaption(
            ProtectedCaption(
                caption = null,
                formattedCaption = null,
                attachmentMetadata = ProtectedAttachmentMetadata(
                    displayName = "费用报销明细.xlsx",
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    size = 13_824L,
                ),
            )
        )

        assertThat(restored.body).isEqualTo("body")
        val file = restored.type as FileMessageType
        assertThat(file.filename).isEqualTo("费用报销明细.xlsx")
        assertThat(file.caption).isNull()
        assertThat(file.info).isEqualTo(
            FileInfo(
                mimetype = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                size = 13_824L,
                thumbnailInfo = null,
                thumbnailSource = null,
            )
        )
    }

    @Test
    fun `authorized video file metadata preserves transport length across repeated projection`() {
        val transport = message(FileMessageType(
            filename = "受保护附件", caption = null, formattedCaption = null,
            source = MediaSource("mxc://example.invalid/opaque", json = "{}"),
            info = FileInfo("application/octet-stream", 456L, null, null),
        ))
        val caption = ProtectedCaption(null, null, attachmentMetadata = ProtectedAttachmentMetadata(
            displayName = "video.mp4", mimeType = "video/mp4", size = 123L,
        ))
        val restored = transport.withProtectedCaption(caption).withProtectedCaption(caption)
        val info = (restored.type as FileMessageType).info!!
        assertThat(info.size).isEqualTo(123L)
        assertThat(info.protectedWireSize).isEqualTo(456L)
    }

    @Test
    fun `renamed media carrying a caption is blocked`() {
        val message = message(
            FileMessageType(
                filename = ".yizhe-1234567890.yzm",
                caption = "plaintext caption",
                formattedCaption = null,
                source = MediaSource("mxc://example.invalid/opaque"),
                info = null,
            )
        )

        assertThat(classifyStrictMatrixMessage(message)).isEqualTo(StrictMatrixMessageDecision.Block)
    }

    @Test
    fun `native live location projection requires a confirmed encrypted room`() {
        val content = LiveLocationContent(
            isLive = true,
            description = null,
            startTimestamp = 1_000L,
            timeout = 60_000L,
            assetType = null,
            locations = emptyList(),
        )

        assertThat(allowEncryptedLiveLocationProjection(content, isRoomEncrypted = true)).isTrue()
        assertThat(allowEncryptedLiveLocationProjection(content, isRoomEncrypted = false)).isFalse()
        assertThat(allowEncryptedLiveLocationProjection(content.copy(timeout = 0L), isRoomEncrypted = true)).isFalse()
    }

    @Test
    fun `temporary KMS failures do not masquerade as policy downgrade blocks`() {
        assertThat(ContentProtectionException.SendBindingPending().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.SEND_BINDING_PENDING)
        assertThat(StageThreeGateDenied(StageThreeDenialReason.VAULT_LOCKED).protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.VAULT_LOCKED)
        assertThat(StageThreeGateDenied(StageThreeDenialReason.DEVICE_NOT_TRUSTED).protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.DEVICE_SECURITY_UNAVAILABLE)
        assertThat(ContentProtectionException.KmsUnavailable().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE)
        assertThat(ContentProtectionException.OfflineLicenseUnavailable().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.LICENSE_AUTHORIZATION_FAILED)
        assertThat(ContentProtectionException.ReplayRejected().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.LICENSE_AUTHORIZATION_FAILED)
        assertThat(StageThreeGateDenied(StageThreeDenialReason.KMS_AUTHORIZATION_UNAVAILABLE).protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.TEMPORARILY_UNAVAILABLE)
    }

    @Test
    fun `terminal content protection decisions retain fail closed status`() {
        assertThat(ContentProtectionException.Revoked().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.REVOKED)
        assertThat(ContentProtectionException.Expired().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.EXPIRED)
        assertThat(ContentProtectionException.AccessDenied().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.ACCESS_DENIED)
        assertThat(ContentProtectionException.RecoveryApprovalRequired().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.ACCESS_DENIED)
        assertThat(ContentProtectionException.AuthenticationFailed().protectedMessageDisplayState())
            .isEqualTo(ProtectedMessageDisplayState.INTEGRITY_REJECTED)
    }

    @Test
    fun `protected message failure shells carry status only`() {
        ProtectedMessageDisplayState.entries.forEach { state ->
            assertThat(state.status).isNotNull()
        }
    }

    @Test
    fun `pending local echo payload supports concurrent projections and expires`() {
        var now = 1_000L
        val cache = PendingProtectedPayloadCache(
            nowEpochMillis = { now },
            maximumEntries = 2,
            ttlMillis = 100,
        )
        val plaintext = "local echo".encodeToByteArray()
        val first = pendingPayloadKey("first")
        cache.put(first, plaintext)
        plaintext.fill(0)

        val firstProjection = cache.get(first)
        val secondProjection = cache.get(first)
        assertThat(firstProjection?.decodeToString()).isEqualTo("local echo")
        assertThat(secondProjection?.decodeToString()).isEqualTo("local echo")
        assertThat(firstProjection).isNotSameInstanceAs(secondProjection)
        firstProjection?.fill(0)
        secondProjection?.fill(0)

        val expired = pendingPayloadKey("expired")
        cache.put(expired, "secret".encodeToByteArray())
        now += 101
        assertThat(cache.get(expired)).isNull()
    }

    @Test
    fun `pending local echo payload is bound to account room and ciphertext`() {
        val cache = PendingProtectedPayloadCache(nowEpochMillis = { 1_000L })
        val expected = pendingPayloadKey("content")
        cache.put(expected, "secret".encodeToByteArray())

        assertThat(cache.get(expected.copy(roomId = "!other:example.org"))).isNull()
        assertThat(cache.get(expected.copy(accountIdHash = "other-account"))).isNull()
        assertThat(cache.get(expected.copy(ciphertextHash = "other-ciphertext"))).isNull()
        assertThat(cache.get(expected)?.decodeToString()).isEqualTo("secret")
    }

    @Test
    fun `destroy removes every pending payload for the content`() {
        val cache = PendingProtectedPayloadCache(nowEpochMillis = { 1_000L })
        val first = pendingPayloadKey("content")
        val second = first.copy(ciphertextHash = "second-ciphertext")
        cache.put(first, "first".encodeToByteArray())
        cache.put(second, "second".encodeToByteArray())

        cache.remove(ContentId("content"))

        assertThat(cache.get(first)).isNull()
        assertThat(cache.get(second)).isNull()
    }

    private fun pendingPayloadKey(contentId: String) = PendingProtectedPayloadCache.Key(
        contentId = ContentId(contentId),
        accountIdHash = "account",
        roomId = "!room:example.org",
        ciphertextHash = "ciphertext",
    )

    private fun message(type: io.element.android.libraries.matrix.api.timeline.item.event.MessageType): MessageContent = MessageContent(
        body = "body",
        inReplyTo = null,
        isEdited = false,
        threadInfo = null,
        type = type,
    )
}
