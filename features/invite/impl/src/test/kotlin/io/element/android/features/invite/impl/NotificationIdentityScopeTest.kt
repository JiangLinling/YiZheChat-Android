/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class NotificationIdentityScopeTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val observer = object : SessionObserver {
        override fun addListener(listener: SessionListener) = Unit
        override fun removeListener(listener: SessionListener) = Unit
    }

    @Test
    fun `a returning login rebuilds from current Matrix without reading or deleting the old vault`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = MemoryPreferences()
        val session = SessionId("@returning:example.test")
        val secure = InMemorySensitiveFileStore()
        val context = RuntimeEnvironment.getApplication()
        val old = DefaultSeenInvitesStore(context, session, scope, observer, secure, "OLD", prefs)
        val oldNotice = InviteNotification(RoomId("!old:example.test"), InviteNotification.Kind.FRIEND, "@old:example.test", 100)
        try {
            old.observeNotifications(listOf(oldNotice))
            secure.deleteVault(deriveAccountVaultId(session.value, "OLD"))
            val currentVault = deriveAccountVaultId(session.value, "NEW")
            val reads = mutableListOf<SecureObjectReference>()
            val deleted = mutableListOf<SecureObjectReference>()
            val guarded = object : SensitiveFileStore by secure {
                override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T {
                    check(reference.vaultId == currentVault)
                    reads += reference
                    return secure.read(reference, readPlaintext)
                }
                override suspend fun delete(reference: SecureObjectReference): Boolean {
                    check(reference.vaultId == currentVault)
                    deleted += reference
                    return secure.delete(reference)
                }
            }
            val current = DefaultSeenInvitesStore(context, session, scope, observer, guarded, "NEW", prefs)
            assertThat(current.notifications().first()).isEmpty()
            val notice = InviteNotification(RoomId("!current:example.test"), InviteNotification.Kind.GROUP, null, 200)
            current.observeNotifications(listOf(notice))
            assertThat(current.notifications().first().map { it.roomId }).containsExactly(notice.roomId)
            assertThat(reads.all { it.vaultId == currentVault }).isTrue()
            assertThat(deleted).isEmpty()
        } finally { scope.cancel() }
    }

    @Test
    fun `a temporarily unreadable valid current snapshot is retained and never overwritten`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = MemoryPreferences()
        val session = SessionId("@locked:example.test")
        val secure = InMemorySensitiveFileStore()
        val context = RuntimeEnvironment.getApplication()
        try {
            val initial = DefaultSeenInvitesStore(context, session, scope, observer, secure, "CURRENT", prefs)
            initial.observeNotifications(listOf(InviteNotification(RoomId("!retained:example.test"), InviteNotification.Kind.GROUP, null, 100)))
            val saved = prefs.data.value
            val unavailable = object : SensitiveFileStore by secure {
                override suspend fun <T> read(reference: SecureObjectReference, readPlaintext: suspend (InputStream) -> T): T =
                    throw IOException("Current vault temporarily unavailable")
            }
            val reopened = DefaultSeenInvitesStore(context, session, scope, observer, unavailable, "CURRENT", prefs)
            assertThat(reopened.notifications().first()).isEmpty()
            val result = runCatching { reopened.observeNotifications(emptyList(), completeSnapshot = true) }
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(prefs.data.value).isEqualTo(saved)
            assertThat(initial.notifications().first().map { it.roomId }).containsExactly(RoomId("!retained:example.test"))
        } finally { scope.cancel() }
    }
}
