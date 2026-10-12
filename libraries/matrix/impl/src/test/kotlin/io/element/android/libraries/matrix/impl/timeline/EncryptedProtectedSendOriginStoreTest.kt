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
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EncryptedProtectedSendOriginStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = ContentVersionLineageScope(AccountVaultId("vault-one"), "!room:example")
    private val origin = ProtectedEventOwnershipResolver.Origin(
        "original-send-operation", FullAppLifecycleId.LC_15, "a".repeat(64), "b".repeat(64),
        "installation-original", "c".repeat(64), null, null, "content-one", ContentKind.RELAY_OPERATION, "d".repeat(64),
    )
    @Test fun `relay recovery survives cold reopen without exposing intent in pointers`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val original = origin.copy(roomIdHash = roomHash())
        val first = EncryptedProtectedSendOriginStore(files, roots)
        first.remember(scope, original)
        first.rememberRelayEditRecovery(scope, "\$relay-root", original, "private original revision")
        val reopened = EncryptedProtectedSendOriginStore(files, roots)
        assertEquals(RecoverableRelayEditOrigin(original, "private original revision"),
            reopened.pendingRelayEditRecovery(scope, "\$relay-root"))
        assertNull(reopened.pendingRelayEditRecovery(scope.copy(vaultId = AccountVaultId("other")), "\$relay-root"))
        assertNull(reopened.pendingRelayEditRecovery(scope.copy(roomId = "!other:example"), "\$relay-root"))
        assertNull(reopened.pendingRelayEditRecovery(scope, "\$another-root"))
        temporary.root.walkTopDown().filter { it.extension == "yzcp" }.forEach {
            val pointer = it.readText()
            assertFalse(pointer.contains("private original revision"))
            assertFalse(pointer.contains("relay-root"))
            assertFalse(pointer.contains(original.operationId))
        }
        reopened.resolveRelayEditRecovery(scope, "\$relay-root", original.operationId)
        assertNull(EncryptedProtectedSendOriginStore(files, roots).pendingRelayEditRecovery(scope, "\$relay-root"))
        assertEquals(original, reopened.find(scope, original.contentId))
    }

    @Test fun `pending relay intent cannot be overwritten or resolved by another operation`() = runTest {
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val store = EncryptedProtectedSendOriginStore(InMemorySensitiveFileStore(), roots)
        val original = origin.copy(roomIdHash = roomHash())
        store.rememberRelayEditRecovery(scope, "\$relay-root", original, "revision one")
        store.rememberRelayEditRecovery(scope, "\$relay-root", original, "revision one")
        assertTrue(runCatching { store.rememberRelayEditRecovery(scope, "\$relay-root", original, "revision two") }.isFailure)
        val next = original.copy(operationId = "next-operation")
        assertTrue(runCatching { store.rememberRelayEditRecovery(scope, "\$relay-root", next, "revision two") }.isFailure)
        assertTrue(runCatching { store.resolveRelayEditRecovery(scope, "\$relay-root", next.operationId) }.isFailure)
        assertEquals("revision one", store.pendingRelayEditRecovery(scope, "\$relay-root")?.encodedIntent)
        store.resolveRelayEditRecovery(scope, "\$relay-root", original.operationId)
        store.rememberRelayEditRecovery(scope, "\$relay-root", original, "revision one")
        assertNull(store.pendingRelayEditRecovery(scope, "\$relay-root"))
        store.rememberRelayEditRecovery(scope, "\$relay-root", next, "revision two")
        assertEquals(next, store.pendingRelayEditRecovery(scope, "\$relay-root")?.origin)
    }

    private fun roomHash() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(scope.roomId.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `cold reopen retains original operation and authoritative event binding`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val first = EncryptedProtectedSendOriginStore(files, roots)
        first.remember(scope, origin)
        val bound = origin.copy(eventId = "\$original-event")
        first.remember(scope, bound)
        val reopened = EncryptedProtectedSendOriginStore(files, roots)
        assertEquals(bound, reopened.find(scope, origin.contentId))
        reopened.remember(scope, bound)
        assertEquals(bound, reopened.find(scope, origin.contentId))
        temporary.root.walkTopDown().filter { it.extension == "yzcp" }.forEach {
            val pointer = it.readText()
            assertFalse(pointer.contains(origin.operationId)); assertFalse(pointer.contains(origin.contentId))
            assertFalse(pointer.contains("original-event")); assertFalse(pointer.contains(origin.installationId))
        }
    }
    @Test fun `origin is isolated by vault room and content`() = runTest {
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val store = EncryptedProtectedSendOriginStore(InMemorySensitiveFileStore(), roots)
        store.remember(scope, origin)
        assertNull(store.find(scope.copy(vaultId = AccountVaultId("other-vault")), origin.contentId))
        assertNull(store.find(scope.copy(roomId = "!other:example"), origin.contentId))
        assertNull(store.find(scope, "different-content"))
    }
    @Test fun `producer transaction survives reopen but cannot authorize a copied canonical event`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val store = EncryptedProtectedSendOriginStore(files, roots)
        store.remember(scope, origin)
        store.remember(scope, origin.copy(transactionId = "sdk-transaction"))
        val reopened = EncryptedProtectedSendOriginStore(files, roots)
        val pending = requireNotNull(reopened.find(scope, origin.contentId))
        val input = ProtectedEventOwnershipResolver.Input(
            pending.accountIdHash, pending.matrixDeviceIdHash, pending.installationId, pending.accountIdHash,
            pending.installationId, pending.roomIdHash, null, "sdk-transaction", pending.contentId,
            pending.contentKind, pending.carrierSha256,
        )
        assertEquals(pending.operationId, ProtectedEventOwnershipResolver.resolve(input, pending).sendContext?.operationId)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "\$copied-event"), pending).sendContext)
        val bound = pending.copy(eventId = "\$actual-event")
        reopened.remember(scope, bound)
        val confirmed = requireNotNull(EncryptedProtectedSendOriginStore(files, roots).find(scope, origin.contentId))
        assertEquals(pending.operationId,
            ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "\$actual-event"), confirmed).sendContext?.operationId)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "\$copied-event"), confirmed).sendContext)
    }
    @Test fun `event and operation cannot be rebound after authoritative binding`() = runTest {
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val store = EncryptedProtectedSendOriginStore(InMemorySensitiveFileStore(), roots)
        val bound = origin.copy(eventId = "\$original-event")
        store.remember(scope, bound)
        listOf(bound.copy(eventId = "\$different-event"), bound.copy(operationId = "different-operation"),
            bound.copy(carrierSha256 = "e".repeat(64)), bound.copy(installationId = "other-installation")
        ).forEach { changed ->
            try { store.remember(scope, changed); fail("Origin rewrite was accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(bound, store.find(scope, origin.contentId))
        }
    }
}
