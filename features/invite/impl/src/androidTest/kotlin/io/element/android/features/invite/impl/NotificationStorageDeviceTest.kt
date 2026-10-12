/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationStorageDeviceTest {
    // Keep each instrumentation test isolated from application account data.
    private fun storageContext(): android.content.Context {
        val application = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = java.nio.file.Files.createTempDirectory("ncs").toFile()
        return object : android.content.ContextWrapper(application) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = directory
        }
    }
    private class Observer : SessionObserver {
        val listeners = mutableListOf<SessionListener>()
        override fun addListener(listener: SessionListener) { listeners += listener }
        override fun removeListener(listener: SessionListener) { listeners -= listener }
    }
    @Test fun encryptedHistorySurvivesRecreationAndRemainsDeviceBound() = runBlocking {
        val context = storageContext()
        val session = SessionId("@restart${UUID.randomUUID()}:example.test")
        val secure = InMemorySensitiveFileStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = DefaultSeenInvitesStore(context, session, scope, Observer(), secure, "DEVICE_A")
        val request = InviteNotification(RoomId("!remembered:example.test"), InviteNotification.Kind.GROUP, null, 100,
            displayName = "保留的群名", avatarUrl = "mxc://example.test/avatar")
        first.observeNotifications(listOf(request))
        first.markNotificationSeen(request.roomId)
        val saved = first.notifications().first().single()
        scope.cancel(); scope.coroutineContext[Job]?.join()
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = DefaultSeenInvitesStore(context, session, reopenedScope, Observer(), secure, "DEVICE_A")
        assertThat(reopened.notifications().first()).containsExactly(saved)
        reopenedScope.cancel(); reopenedScope.coroutineContext[Job]?.join()
        val foreignScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val foreign = DefaultSeenInvitesStore(context, session, foreignScope, Observer(), secure, "DEVICE_B")
            assertThat(foreign.notifications().first()).isEmpty()
            assertThat(runCatching { foreign.observeNotifications(emptyList()) }.isFailure).isTrue()
        } finally { foreignScope.cancel() }
    }
    @Test fun metadataPersistsAndAnotherAccountCannotSeeIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val otherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observer = Observer()
        val context = storageContext()
        val session = SessionId("@test${UUID.randomUUID()}:example.test")
        val secureStore = InMemorySensitiveFileStore()
        val store = DefaultSeenInvitesStore(context, session, scope, observer, secureStore, "DEVICE")
        val other = DefaultSeenInvitesStore(context,
            SessionId("@another${UUID.randomUUID()}:example.test"), otherScope, Observer(), secureStore, "DEVICE")
        val notice = InviteNotification(RoomId("!request:example.test"), InviteNotification.Kind.FRIEND, "@peer:example.test", 100)
        try {
            store.observeNotifications(listOf(notice))
            assertThat(store.notifications().first().single().roomId).isEqualTo(notice.roomId)
            assertThat(other.notifications().first()).isEmpty()
            assertThat(observer.listeners).hasSize(1)
            val sequence = store.notifications().first().single().sequence
            store.markNotificationSeen(notice.roomId)
            store.observeNotifications(listOf(notice))
            assertThat(store.notifications().first().single().seen).isTrue()
            assertThat(store.notifications().first().single().sequence).isEqualTo(sequence)
            store.updateNotificationProfiles(mapOf(notice.roomId to ("私密群名" to "mxc://example.test/avatar")))
            store.recordOutcome(notice.roomId, InviteNotification.Outcome.ACCEPTED, seen = false)
            assertThat(store.notifications().first().single().sequence).isGreaterThan(sequence)
            val confirmed = store.notifications().first().single()
            store.recordOutcome(notice.roomId, InviteNotification.Outcome.DECLINED, seen = false, expectedSequence = sequence)
            assertThat(store.notifications().first().single()).isEqualTo(confirmed)
            store.observeNotifications(emptyList(), completeSnapshot = true)
            assertThat(store.notifications().first().single().displayName).isEqualTo("私密群名")
            val files = java.io.File(context.filesDir, "datastore")
                .listFiles().orEmpty().filter { it.name.contains("seen-invites") }
            assertThat(files.any { it.readBytes().toString(Charsets.UTF_8).contains("私密群名") }).isFalse()
            store.clear()
            assertThat(store.notifications().first()).isEmpty()
        } finally { scope.cancel(); otherScope.cancel() }
    }
}
