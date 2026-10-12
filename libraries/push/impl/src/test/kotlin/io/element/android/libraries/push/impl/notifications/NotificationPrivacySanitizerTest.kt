/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import org.junit.Test

class NotificationPrivacySanitizerTest {
    @Test
    fun `hidden exposure removes notification presentation data`() {
        val sanitizer = aSanitizer(NotificationExposure.HIDDEN)
        val input = aNotifiableMessageEvent(
            body = "commercial secret",
            senderDisambiguatedDisplayName = "Alice",
            roomName = "Secret room",
        )

        val output = sanitizer.sanitize(ResolvedPushEvent.Event(input)).notifiableMessage()

        assertThat(output.body).isEqualTo(GENERIC_BODY)
        assertThat(output.senderDisambiguatedDisplayName).isNull()
        assertThat(output.roomName).isNull()
        assertThat(output.imageUri).isNull()
        assertThat(output.roomAvatarPath).isNull()
        assertThat(output.senderAvatarPath).isNull()
    }

    @Test
    fun `sender only exposure retains sender but removes content`() {
        val sanitizer = aSanitizer(NotificationExposure.SENDER_ONLY)
        val input = aNotifiableMessageEvent(
            body = "commercial secret",
            senderDisambiguatedDisplayName = "Alice",
        )

        val output = sanitizer.sanitize(ResolvedPushEvent.Event(input)).notifiableMessage()

        assertThat(output.body).isEqualTo(GENERIC_BODY)
        assertThat(output.senderDisambiguatedDisplayName).isEqualTo("Alice")
    }

    @Test
    fun `content exposure leaves event unchanged`() {
        val sanitizer = aSanitizer(NotificationExposure.CONTENT_ALLOWED)
        val input = aNotifiableMessageEvent(body = "allowed")

        val output = sanitizer.sanitize(ResolvedPushEvent.Event(input)).notifiableMessage()

        assertThat(output).isEqualTo(input)
        assertThat(sanitizer.allowsContent()).isTrue()
    }

    @Test
    fun `forced hidden presentation overrides content exposure`() {
        val sanitizer = aSanitizer(NotificationExposure.CONTENT_ALLOWED)
        val input = aNotifiableMessageEvent(
            body = "protected transport carrier",
            senderDisambiguatedDisplayName = "Alice",
            roomName = "Secret room",
        )

        val output = sanitizer.sanitize(
            event = ResolvedPushEvent.Event(input),
            forceHidden = true,
        ).notifiableMessage()

        assertThat(output.body).isEqualTo(GENERIC_BODY)
        assertThat(output.senderDisambiguatedDisplayName).isNull()
        assertThat(output.roomName).isNull()
        assertThat(output.imageUri).isNull()
    }

    private fun aSanitizer(exposure: NotificationExposure) = NotificationPrivacySanitizer(
        policyProvider = FakeDataProtectionPolicyProvider(exposure),
        stringProvider = FakeStringProvider(defaultResult = GENERIC_BODY),
    )

    private fun ResolvedPushEvent.notifiableMessage(): NotifiableMessageEvent =
        (this as ResolvedPushEvent.Event).notifiableEvent as NotifiableMessageEvent

    private companion object {
        const val GENERIC_BODY = "You have new messages."
    }
}
