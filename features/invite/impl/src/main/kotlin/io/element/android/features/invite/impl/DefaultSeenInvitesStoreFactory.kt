/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.invite.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.invite.api.SeenInvitesStore
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSeenInvitesStoreFactory(
    @ApplicationContext private val context: Context,
    private val sessionObserver: SessionObserver,
    private val sensitiveFileStore: SensitiveFileStore,
) : SeenInvitesStoreFactory {
    // We can have only one class accessing a single data store, so keep a cache of them.
    private data class Entry(val deviceId: String, val store: SeenInvitesStore)
    private val cache = ConcurrentHashMap<SessionId, Entry>()

    init {
        sessionObserver.addListener(object : SessionListener {
            override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
                cache.remove(SessionId(userId))
            }
        })
    }

    override fun getOrCreate(
        sessionId: SessionId,
        sessionCoroutineScope: CoroutineScope,
        deviceId: String,
    ): SeenInvitesStore {
        val entry = cache.computeIfAbsent(sessionId) {
            Entry(deviceId, DefaultSeenInvitesStore(
                context = context,
                sessionId = sessionId,
                sessionCoroutineScope = sessionCoroutineScope,
                sessionObserver = sessionObserver,
                sensitiveFileStore = sensitiveFileStore,
                deviceId = deviceId,
            ))
        }
        check(entry.deviceId == deviceId) { "Notification history device identity changed before session disposal" }
        return entry.store
    }
}
