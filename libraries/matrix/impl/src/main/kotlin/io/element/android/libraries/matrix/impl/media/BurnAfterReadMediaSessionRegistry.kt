/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.BurnAfterReadSession
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.securestorage.api.SecureMediaLeaseManager

/**
 * Process-memory bridge between an explicitly opened timeline item and the media decoder.
 *
 * Nothing is persisted: process death, account lock, or explicit close removes the capability.
 * The KMS still validates the signed session on every release; this registry is only the local
 * fail-closed hand-off and the owner of media-lease revocation.
 */
@Inject
@SingleIn(AppScope::class)
class BurnAfterReadMediaSessionRegistry(
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
) {
    private data class Key(val accountIdHash: String, val contentId: ContentId)

    private val sessions = linkedMapOf<Key, BurnAfterReadSession>()

    fun register(session: BurnAfterReadSession) {
        synchronized(sessions) {
            val key = Key(session.accountIdHash, session.contentId)
            val previous = sessions[key]
            check(previous == null || previous.sessionId == session.sessionId) {
                "A burn-after-read media capability is already active for this content."
            }
            sessions[key] = session
        }
    }

    fun get(accountIdHash: String, contentId: ContentId): BurnAfterReadSession? =
        synchronized(sessions) { sessions[Key(accountIdHash, contentId)] }

    fun remove(accountIdHash: String, contentId: ContentId, sessionId: String? = null): BurnAfterReadSession? {
        val removed = synchronized(sessions) {
            val key = Key(accountIdHash, contentId)
            val active = sessions[key] ?: return@synchronized null
            if (sessionId != null && active.sessionId != sessionId) return@synchronized null
            sessions.remove(key)
        }
        removed?.let { secureMediaLeaseManager.revokeScope(it.sessionId) }
        return removed
    }
}
