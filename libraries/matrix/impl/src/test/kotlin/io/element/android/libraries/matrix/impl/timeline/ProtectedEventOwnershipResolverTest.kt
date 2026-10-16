/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import org.junit.Test
import org.junit.Assert.*

class ProtectedEventOwnershipResolverTest {
    @Test fun `cold media defers to original upload but live upload and caption retain registration`() {
        assertTrue(ProtectedEventOwnershipResolver.isHistoricalMediaRegistration(true, false, null))
        assertFalse(ProtectedEventOwnershipResolver.isHistoricalMediaRegistration(true, true, null))
        assertFalse(ProtectedEventOwnershipResolver.isHistoricalMediaRegistration(false, false, null))
        assertFalse(ProtectedEventOwnershipResolver.isHistoricalMediaRegistration(false, true, null))
        assertFalse(ProtectedEventOwnershipResolver.isHistoricalMediaRegistration(
            true, false, LifecycleOperationContext("original-upload-operation", FullAppLifecycleId.LC_16),
        ))
        // Scheduling must never synthesize the missing original SEND or a read grant.
        assertNull(ProtectedEventOwnershipResolver.resolve(input, null).sendContext)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN, ProtectedEventOwnershipResolver.resolve(input, null).ownership)
    }

    @Test fun `only acknowledged historical text binding can avoid registration replay`() {
        val bound = origin().copy(lifecycleId = FullAppLifecycleId.LC_13, contentKind = ContentKind.TEXT)
        assertTrue(ProtectedEventOwnershipResolver.hasRecordedTextBinding(bound, null, "\$event", false))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(bound, null, "\$event", true))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(bound, LifecycleOperationContext(bound.operationId, bound.lifecycleId), "\$event", false))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(bound.copy(eventId = null), null, "\$event", false))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(bound, null, "\$different", false))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(origin(), null, "\$event", false))
        assertFalse(ProtectedEventOwnershipResolver.hasRecordedTextBinding(null, null, "\$event", false))
    }

    @Test fun `bound restored sender journal retains owner without becoming a current send`() {
        val restored = origin()
        assertFalse(ProtectedEventOwnershipResolver.needsImmediateEventBinding(restored, null))
        assertEquals(restored.operationId, ProtectedEventOwnershipResolver.resolve(input, restored).sendContext?.operationId)
        assertTrue(ProtectedEventOwnershipResolver.needsImmediateEventBinding(restored.copy(eventId = null), null))
        assertNull(ProtectedEventOwnershipResolver.resolve(input, restored.copy(eventId = null)).sendContext)
    }

    @Test fun `only matching runtime owner promotes a bound journal to current mapping`() {
        val restored = origin()
        val current = LifecycleOperationContext(restored.operationId, restored.lifecycleId)
        assertTrue(ProtectedEventOwnershipResolver.needsImmediateEventBinding(restored, current))
        assertFalse(ProtectedEventOwnershipResolver.needsImmediateEventBinding(restored, LifecycleOperationContext("other-operation", restored.lifecycleId)))
        assertFalse(ProtectedEventOwnershipResolver.needsImmediateEventBinding(restored, LifecycleOperationContext(restored.operationId, FullAppLifecycleId.LC_13)))
        assertTrue(ProtectedEventOwnershipResolver.needsImmediateEventBinding(null, current))
        assertFalse(ProtectedEventOwnershipResolver.needsImmediateEventBinding(null, null))
    }
    @Test fun `original LC20 and LC15 call binding resumes same owner and rejects conflicting resources`() {
        listOf(FullAppLifecycleId.LC_20, FullAppLifecycleId.LC_15).forEach { lifecycle ->
            val current = input.copy(contentKind = ContentKind.CALL_OPERATION)
            val pending = origin(current).copy(eventId = null, lifecycleId = lifecycle)
            assertTrue(ProtectedEventOwnershipResolver.isAwaitingEventBinding(current, pending))
            assertNull(ProtectedEventOwnershipResolver.resolve(current, pending).sendContext)
            val bound = pending.copy(eventId = current.eventId)
            val restored = ProtectedEventOwnershipResolver.resolve(current, bound).sendContext!!
            assertEquals(pending.operationId, restored.operationId)
            assertEquals(lifecycle, restored.lifecycleId)
            listOf(current.copy(eventId = "\$other-event"), current.copy(carrierSha256 = "f".repeat(64)),
                current.copy(generation = 4), current.copy(matrixDeviceIdHash = "f".repeat(64)),
                current.copy(senderInstallationId = "other-installation")
            ).forEach { assertNull(ProtectedEventOwnershipResolver.resolve(it, bound).sendContext) }
            assertNull(ProtectedEventOwnershipResolver.resolve(current.copy(eventId = null, transactionId = "other-txn"), pending).sendContext)
        }
        ContentKind.entries.filter { it != ContentKind.CALL_OPERATION }.forEach { kind ->
            val current = input.copy(contentKind = kind)
            assertNull(ProtectedEventOwnershipResolver.resolve(current, origin(current).copy(lifecycleId = FullAppLifecycleId.LC_20)).sendContext)
        }
    }
    @Test fun `canonical transition waits without treating transaction as event authority`() {
        listOf(ContentKind.TEXT, ContentKind.POLL, ContentKind.RELAY).forEach { kind ->
            val current = input.copy(contentKind = kind)
            val pending = origin(current).copy(eventId = null,
                lifecycleId = if (kind == ContentKind.TEXT) FullAppLifecycleId.LC_13 else FullAppLifecycleId.LC_15)
            assertTrue(ProtectedEventOwnershipResolver.isAwaitingEventBinding(current, pending))
            assertNull(ProtectedEventOwnershipResolver.resolve(current, pending).sendContext)
            assertEquals(pending.operationId,
                ProtectedEventOwnershipResolver.resolve(current, pending.copy(eventId = current.eventId)).sendContext?.operationId)
        }
    }

    @Test fun `conflicting carrier sender device or bound event cannot enter pending send recovery`() {
        val pending = origin().copy(eventId = null)
        listOf(input.copy(carrierSha256 = "f".repeat(64)), input.copy(senderAccountIdHash = "f".repeat(64)),
            input.copy(matrixDeviceIdHash = "f".repeat(64)), input.copy(senderInstallationId = "other-device"),
            input.copy(contentKind = ContentKind.RELAY), input.copy(generation = 4),
            input.copy(eventId = "summary-surrogate"), input.copy(eventId = null)
        ).forEach { assertFalse(ProtectedEventOwnershipResolver.isAwaitingEventBinding(it, pending)) }
        assertFalse(ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, origin().copy(eventId = "\$other-event")))
        assertFalse(ProtectedEventOwnershipResolver.isAwaitingEventBinding(input, null))
    }

    private val input = ProtectedEventOwnershipResolver.Input(
        "a".repeat(64), "b".repeat(64), "installation-current", "a".repeat(64), "installation-current",
        "c".repeat(64), "\$event", "txn", "content", ContentKind.REACTION, "d".repeat(64), 3,
    )
    private fun origin(i: ProtectedEventOwnershipResolver.Input = input) = ProtectedEventOwnershipResolver.Origin(
        "original-send-operation", FullAppLifecycleId.LC_15, i.accountIdHash, i.matrixDeviceIdHash,
        i.installationId, i.roomIdHash, i.eventId, i.transactionId, i.contentId, i.contentKind, i.carrierSha256, i.generation,
    )
    @Test fun `all protected interactions share exact local origin routing`() {
        listOf(ContentKind.REACTION, ContentKind.POLL, ContentKind.POLL_OPERATION,
            ContentKind.RELAY, ContentKind.RELAY_OPERATION, ContentKind.MEDIA_CAPTION).forEach { kind ->
            val current = input.copy(contentKind = kind)
            val result = ProtectedEventOwnershipResolver.resolve(current, origin(current))
            assertEquals(ProtectedEventOwnershipResolver.Ownership.OWN_LOCAL_SEND, result.ownership)
            assertEquals("original-send-operation", result.sendContext?.operationId)
        }
    }
    @Test fun `same account alone cannot prove local echo after cold start`() {
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN, ProtectedEventOwnershipResolver.resolve(input, null).ownership)
    }
    @Test fun `same account other installation retains genuine read path`() {
        val result = ProtectedEventOwnershipResolver.resolve(input.copy(senderInstallationId = "installation-other"), null)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.OWN_OTHER_DEVICE, result.ownership)
        assertNull(result.sendContext)
    }
    @Test fun `remote sender retains genuine receive path`() {
        val result = ProtectedEventOwnershipResolver.resolve(input.copy(senderAccountIdHash = "e".repeat(64)), null)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.TRUE_REMOTE_RECEIVE, result.ownership)
        assertNull(result.sendContext)
    }
    @Test fun `origin cannot be transferred between subject resource kind or generation`() {
        val original = origin()
        listOf(input.copy(accountIdHash = "f".repeat(64)), input.copy(matrixDeviceIdHash = "f".repeat(64)),
            input.copy(installationId = "other-installation"), input.copy(roomIdHash = "f".repeat(64)),
            input.copy(contentId = "different"), input.copy(contentKind = ContentKind.RELAY),
            input.copy(carrierSha256 = "f".repeat(64)), input.copy(generation = 4),
            input.copy(eventId = "\$different"), input.copy(senderInstallationId = "other-installation")
        ).forEach { changed ->
            assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN, ProtectedEventOwnershipResolver.resolve(changed, original).ownership)
        }
    }
    @Test fun `summary surrogate cannot borrow matching transaction to become event`() {
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN,
            ProtectedEventOwnershipResolver.resolve(input.copy(eventId = "timestamp-sender-surrogate"), origin()).ownership)
    }
    @Test fun `pre sync transaction resolves only original pending identity`() {
        val pending = input.copy(eventId = null)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.OWN_LOCAL_SEND,
            ProtectedEventOwnershipResolver.resolve(pending, origin(pending)).ownership)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN,
            ProtectedEventOwnershipResolver.resolve(pending.copy(transactionId = "different"), origin(pending)).ownership)
    }
    @Test fun `missing all identities and foreign lifecycle cannot invent owner`() {
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN,
            ProtectedEventOwnershipResolver.resolve(input.copy(eventId = null, transactionId = null), null).ownership)
        assertEquals(ProtectedEventOwnershipResolver.Ownership.UNKNOWN,
            ProtectedEventOwnershipResolver.resolve(input, origin().copy(lifecycleId = FullAppLifecycleId.LC_25)).ownership)
    }
}
