/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FileOpenReferenceStoreTest {
    private val room = RoomId("!qa-opened-room:example.org")
    private val otherRoom = RoomId("!other-room:example.org")
    private val event = EventId("\$qa-opened-file")

    @Test
    fun `file display survives store reload and stays within account and room`() = runTest {
        val fixture = Fixture(this)
        val first = fixture.create()
        first.markFileOpened(room, event)
        val reloaded = fixture.create()
        reloaded.load()
        assertThat(reloaded.state.value.isFileOpened(room, event)).isTrue()
        assertThat(reloaded.state.value.isFileOpened(otherRoom, event)).isFalse()
        val otherAccount = fixture.create(FakeMatrixClient(sessionId = SessionId("@other:example.org")))
        otherAccount.load()
        assertThat(otherAccount.state.value.isFileOpened(room, event)).isFalse()
    }

    @Test
    fun `local message and conversation deletion remove only their opened references`() = runTest {
        val fixture = Fixture(this)
        val store = fixture.create()
        val second = EventId("\$qa-second-file")
        store.markFileOpened(room, event)
        store.markFileOpened(room, second)
        store.markFileOpened(otherRoom, event)
        store.deleteLocally(room, listOf(event))
        assertThat(store.state.value.isFileOpened(room, event)).isFalse()
        store.markFileOpened(room, event)
        assertThat(store.state.value.isFileOpened(room, event)).isFalse()
        store.deleteConversation(room, 1234)
        assertThat(store.state.value.isFileOpened(room, second)).isFalse()
        assertThat(store.state.value.isFileOpened(otherRoom, event)).isTrue()
    }

    @Test
    fun `destroyed account vault cannot restore an opened file projection`() = runTest {
        val fixture = Fixture(this)
        fixture.create().markFileOpened(room, event)
        fixture.files.deleteVault(deriveAccountVaultId(fixture.matrix.sessionId.value, fixture.matrix.deviceId.value))
        val reloaded = fixture.create()
        reloaded.load()
        assertThat(reloaded.state.value.isFileOpened(room, event)).isFalse()
    }

    private class Fixture(private val scope: TestScope) {
        val matrix = FakeMatrixClient()
        val files = InMemorySensitiveFileStore()
        private val preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("qa-file-open-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        private val synchronizer = mockk<FavouriteReferenceSynchronizer> {
            coEvery { refresh() } returns emptySet()
        }
        fun create(client: FakeMatrixClient = matrix) = DefaultMessageReferenceStore(
            matrixClient = client,
            sensitiveFileStore = files,
            sharedPreferences = preferences,
            favouriteSynchronizer = synchronizer,
            sessionCoroutineScope = scope.backgroundScope,
            coordinator = FakeAppOperationalStateCoordinator(),
        )
    }
}
