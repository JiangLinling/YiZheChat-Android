/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.timeline.item.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import org.junit.Test

class EventContentVisibilityTest {
    @Test
    fun `only actionable content-free security shells are visible`() {
        val visibleStatuses = setOf(
            ProtectedMessageStatus.ATTACHMENT_DECRYPTING,
            ProtectedMessageStatus.LEGACY_BLOCKED,
            ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE,
            ProtectedMessageStatus.SEND_BINDING_PENDING,
            ProtectedMessageStatus.LICENSE_AUTHORIZATION_FAILED,
            ProtectedMessageStatus.DEVICE_SECURITY_UNAVAILABLE,
            ProtectedMessageStatus.ACCESS_DENIED,
            ProtectedMessageStatus.INTEGRITY_REJECTED,
        )
        visibleStatuses.forEach { status ->
            assertThat(message(body = "", status = status).isAuthorizedForUserDisplay()).isTrue()
            assertThat(message(body = "leaked", status = status).isAuthorizedForUserDisplay()).isFalse()
        }
        ProtectedMessageStatus.entries
            .filterNot { it in visibleStatuses || it == ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED }
            .forEach { status ->
                assertThat(message(status = status).isAuthorizedForUserDisplay()).isFalse()
            }
    }

    @Test
    fun `only an empty authenticated burn after read action shell is visible`() {
        assertThat(message(
            body = "",
            status = ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
            burnAfterRead = true,
        ).isAuthorizedForUserDisplay(requireContentProtection = true)).isTrue()
        assertThat(message(
            body = "leaked",
            status = ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
            burnAfterRead = true,
        ).isAuthorizedForUserDisplay(requireContentProtection = true)).isFalse()
        assertThat(message(
            body = "",
            status = ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
        ).isAuthorizedForUserDisplay(requireContentProtection = true)).isFalse()
    }

    @Test
    fun `consumed or expired burn after read messages disappear from the timeline`() {
        assertThat(message(
            body = "",
            status = ProtectedMessageStatus.REVOKED,
            burnAfterRead = true,
        ).isAuthorizedForUserDisplay(requireContentProtection = true)).isFalse()
        assertThat(message(
            body = "",
            status = ProtectedMessageStatus.EXPIRED,
            burnAfterRead = true,
        ).isAuthorizedForUserDisplay(requireContentProtection = true)).isFalse()
    }

    @Test
    fun `matrix unable to decrypt events remain invisible`() {
        val content = UnableToDecryptContent(UnableToDecryptContent.Data.Unknown, threadInfo = null)

        assertThat(content.isAuthorizedForUserDisplay()).isFalse()
    }

    @Test
    fun `raw protected transport carrier remains invisible`() {
        assertThat(message(body = "yizhe-secure-v1:ciphertext").isAuthorizedForUserDisplay()).isFalse()
    }

    @Test
    fun `raw protected media carrier remains invisible`() {
        val content = MessageContent(
            body = ".yizhe-1234567890.yzm",
            inReplyTo = null,
            isEdited = false,
            threadInfo = null,
            type = FileMessageType(
                filename = ".yizhe-1234567890.yzm",
                caption = null,
                formattedCaption = null,
                source = MediaSource("mxc://server/id"),
                info = null,
            ),
        )

        assertThat(content.isAuthorizedForUserDisplay()).isFalse()
    }

    @Test
    fun `authenticated decrypted message is visible`() {
        assertThat(message(body = "decrypted body").isAuthorizedForUserDisplay()).isTrue()
    }

    @Test
    fun `strict content policy only exposes a message with an authenticated authorization marker`() {
        assertThat(message(body = "legacy plaintext").isAuthorizedForUserDisplay(requireContentProtection = true)).isFalse()
        assertThat(
            message(body = "authenticated plaintext", authorized = true)
                .isAuthorizedForUserDisplay(requireContentProtection = true)
        ).isTrue()
    }

    private fun message(
        body: String = "body",
        status: ProtectedMessageStatus? = null,
        authorized: Boolean = false,
        burnAfterRead: Boolean = false,
    ) = MessageContent(
        body = body,
        inReplyTo = null,
        isEdited = false,
        threadInfo = null,
        type = TextMessageType(body, formatted = null),
        protectedMessageStatus = status,
        isContentProtectionAuthorized = authorized,
        isBurnAfterRead = burnAfterRead,
    )
}
