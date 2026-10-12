/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class ProtectedRelayEditPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files = InMemorySensitiveFileStore()
    private val scope = ContentVersionLineageScope(AccountVaultId("vault-one"), "!room:example")
    private val intentHash = "d".repeat(64)
    private val contentId = relayEditPreparedContentId(scope.roomId, "relay-edit-original-0001")
    private val expected = ProtectedEventOwnershipResolver.Origin(
        "relay-edit-original-0001", FullAppLifecycleId.LC_15, "a".repeat(64), "b".repeat(64),
        "installation-original", "c".repeat(64), null, null, contentId.value, ContentKind.RELAY_OPERATION, "",
    )
    private fun store() = EncryptedProtectedSendOriginStore(files,
        mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root })

    private suspend fun commit(store: ProtectedSendOriginStore, carrier: String = "opaque-encrypted-carrier"): PreparedProtectedContent {
        store.rememberPrepared(scope, expected.copy(carrierSha256 = accountIdHash(carrier), generation = 3), carrier, intentHash)
        return PreparedProtectedContent(carrier, contentId)
    }

    @Test fun `cold retry preserves exact original carrier and authorizes instead of creating another resource`() = runTest {
        val firstStore = store()
        val creations = AtomicInteger()
        val prepared = prepareRelayEdit(firstStore, scope, expected, intentHash,
            { error("No retained carrier yet") }, { _, beforeStore -> beforeStore(); creations.incrementAndGet(); commit(firstStore) })
        val reopened = store()
        var authorized = 0
        val retry = prepareRelayEdit(reopened, scope, expected, intentHash,
            { carrier -> assertEquals(prepared.encodedEnvelope, carrier); authorized++ },
            { _, _ -> creations.incrementAndGet(); error("Retry created another resource") })
        assertEquals(prepared, retry)
        assertEquals(1, creations.get())
        assertEquals(1, authorized)
        temporary.root.walkTopDown().filter { it.isFile && it.extension == "yzcp" }.forEach {
            assertFalse(it.readText().contains(prepared.encodedEnvelope))
            assertFalse(it.readText().contains(intentHash))
        }
    }

    @Test fun `concurrent owners serialize preparation and both use one committed carrier`() = runTest {
        val firstStore = store()
        val secondStore = store()
        val creating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val creations = AtomicInteger()
        val first = async(Dispatchers.Default) {
            prepareRelayEdit(firstStore, scope, expected, intentHash, { }, { _, beforeStore ->
                beforeStore(); creations.incrementAndGet(); creating.complete(Unit); release.await(); commit(firstStore)
            })
        }
        creating.await()
        val second = async(Dispatchers.Default) {
            secondStarted.complete(Unit)
            prepareRelayEdit(secondStore, scope, expected, intentHash, { }, { _, beforeStore ->
                beforeStore(); creations.incrementAndGet(); commit(secondStore, "second-carrier")
            })
        }
        secondStarted.await()
        assertNull(withTimeoutOrNull(100) { second.await() })
        assertEquals(1, creations.get())
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, creations.get())
    }

    @Test fun `current authorization rejection keeps original record and cannot create or enqueue new payload`() = runTest {
        val originalStore = store()
        prepareRelayEdit(originalStore, scope, expected, intentHash, { }, { _, beforeStore -> beforeStore(); commit(originalStore) })
        val reopened = store()
        val before = reopened.prepared(scope, contentId.value)
        var created = 0
        var returned = false
        try {
            prepareRelayEdit(reopened, scope, expected, intentHash, { error("KMS denies current generation") }, { _, _ ->
                created++; commit(reopened)
            })
            returned = true
        } catch (_: IllegalStateException) { }
        assertFalse(returned)
        assertEquals(0, created)
        assertEquals(before, reopened.prepared(scope, contentId.value))
    }

    @Test fun `changed payload or device cannot borrow original operation preparation`() = runTest {
        val originalStore = store()
        prepareRelayEdit(originalStore, scope, expected, intentHash, { }, { _, beforeStore -> beforeStore(); commit(originalStore) })
        var callbacks = 0
        listOf(expected.copy(matrixDeviceIdHash = "e".repeat(64)) to intentHash,
            expected.copy(installationId = "another-installation") to intentHash,
            expected to "e".repeat(64)).forEach { (different, hash) ->
            var rejected = false
            try { prepareRelayEdit(store(), scope, different, hash, { callbacks++ }, { _, _ -> callbacks++; commit(store()) }) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        assertEquals(0, callbacks)
    }

    @Test fun `transaction and canonical event binding preserve immutable prepared payload after reopen`() = runTest {
        val originalStore = store()
        val prepared = prepareRelayEdit(originalStore, scope, expected, intentHash, { }, { _, beforeStore -> beforeStore(); commit(originalStore) })
        val origin = requireNotNull(originalStore.find(scope, contentId.value))
        originalStore.remember(scope, origin.copy(transactionId = contentId.value))
        originalStore.remember(scope, origin.copy(transactionId = contentId.value, eventId = "\$accepted-event"))
        val retained = requireNotNull(store().prepared(scope, contentId.value))
        assertEquals(prepared.encodedEnvelope, retained.encodedEnvelope)
        assertEquals(intentHash, retained.intentSha256)
        assertEquals("\$accepted-event", retained.origin.eventId)
        var rejected = false
        try { store().rememberPrepared(scope, retained.origin, retained.encodedEnvelope, "e".repeat(64)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(retained, store().prepared(scope, contentId.value))
    }

    @Test fun `interrupted protection retains original intent and cannot create a second uncertain share`() = runTest {
        val originalStore = store()
        var created = 0
        try { prepareRelayEdit(originalStore, scope, expected, intentHash, { }, { _, beforeStore -> beforeStore(); created++; error("Lost protection response") }) }
        catch (_: IllegalStateException) { }
        assertEquals(1, created)
        val reopened = store()
        assertEquals(expected, reopened.find(scope, contentId.value))
        assertNull(reopened.prepared(scope, contentId.value))
        var rejected = false
        try { prepareRelayEdit(reopened, scope, expected, intentHash, { }, { _, _ -> created++; commit(reopened) }) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, created)
    }

    @Test fun `unknown original probes authority and still never prepares a second payload`() = runTest {
        val journal = store()
        journal.reservePreparation(scope, expected, intentHash)
        journal.markPreparationStarted(scope, expected, intentHash)
        var probes = 0
        try {
            prepareRelayEdit(store(), scope, expected, intentHash, { error("No retained carrier") },
                { _, _ -> error("Unknown original created another resource") }, reconcileOriginal = { probes++ })
            fail("Unknown original resumed preparation")
        } catch (_: IllegalStateException) { }
        assertEquals(1, probes)
        assertEquals(true, store().reserved(scope, contentId.value)?.preparationStarted)
    }

    @Test fun `destroyed original carrier stops before ciphertext authorization or any fresh preparation`() = runTest {
        val journal = store()
        prepareRelayEdit(journal, scope, expected, intentHash, {}, { _, boundary -> boundary(); commit(journal) })
        var probes = 0
        try {
            prepareRelayEdit(store(), scope, expected, intentHash, { error("Destroyed original dispatched") },
                { _, _ -> error("Destroyed original prepared again") }, reconcileOriginal = {
                    probes++; error("Original operation has signed FAILED_FINAL")
                })
            fail("Destroyed original returned a dispatchable carrier")
        } catch (_: IllegalStateException) { }
        assertEquals(1, probes)
        assertNotNull(store().prepared(scope, contentId.value))
    }

    @Test fun `cold interruption before KMS dispatch resumes the original reservation exactly once`() = runTest {
        val first = store()
        try { prepareRelayEdit(first, scope, expected, intentHash, { }, { _, _ -> error("Stopped before KMS") }) }
        catch (_: IllegalStateException) { }
        assertEquals(false, store().reserved(scope, contentId.value)?.preparationStarted)
        val reopened = store()
        var stores = 0
        val prepared = prepareRelayEdit(reopened, scope, expected, intentHash, { error("No complete carrier") }, { stableId, beforeStore ->
            assertEquals(contentId, stableId)
            beforeStore()
            assertEquals(true, store().reserved(scope, contentId.value)?.preparationStarted)
            stores++
            commit(reopened)
        })
        assertEquals(1, stores)
        assertEquals(expected.operationId, store().prepared(scope, contentId.value)?.origin?.operationId)
        assertEquals(prepared.encodedEnvelope, store().prepared(scope, contentId.value)?.encodedEnvelope)
    }

    @Test fun `unsent reservation still rejects another intent or installation before protection`() = runTest {
        store().reservePreparation(scope, expected, intentHash)
        var calls = 0
        listOf(expected.copy(installationId = "other") to intentHash, expected to "e".repeat(64)).forEach { (origin, hash) ->
            try {
                prepareRelayEdit(store(), scope, origin, hash, { calls++ }, { _, _ -> calls++; commit(store()) })
                fail("Changed original intent was resumed")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
        assertEquals(false, store().reserved(scope, contentId.value)?.preparationStarted)
    }

    @Test fun `dispatch boundary is monotonic and a carrier cannot bypass it`() = runTest {
        val journal = store()
        journal.reservePreparation(scope, expected, intentHash)
        try { commit(journal); fail("Carrier bypassed boundary") }
        catch (_: IllegalArgumentException) { }
        journal.markPreparationStarted(scope, expected, intentHash)
        journal.markPreparationStarted(scope, expected, intentHash)
        assertEquals(true, store().reserved(scope, contentId.value)?.preparationStarted)
        try { journal.reservePreparation(scope, expected, intentHash); fail("Dispatched intent was reset") }
        catch (_: IllegalArgumentException) { }
        assertEquals(true, store().reserved(scope, contentId.value)?.preparationStarted)
    }

    @Test fun `legacy reservation without a dispatch boundary remains unknown after decode`() = runTest {
        val codec = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val legacy = ProtectedSendOriginRecord.from(expected).copy(preparedIntentSha256 = intentHash)
        val encoded = codec.encodeToString(ProtectedSendOriginRecord.serializer(), legacy)
        assertFalse(encoded.contains("preparationStarted"))
        val decoded = codec.decodeFromString(ProtectedSendOriginRecord.serializer(), encoded)
        assertNull(decoded.preparationStarted)
        val journal = store()
        journal.reservePreparation(scope, expected, intentHash)
        val legacyView = object : ProtectedSendOriginStore by journal {
            override suspend fun reserved(scope: ContentVersionLineageScope, contentId: String) =
                ReservedProtectedSendOrigin(decoded.origin(), requireNotNull(decoded.preparedIntentSha256), decoded.preparationStarted)
        }
        var calls = 0
        try {
            prepareRelayEdit(legacyView, scope, expected, intentHash, { calls++ }, { _, _ -> calls++; commit(journal) })
            fail("Legacy unknown boundary was retried")
        } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }
}
