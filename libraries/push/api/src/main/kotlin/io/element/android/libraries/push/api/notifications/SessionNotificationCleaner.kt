/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.api.notifications

import io.element.android.libraries.matrix.api.core.SessionId

/** Clears every Android notification owned by one Matrix session, without touching other sessions. */
interface SessionNotificationCleaner {
    fun clearAllForSession(sessionId: SessionId)
}
