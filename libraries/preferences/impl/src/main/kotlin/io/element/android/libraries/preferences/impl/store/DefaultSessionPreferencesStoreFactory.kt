/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.impl.store

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.SessionPreferencesStoreFactory
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSessionPreferencesStoreFactory(
    @ApplicationContext private val context: Context,
    sessionObserver: SessionObserver,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : SessionPreferencesStoreFactory {
    private val lock = Any()
    private val cache = mutableMapOf<SessionId, DefaultSessionPreferencesStore>()
    private val closing = mutableMapOf<SessionId, Deferred<Unit>>()

    init {
        sessionObserver.addListener(object : SessionListener {
            override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
                retire(SessionId(userId), delete = true)?.await()
            }
        })
    }

    override fun get(sessionId: SessionId, sessionCoroutineScope: CoroutineScope): SessionPreferencesStore = synchronized(lock) {
        cache.getOrPut(sessionId) {
            DefaultSessionPreferencesStore(context, sessionId, sessionCoroutineScope, closing[sessionId])
        }
    }

    override fun remove(sessionId: SessionId) {
        retire(sessionId, delete = false)
    }

    private fun retire(sessionId: SessionId, delete: Boolean): Deferred<Unit>? = synchronized(lock) {
        val retired = cache.remove(sessionId)
        val previous = closing[sessionId]
        if (retired == null && !delete) return@synchronized previous
        retired?.stop()
        appCoroutineScope.async(start = CoroutineStart.LAZY) {
            previous?.await()
            retired?.awaitStopped()
            if (delete) {
                if (retired != null) {
                    retired.clear()
                } else {
                    val file = DefaultSessionPreferencesStore.storeFile(context, sessionId)
                    check(!file.exists() || file.delete()) { "Session preferences deletion failed" }
                }
            }
            Unit
        }.also { task ->
            closing[sessionId] = task
            task.start()
        }
    }
}
