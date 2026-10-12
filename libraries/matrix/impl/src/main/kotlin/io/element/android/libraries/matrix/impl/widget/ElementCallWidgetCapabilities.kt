/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import io.element.android.appconfig.NextDeploymentConfig
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetEventFilter
import org.matrix.rustcomponents.sdk.getElementCallRequiredPermissions

/**
 * Adds the MatrixRTC membership event and sticky delivery semantics used by the
 * bundled Element Call build.
 *
 * SDK 26.06.3 grants the legacy `org.matrix.msc3401.call.member` state event, while
 * Element Call 0.20 publishes MSC4143 membership as the message-like event below.
 * Keep the SDK's constrained event filters and extend them only with that exact type.
 */
internal fun getElementCallRequiredPermissionsForDeployment(
    ownUserId: String,
    ownDeviceId: String,
): WidgetCapabilities = capabilitiesForDeployment(
    base = getElementCallRequiredPermissions(ownUserId, ownDeviceId),
    isNextDeployment = NextDeploymentConfig.isNextDeployment,
)

internal fun capabilitiesForDeployment(
    base: WidgetCapabilities,
    isNextDeployment: Boolean,
): WidgetCapabilities = if (isNextDeployment) {
    addNextRtcMembershipCapability(base)
} else {
    base
}

internal fun addNextRtcMembershipCapability(base: WidgetCapabilities): WidgetCapabilities {
    val rtcMember = WidgetEventFilter.MessageLikeWithType(NEXT_RTC_MEMBER_EVENT_TYPE)
    return base.copy(
        read = (base.read + rtcMember).distinct(),
        send = (base.send + rtcMember).distinct(),
        // Element Call 0.20 publishes MSC4143 membership as sticky events. The
        // event filter above still limits access to that exact event type; these
        // flags only allow the requested delivery semantics for the same event.
        sendStickyEvent = true,
        receiveStickyEvent = true,
    )
}

internal const val NEXT_RTC_MEMBER_EVENT_TYPE = "org.matrix.msc4143.rtc.member"
