/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.forward.impl

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.spyk
import org.junit.Test
import java.util.UUID

class ForwardOperationStoreTest {
    @Test
    fun `shared vault rejects stale snapshot save instead of overwriting success`() = runTest {
        val preferences = preferences()
        val files = InMemorySensitiveFileStore()
        val first = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val second = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val pair = ForwardPair(EventId("\$event:test"), RoomId("!room:test"))
        val created = first.create(operation("generation").copy(pairStates = mapOf(pair to ForwardPairState.Pending)))
        val stale = second.load("scope", 1_000L)!!
        val claim = first.claimPair("scope", created.generation, pair)!!
        first.finishPair("scope", created.generation, pair, claim.token, ForwardPairState.Success)

        assertFails { second.save(stale.copy(pairStates = mapOf(pair to ForwardPairState.Sending)), stale.revision) }
        assertThat(first.load("scope", 1_000L)?.pairStates?.get(pair)).isEqualTo(ForwardPairState.Success)
    }

    @Test
    fun `stale sender token cannot complete a reclaimed pair`() = runTest {
        val store = DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences())
        val pair = ForwardPair(EventId("\$event:test"), RoomId("!room:test"))
        val created = store.create(operation("generation").copy(pairStates = mapOf(pair to ForwardPairState.Pending)))
        val claim = store.claimPair("scope", created.generation, pair)!!

        assertFails { store.finishPair("scope", created.generation, pair, "stale-token", ForwardPairState.Success) }
        val completed = store.finishPair("scope", created.generation, pair, claim.token, ForwardPairState.Success)
        assertThat(completed.pairStates[pair]).isEqualTo(ForwardPairState.Success)
    }

    @Test
    fun `another store instance does not recover a live in-process claim`() = runTest {
        val preferences = preferences()
        val files = InMemorySensitiveFileStore()
        val sender = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val observer = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val pair = ForwardPair(EventId("\$event:test"), RoomId("!room:test"))
        val created = sender.create(operation("generation").copy(pairStates = mapOf(pair to ForwardPairState.Pending)))
        val claim = sender.claimPair("scope", created.generation, pair)!!

        val observed = observer.load("scope", 1_000L)
        assertThat(observed?.pairStates?.get(pair)).isEqualTo(ForwardPairState.Sending)
        assertThat(sender.finishPair("scope", created.generation, pair, claim.token, ForwardPairState.Success).pairStates[pair])
            .isEqualTo(ForwardPairState.Success)
    }

    @Test
    fun `different pair claims complete without overwriting each other`() = runTest {
        val store = DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences())
        val firstPair = ForwardPair(EventId("\$first:test"), RoomId("!room:test"))
        val secondPair = ForwardPair(EventId("\$second:test"), RoomId("!room:test"))
        val created = store.create(operation("generation").copy(pairStates = mapOf(firstPair to ForwardPairState.Pending, secondPair to ForwardPairState.Pending)))
        val firstClaim = store.claimPair("scope", created.generation, firstPair)!!
        val secondClaim = store.claimPair("scope", created.generation, secondPair)!!

        store.finishPair("scope", created.generation, firstPair, firstClaim.token, ForwardPairState.Success)
        val completed = store.finishPair("scope", created.generation, secondPair, secondClaim.token, ForwardPairState.Success)

        assertThat(completed.pairStates.values).containsExactly(ForwardPairState.Success, ForwardPairState.Success)
    }

    @Test
    fun `pair claim is atomic and cannot be claimed twice`() = runTest {
        val store = DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences())
        val pair = ForwardPair(EventId("\$event:test"), RoomId("!room:test"))
        val created = store.create(operation("generation").copy(pairStates = mapOf(pair to ForwardPairState.Pending)))

        val first = store.claimPair("scope", created.generation, pair)
        val second = store.claimPair("scope", created.generation, pair)

        assertThat(first?.operation?.pairStates?.get(pair)).isEqualTo(ForwardPairState.Sending)
        assertThat(second).isNull()
    }

    @Test
    fun `restart converts sending to ambiguous and keeps stable identity`() = runTest {
        val preferences = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("forward-${UUID.randomUUID()}", 0)
        val files = InMemorySensitiveFileStore()
        val pair = ForwardPair(EventId("\$event:test"), RoomId("!room:test"))
        val operation = PersistedForwardOperation("scope", "generation", "nonce", 2_000L, mapOf(pair to ForwardPairState.Sending))
        DefaultForwardOperationStore(FakeMatrixClient(), files, preferences).create(operation)

        val restored = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences).load("scope", 1_000L)

        assertThat(restored?.operationNonce).isEqualTo("nonce")
        assertThat(restored?.pairStates?.get(pair)).isInstanceOf(ForwardPairState.Ambiguous::class.java)
    }

    @Test
    fun `stale generation cannot clear a newer operation`() = runTest {
        val preferences = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("forward-${UUID.randomUUID()}", 0)
        val store = DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences)
        val operation = PersistedForwardOperation("scope", "new-generation", "nonce", 2_000L, emptyMap())
        store.create(operation)

        store.clear("scope", "old-generation")

        assertThat(store.load("scope", 1_000L)?.generation).isEqualTo("new-generation")
    }

    @Test
    fun `corrupt reference fails closed instead of returning empty state`() = runTest {
        val preferences = preferences()
        val store = DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences)
        store.create(operation("generation"))
        val key = preferences.all.keys.single { it.startsWith("forward_operation_ref_") }
        preferences.edit().putString(key, "not-json").commit()

        assertFails { DefaultForwardOperationStore(FakeMatrixClient(), InMemorySensitiveFileStore(), preferences).load("scope", 1_000L) }
    }

    @Test
    fun `commit failure keeps old reference readable`() = runTest {
        val real = preferences()
        val files = InMemorySensitiveFileStore()
        val firstStore = DefaultForwardOperationStore(FakeMatrixClient(), files, real)
        val created = firstStore.create(operation("generation"))
        val preferences = spyk(real)
        val editor = spyk(real.edit())
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false

        assertFails { DefaultForwardOperationStore(FakeMatrixClient(), files, preferences).save(created.copy(operationNonce = "new"), created.revision) }

        assertThat(DefaultForwardOperationStore(FakeMatrixClient(), files, real).load("scope", 1_000L)?.operationNonce).isEqualTo("nonce")
    }

    @Test
    fun `delete failure is journaled and recovered before next read`() = runTest {
        val preferences = preferences()
        val files = FlakyDeleteStore(InMemorySensitiveFileStore())
        val store = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val created = store.create(operation("generation"))
        files.refuseDelete = true
        assertFails { store.save(created.copy(operationNonce = "new"), created.revision) }
        files.refuseDelete = false

        assertThat(DefaultForwardOperationStore(FakeMatrixClient(), files, preferences).load("scope", 1_000L)?.operationNonce).isEqualTo("new")
    }

    @Test
    fun `two instances reject a stale generation`() = runTest {
        val preferences = preferences()
        val files = InMemorySensitiveFileStore()
        val first = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val second = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        first.create(operation("first"))

        assertFails { second.create(operation("second")) }
        assertThat(first.load("scope", 1_000L)?.generation).isEqualTo("first")
    }

    @Test
    fun `expiry preserves success and converts unresolved state to ambiguous`() = runTest {
        val preferences = preferences()
        val files = InMemorySensitiveFileStore()
        val store = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences)
        val success = ForwardPair(EventId("\$success:test"), RoomId("!a:test"))
        val pending = ForwardPair(EventId("\$pending:test"), RoomId("!b:test"))
        store.create(operation("generation").copy(pairStates = mapOf(success to ForwardPairState.Success, pending to ForwardPairState.Pending)))

        val restored = DefaultForwardOperationStore(FakeMatrixClient(), files, preferences).load("scope", 2_001L)

        assertThat(restored?.pairStates?.get(success)).isEqualTo(ForwardPairState.Success)
        assertThat(restored?.pairStates?.get(pending)).isInstanceOf(ForwardPairState.Ambiguous::class.java)
    }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (throwable: Throwable) {
            failure = throwable
        }
        assertThat(failure).isNotNull()
        return checkNotNull(failure)
    }

    private fun preferences() = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("forward-${UUID.randomUUID()}", 0)

    private fun operation(generation: String) = PersistedForwardOperation("scope", generation, "nonce", 2_000L, emptyMap())
}

private class FlakyDeleteStore(private val delegate: io.element.android.security.securestorage.api.SensitiveFileStore) :
    io.element.android.security.securestorage.api.SensitiveFileStore by delegate {
    var refuseDelete = false
    override suspend fun delete(reference: io.element.android.security.securestorage.api.SecureObjectReference): Boolean =
        if (refuseDelete) false else delegate.delete(reference)
}
