/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveLocationSendOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = ContentVersionLineageScope(AccountVaultId("vault-location"), "!room:example")
    private val input = ProtectedEventOwnershipResolver.Input(
        "a".repeat(64), "b".repeat(64), "installation-current", "a".repeat(64), "installation-current",
        "c".repeat(64), "\$location-event", "location-content", "location-content", ContentKind.LIVE_LOCATION,
        "d".repeat(64), 3,
    )
    private val origin = ProtectedEventOwnershipResolver.Origin(
        "original-location-operation", FullAppLifecycleId.LC_17, input.accountIdHash, input.matrixDeviceIdHash,
        input.installationId, input.roomIdHash, null, input.transactionId, input.contentId,
        input.contentKind, input.carrierSha256, input.generation,
    )

    @Test fun `all live carriers preserve separate exact event owners across encrypted journal reopen`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        for ((index, kind) in listOf(ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION,
            ContentKind.LIVE_LOCATION_OPERATION, ContentKind.LIVE_LOCATION_OPERATION, ContentKind.LIVE_LOCATION_OPERATION).withIndex()) {
            val current = input.copy(contentId = "live-content-$index", transactionId = "live-content-$index",
                eventId = "\$live-event-$index", contentKind = kind)
            val candidate = origin.copy(contentId = current.contentId, transactionId = current.transactionId,
                contentKind = kind, eventId = null)
            val journal = EncryptedProtectedSendOriginStore(files, roots)
            journal.remember(scope, candidate.copy(transactionId = null))
            journal.remember(scope, candidate)
            val reopened = EncryptedProtectedSendOriginStore(files, roots)
            assertNull(ProtectedEventOwnershipResolver.resolve(current, reopened.find(scope, current.contentId)).sendContext)
            assertTrue(ProtectedEventOwnershipResolver.isAwaitingEventBinding(current, reopened.find(scope, current.contentId)))
            assertEquals(candidate.operationId,
                ProtectedEventOwnershipResolver.resolve(current.copy(eventId = null), reopened.find(scope, current.contentId)).sendContext?.operationId)
            reopened.remember(scope, candidate.copy(eventId = current.eventId))
            val bound = EncryptedProtectedSendOriginStore(files, roots).find(scope, current.contentId)
            assertEquals(candidate.operationId, ProtectedEventOwnershipResolver.resolve(current, bound).sendContext?.operationId)
            assertNull(ProtectedEventOwnershipResolver.resolve(current.copy(eventId = "\$replayed"), bound).sendContext)
            assertNull(ProtectedEventOwnershipResolver.resolve(current.copy(matrixDeviceIdHash = "f".repeat(64)), bound).sendContext)
            assertNull(ProtectedEventOwnershipResolver.resolve(current.copy(generation = 99), bound).sendContext)
            assertTrue(runCatching { reopened.remember(scope, candidate.copy(eventId = "\$other")) }.isFailure)
        }
    }

    @Test fun `retained live intent rejects different owner device room kind and payload`() {
        val retained = PreparedProtectedSendOrigin(origin, "sealed-carrier", "intent-hash")
        retained.requireLiveLocationIntent(origin, "intent-hash")
        for (changed in listOf(origin.copy(operationId = "other-original-operation"), origin.copy(matrixDeviceIdHash = "e".repeat(64)),
            origin.copy(installationId = "other-installation"), origin.copy(roomIdHash = "f".repeat(64)),
            origin.copy(contentKind = ContentKind.LOCATION), origin.copy(contentId = "other-content"))) {
            assertTrue(runCatching { retained.requireLiveLocationIntent(changed, "intent-hash") }.isFailure)
        }
        assertTrue(runCatching { retained.requireLiveLocationIntent(origin, "changed-intent") }.isFailure)
    }

    @Test fun `stop clears only live producer payloads in its account and room`() {
        val cache = PendingProtectedPayloadCache({ 1000L })
        val identity = PendingProtectedPayloadCache.Identity(input.matrixDeviceIdHash, input.installationId, input.carrierSha256, 7, 9)
        val live = PendingProtectedPayloadCache.Key(ContentId("live-buffer"), input.accountIdHash, scope.roomId, "ciphertext-live")
        val text = live.copy(contentId = ContentId("text-buffer"))
        val other = live.copy(roomId = "!other:example")
        val liveOwner = PendingProtectedPayloadCache.Owner(LifecycleOperationContext(origin.operationId, FullAppLifecycleId.LC_17),
            identity, 2000L, ContentKind.LIVE_LOCATION_OPERATION)
        cache.put(live, "coordinate".encodeToByteArray(), liveOwner)
        cache.put(other, "other-coordinate".encodeToByteArray(), liveOwner)
        cache.put(text, "text".encodeToByteArray(), liveOwner.copy(operation = LifecycleOperationContext("text-original-operation", FullAppLifecycleId.LC_13), contentKind = ContentKind.TEXT))
        val ticket = checkNotNull(cache.ownership(live, identity))
        cache.clearLiveLocation(input.accountIdHash, scope.roomId)
        assertNull(cache.get(ticket))
        assertNull(cache.ownership(live, identity))
        assertNotNull(cache.ownership(other, identity))
        assertNotNull(cache.ownership(text, identity))
        cache.clear()
    }

    @Test fun `expired live pending payload cannot be revived by retained ownership`() {
        var now = 1_000L
        val cache = PendingProtectedPayloadCache({ now }, ttlMillis = 100L)
        val key = PendingProtectedPayloadCache.Key(ContentId(input.contentId), input.accountIdHash, scope.roomId, "ciphertext")
        val identity = PendingProtectedPayloadCache.Identity(input.matrixDeviceIdHash, input.installationId, input.carrierSha256, 7, 9)
        cache.put(key, "test-payload".encodeToByteArray(), PendingProtectedPayloadCache.Owner(
            LifecycleOperationContext(origin.operationId, FullAppLifecycleId.LC_17), identity, 1_050L, ContentKind.LIVE_LOCATION))
        val ticket = checkNotNull(cache.ownership(key, identity))
        now = 1_051L
        assertNull(cache.ownership(key, identity))
        assertNull(cache.get(ticket))
        cache.clear()
        assertNull(cache.get(ticket))
    }

    @Test fun `delayed binding remains unknown until exact Matrix event mapping survives reopen`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = mockk<SecureStorageRoots> { every { persistentRoot() } returns temporary.root }
        val producer = EncryptedProtectedSendOriginStore(files, roots)
        producer.remember(scope, origin.copy(transactionId = null))
        producer.remember(scope, origin)
        val reopened = EncryptedProtectedSendOriginStore(files, roots)
        val pending = reopened.find(scope, origin.contentId)
        assertTrue(ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, pending))
        assertNull(ProtectedEventOwnershipResolver.resolve(input, pending).sendContext)
        assertEquals(origin.operationId,
            ProtectedEventOwnershipResolver.resolve(input.copy(eventId = null), pending).sendContext?.operationId)
        // This models successful existing registration, not a grant from an enqueue acknowledgement.
        val bound = origin.copy(eventId = input.eventId)
        reopened.remember(scope, bound)
        reopened.remember(scope, bound) // Duplicate sync must keep one owner and mapping.
        val confirmed = EncryptedProtectedSendOriginStore(files, roots).find(scope, origin.contentId)
        val result = ProtectedEventOwnershipResolver.resolve(input, confirmed)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.OWN_LOCAL_SEND, result.ownership)
        assertEquals(FullAppLifecycleId.LC_17, result.sendContext?.lifecycleId)
        assertEquals(origin.operationId, result.sendContext?.operationId)
        assertNull(ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "\$copied-location"), confirmed).sendContext)
        assertTrue(runCatching { reopened.remember(scope, bound.copy(eventId = "\$different")) }.isFailure)
        assertEquals(bound, reopened.find(scope, origin.contentId))
    }

    @Test fun `LC17 cannot own unrelated content kinds`() {
        ContentKind.entries.filter { it !in setOf(ContentKind.LOCATION, ContentKind.LIVE_LOCATION, ContentKind.LIVE_LOCATION_OPERATION) }.forEach { kind ->
            val current = input.copy(contentKind = kind)
            val candidate = origin.copy(eventId = input.eventId, contentKind = kind)
            assertNull(ProtectedEventOwnershipResolver.resolve(current, candidate).sendContext)
            assertFalse(PendingProtectedPayloadCache.acceptsOwner(
                LifecycleOperationContext(origin.operationId, FullAppLifecycleId.LC_17), kind))
        }
    }

    @Test fun `remote and other installation locations keep genuine receive routing`() {
        assertEquals(ProtectedEventOwnershipResolver.Ownership.TRUE_REMOTE_RECEIVE,
            ProtectedEventOwnershipResolver.resolve(input.copy(senderAccountIdHash = "e".repeat(64)), null).ownership)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.OWN_OTHER_DEVICE,
            ProtectedEventOwnershipResolver.resolve(input.copy(senderInstallationId = "other"), null).ownership)
        assertNull(ProtectedEventOwnershipResolver.resolve(input, null).sendContext)
    }

    @Test fun `location ownership rejects account device room carrier generation and event conflicts`() {
        val bound = origin.copy(eventId = input.eventId)
        listOf(input.copy(accountIdHash = "f".repeat(64)), input.copy(matrixDeviceIdHash = "f".repeat(64)),
            input.copy(installationId = "other"), input.copy(senderInstallationId = "other"),
            input.copy(roomIdHash = "f".repeat(64)), input.copy(contentId = "other"),
            input.copy(carrierSha256 = "f".repeat(64)), input.copy(generation = 4),
            input.copy(eventId = "\$other"), input.copy(eventId = "summary-surrogate")
        ).forEach { assertNull(ProtectedEventOwnershipResolver.resolve(it, bound).sendContext) }
    }

    @Test fun `location pending payload is bounded and never substitutes for canonical event mapping`() {
        var now = 1_000L
        val cache = PendingProtectedPayloadCache({ now }, ttlMillis = 100L)
        val key = PendingProtectedPayloadCache.Key(ContentId(input.contentId), input.accountIdHash, scope.roomId, "ciphertext")
        val identity = PendingProtectedPayloadCache.Identity(input.matrixDeviceIdHash, input.installationId, input.carrierSha256, 7, 9)
        val operation = LifecycleOperationContext(origin.operationId, FullAppLifecycleId.LC_17)
        cache.put(key, "location-test-payload".encodeToByteArray(), PendingProtectedPayloadCache.Owner(
            operation, identity, 2_000L, ContentKind.LOCATION,
        ))
        val ticket = checkNotNull(cache.ownership(key, identity))
        assertEquals(operation, ticket.owner.operation)
        assertNull(ProtectedEventOwnershipResolver.resolve(input, origin).sendContext)
        assertNull(cache.ownership(key, identity.copy(authorizationRevision = 8)))
        now = 1_101L
        assertNull(cache.ownership(key, identity))
        assertNull(cache.get(ticket))
    }
}
