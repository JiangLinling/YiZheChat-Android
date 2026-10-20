/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.api.notifications

/**
 * Keeps the incoming-call notification channel aligned with the current system interruption policy.
 */
interface CallNotificationPolicyService {
    /** Exact channels currently used for delivery; null means unavailable, never denied. */
    fun messageNotificationChannelId(): String? = null
    fun ringingNotificationChannelId(): String? = null

    /** Whether the user granted access to bypass Do Not Disturb for urgent calls. */
    fun isDoNotDisturbAccessGranted(): Boolean

    /**
     * Recreates the ringing-call channel when Do Not Disturb access changes.
     */
    fun refreshRingingCallChannel()
}
