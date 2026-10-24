/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.network.*
import io.element.android.features.discovery.impl.session.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryReadingsTest {
    private class Fixture {
        val identity = DiscoveryAccountScope("a".repeat(64), "b".repeat(64), "installation", 1, 100)
        var current: DiscoveryAccountScope? = identity
        var rows = emptyList<DiscoveryReadingVisit>()
        var loseResponse = false
        var forgedReceipt = false
        val receipts = mutableMapOf<String, JsonObject>()
        val posted = mutableListOf<DiscoveryArticleView>()
        val access = mockk<DefaultDiscoveryAccess>()
        val gate = mockk<AndroidDiscoveryIdentityGate>()
        val authority = mockk<DiscoveryCodeClient>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val store = mockk<SecureDiscoveryInteractionStore>()
        val clock = mockk<AndroidDiscoveryClock> {
            every { wallMillis() } returns 100000L
            every { elapsedMillis() } returns 0L
        }
        val session = DiscoverySession(DiscoverySecret("GUEST-ONLY"), "login", 160000L, 60000L)
        init {
            coEvery { access.withSession<Any?>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> Any?>()(session) }
            coEvery { gate.current() } answers { current }
            coEvery { store.readings(identity) } answers { rows }
            coEvery { store.updateReadings(identity, any()) } answers { rows = secondArg<(List<DiscoveryReadingVisit>) -> List<DiscoveryReadingVisit>>()(rows) }
            coEvery { authority.issueAction(identity, any()) } answers {
                val target = secondArg<DiscoveryActionTarget>()
                assertTrue(rows.any { it.visitId == target.operationId || it.finishId == target.operationId })
                DiscoveryCode(DiscoverySecret("ACTION"), "exchange", DiscoverySecret("VERIFIER"), 105000, 105000, 0)
            }
            coEvery { transport.operation(any(), any()) } answers {
                receipts[firstArg<String>()]?.let { receipt -> buildJsonObject {
                    put("status", "COMPLETED"); put("result", receipt)
                }.toString().encodeToByteArray() } ?: """{"status":"PENDING"}""".encodeToByteArray()
            }
            coEvery { transport.post(any(), any(), any(), any()) } answers {
                val value = Json.parseToJsonElement(arg<ByteArray>(2).decodeToString()).jsonObject
                val command = DiscoveryArticleView.parse(value)
                posted.add(command)
                val receipt = JsonObject(value + mapOf("outcome" to JsonPrimitive("APPLIED"), "count" to JsonPrimitive(1),
                    "interaction_id" to JsonPrimitive(7)))
                receipts[command.operationId] = receipt
                if (loseResponse) { loseResponse = false; throw DiscoveryFailure(503) }
                val result = if (forgedReceipt) JsonObject(receipt + ("visit_id" to JsonPrimitive(java.util.UUID.randomUUID().toString()))) else receipt
                result.toString().encodeToByteArray()
            }
        }
        fun coordinator() = DiscoveryReadings(access, gate, authority, transport, clock, store)
    }

    @Test
    fun `lost start response restores same visit then finishes without recount or explicit intent access`() = runTest {
        val f = Fixture()
        val service = f.coordinator()
        val handle = service.begin(7)
        val original = f.rows.single()
        f.loseResponse = true
        assertTrue(runCatching { service.synchronize() }.isFailure)
        assertFalse(f.rows.single().startApplied)
        service.checkpoint(handle, 12500, ended = true)
        service.synchronize()
        assertTrue(f.rows.isEmpty())
        assertEquals(listOf(original.visitId, original.finishId), f.posted.map { it.operationId })
        assertEquals(listOf(DiscoveryViewPhase.START, DiscoveryViewPhase.FINISH), f.posted.map { it.phase })
        assertEquals(13, f.posted.last().staySeconds)
        coVerify(exactly = 0) { f.store.pending(any()) }
        coVerify(exactly = 0) { f.store.stage(any(), any()) }
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test
    fun `process restart ends orphan at durable checkpoint without wall clock invention`() = runTest {
        val f = Fixture()
        val before = f.coordinator()
        val handle = before.begin(7)
        before.synchronize()
        before.checkpoint(handle, 15000)
        assertFalse(f.rows.single().ended)
        f.coordinator().synchronize()
        assertTrue(f.rows.isEmpty())
        assertEquals(15, f.posted.last().staySeconds)
        assertEquals(2, f.posted.size)
    }

    @Test
    fun `storage failure happens before any proof and false receipt retains recovery responsibility`() = runTest {
        val f = Fixture()
        val service = f.coordinator()
        coEvery { f.store.updateReadings(any(), any()) } throws IllegalStateException("test disk full")
        assertTrue(runCatching { service.begin(7) }.isFailure)
        coVerify(exactly = 0) { f.authority.issueAction(any(), any()) }
        val good = Fixture()
        good.coordinator().begin(7)
        good.forgedReceipt = true
        assertTrue(runCatching { good.coordinator().synchronize() }.isFailure)
        assertFalse(good.rows.single().startApplied)
    }

    @Test
    fun `identity change before proof result keeps journal and sends no business request`() = runTest {
        val f = Fixture()
        val service = f.coordinator()
        service.begin(7)
        coEvery { f.authority.issueAction(any(), any()) } answers {
            f.current = null
            DiscoveryCode(DiscoverySecret("ACTION"), "exchange", DiscoverySecret("VERIFIER"), 105000, 105000, 0)
        }
        assertTrue(runCatching { service.synchronize() }.exceptionOrNull() is CancellationException)
        assertEquals(1, f.rows.size)
        coVerify(exactly = 0) { f.transport.post(any(), any(), any(), any()) }
    }

    @Test
    fun `retrying the same visible entry reuses its persisted visit and finish identities`() = runTest {
        val f = Fixture()
        val service = f.coordinator()
        val visit = java.util.UUID.randomUUID().toString()
        val finish = java.util.UUID.randomUUID().toString()
        val first = service.begin(7, visit, finish)
        assertEquals(first, service.begin(7, visit, finish))
        assertEquals(1, f.rows.size)
        assertFalse(f.rows.single().ended)
        assertTrue(runCatching { service.begin(8, visit, finish) }.isFailure)
        assertEquals(7L, f.rows.single().articleId)
        service.synchronize()
        assertFalse(f.rows.single().ended) // Rejected duplicate begin must not release the original live reader.
    }
}
