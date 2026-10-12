/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.Inject
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.model.FallbackNotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.InviteNotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent
import io.element.android.libraries.push.impl.notifications.model.SimpleNotifiableEvent
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.services.toolbox.api.strings.StringProvider

@Inject
class NotificationPrivacySanitizer(
    private val policyProvider: DataProtectionPolicyProvider,
    private val stringProvider: StringProvider,
) {
    fun allowsContent(): Boolean = currentExposure() == NotificationExposure.CONTENT_ALLOWED

    /** Raw push events have not passed the YiZhe KMS/content gate and cannot expose message metadata. */
    fun requiresContentAuthorization(): Boolean = policyProvider.current().policy.requireContentEnvelope

    fun sanitize(
        event: ResolvedPushEvent,
        forceHidden: Boolean = false,
        genericBodyOverride: String? = null,
    ): ResolvedPushEvent {
        val exposure = if (forceHidden) NotificationExposure.HIDDEN else currentExposure()
        if (exposure == NotificationExposure.CONTENT_ALLOWED) return event
        if (event is ResolvedPushEvent.Redaction) return event.copy(reason = null)

        event as ResolvedPushEvent.Event
        val genericBody = genericBodyOverride ?: stringProvider.getString(R.string.notification_fallback_content)
        val keepSender = exposure == NotificationExposure.SENDER_ONLY
        val sanitized = when (val notifiableEvent = event.notifiableEvent) {
            is NotifiableMessageEvent -> notifiableEvent.redactPresentation(genericBody, keepSender)
            is InviteNotifiableEvent -> notifiableEvent.copy(
                roomName = null,
                title = null,
                description = genericBody,
            )
            is SimpleNotifiableEvent -> notifiableEvent.copy(
                title = genericBody,
                description = genericBody,
            )
            is FallbackNotifiableEvent -> notifiableEvent.copy(
                description = genericBody,
                cause = null,
            )
            is NotifiableRingingCallEvent -> notifiableEvent.copy(
                description = genericBody,
                roomName = null,
                senderDisambiguatedDisplayName = notifiableEvent.senderDisambiguatedDisplayName.takeIf { keepSender },
                senderAvatarUrl = null,
                roomAvatarUrl = null,
            )
        }
        return ResolvedPushEvent.Event(sanitized)
    }

    private fun currentExposure(): NotificationExposure = policyProvider.current().policy.notificationExposure
}
