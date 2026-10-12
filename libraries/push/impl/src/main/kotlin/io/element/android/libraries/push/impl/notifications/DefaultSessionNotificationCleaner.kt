/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.SessionNotificationCleaner

/**
 * Deliberately depends only on the notification store/displayer. Keeping MatrixClientProvider out
 * of this deletion path prevents a dependency cycle through the permanent-deletion coordinator.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSessionNotificationCleaner(
    private val notificationDisplayer: NotificationDisplayer,
    private val activeNotificationsProvider: ActiveNotificationsProvider,
) : SessionNotificationCleaner {
    override fun clearAllForSession(sessionId: SessionId) {
        activeNotificationsProvider.getNotificationsForSession(sessionId)
            .forEach { notificationDisplayer.cancelNotification(it.tag, it.id) }
    }
}
