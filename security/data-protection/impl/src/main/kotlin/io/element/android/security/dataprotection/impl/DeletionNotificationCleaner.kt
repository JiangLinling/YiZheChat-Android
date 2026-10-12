/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.SessionNotificationCleaner
import io.element.android.security.dataprotection.api.DeletionScope

interface DeletionNotificationCleaner {
    fun cancel(scope: DeletionScope.Account): Boolean
}

/**
 * Cancels only notification state belonging to the deleted Matrix session. Other accounts on the
 * same Android installation keep their notifications and delivery state.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDeletionNotificationCleaner(
    private val sessionNotificationCleaner: SessionNotificationCleaner,
) : DeletionNotificationCleaner {
    override fun cancel(scope: DeletionScope.Account): Boolean = runCatchingExceptions {
        sessionNotificationCleaner.clearAllForSession(SessionId(scope.localSessionId))
    }.isSuccess
}
