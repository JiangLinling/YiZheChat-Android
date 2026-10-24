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

internal class DiscoveryInteractionsTest {
    private class Fixture {
        val identity = DiscoveryAccountScope("a".repeat(64), "b".repeat(64), "installation", 1, 100)
        var current: DiscoveryAccountScope? = identity
        var intent: DiscoveryExplicitCommand? = null
        var elapsed = 0L
        val events = mutableListOf<String>()
        val access = mockk<DefaultDiscoveryAccess>()
        val gate = mockk<AndroidDiscoveryIdentityGate>()
        val authority = mockk<DiscoveryCodeClient>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val store = mockk<SecureDiscoveryInteractionStore>()
        val clock = mockk<AndroidDiscoveryClock> {
            every { wallMillis() } answers { 100_000L + elapsed }
            every { elapsedMillis() } answers { elapsed }
        }
        val session = DiscoverySession(DiscoverySecret("GUEST-ONLY"), "login", 160_000L, 60_000L)
        init {
            coEvery { access.withSession<DiscoveryInteractionOutcome>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> DiscoveryInteractionOutcome>()(session) }
            coEvery { gate.current() } answers { current }
            coEvery { store.pending(identity) } answers { intent }
            coEvery { store.stage(identity, any()) } answers { intent = secondArg(); events += "stage" }
            coEvery { store.complete(identity, any()) } answers {
                check(secondArg<String>() == intent?.operationId)
                events += "complete"; intent = null
            }
            coEvery { authority.issueAction(identity, any()) } answers {
                assertNotNull(intent)
                assertEquals(sha256(requireNotNull(intent).bytes()), secondArg<DiscoveryActionTarget>().bodySha256)
                events += "proof"
                DiscoveryCode(DiscoverySecret("ACTION-CODE"), "proof", DiscoverySecret("VERIFIER"),
                    clock.wallMillis() + 5_000, clock.wallMillis() + 5_000, elapsed)
            }
            coEvery { transport.operation(any(), any()) } returns """{"status":"PENDING"}""".encodeToByteArray()
            coEvery { transport.post(any(), any(), any(), any()) } answers {
                events += "post"
                assertEquals(DiscoveryTarget.GATEWAY, firstArg<DiscoveryTarget>())
                assertEquals("/v1/discovery/business/articles/interactions", secondArg<String>())
                assertEquals(requireNotNull(intent), DiscoveryExplicitCommand.parse(Json.parseToJsonElement(arg<ByteArray>(2).decodeToString()).jsonObject))
                assertEquals(mapOf("Authorization" to "Bearer GUEST-ONLY", "X-Discovery-Code" to "ACTION-CODE",
                    "X-Discovery-Verifier" to "VERIFIER", "X-Discovery-Exchange" to "proof"), arg<Map<String, String>>(3))
                receipt()
            }
        }
        fun receipt(): ByteArray {
            val command = requireNotNull(intent)
            val result = if (command is DiscoveryArticleCommentCommand) JsonObject(command.receiptFields() + mapOf(
                "outcome" to JsonPrimitive("APPLIED"), "comment_id" to JsonPrimitive(8), "count" to JsonPrimitive(1)))
            else JsonObject(Json.parseToJsonElement(command.bytes().decodeToString()).jsonObject +
                mapOf("count" to JsonPrimitive(1)) +
                if ((command as? DiscoveryArticleInteraction)?.kind == DiscoveryInteractionKind.FOLLOW) mapOf("outcome" to JsonPrimitive("APPLIED")) else emptyMap())
            return result.toString().encodeToByteArray()
        }
        fun coordinator() = DiscoveryInteractions(access, gate, authority, transport, clock, store)
    }

    @Test
    fun `comment is staged before proof and lost result recovers exact body without reposting`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } throws DiscoveryFailure(503)
        assertTrue(runCatching { f.coordinator().comment(9, "评论正文", 7) }.isFailure)
        val pending = f.intent as DiscoveryArticleCommentCommand
        assertEquals("评论正文", pending.content)
        assertEquals(listOf("stage", "proof"), f.events)
        val receipt = Json.parseToJsonElement(f.receipt().decodeToString())
        coEvery { f.transport.operation(pending.operationId, any()) } returns buildJsonObject {
            put("status", "COMPLETED"); put("result", receipt)
        }.toString().encodeToByteArray()
        assertEquals(DiscoveryInteractionOutcome.APPLIED, f.coordinator().recover())
        assertNull(f.intent)
        coVerify(exactly = 1) { f.transport.post(any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.authority.issueAction(any(), any()) }
    }

    @Test
    fun `comment rejects changed digest or malformed receipt and known rejection retires exact intent`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } answers {
            val result = Json.parseToJsonElement(f.receipt().decodeToString()).jsonObject
            JsonObject(result + ("content_sha256" to JsonPrimitive("0".repeat(64)))).toString().encodeToByteArray()
        }
        assertTrue(runCatching { f.coordinator().comment(9, "保留正文", null) }.isFailure)
        val command = f.intent as DiscoveryArticleCommentCommand
        assertFalse(command.toString().contains(command.content))
        val rejected = JsonObject(command.receiptFields() + mapOf("outcome" to JsonPrimitive("REJECTED"), "reason" to JsonPrimitive("COMMENTS_DISABLED")))
        coEvery { f.transport.operation(command.operationId, any()) } returns buildJsonObject {
            put("status", "COMPLETED"); put("result", rejected)
        }.toString().encodeToByteArray()
        assertEquals(DiscoveryInteractionOutcome.REJECTED, f.coordinator().recover())
        assertNull(f.intent)
    }

    @Test
    fun `author follow uses author identity and a durable rejection clears only its exact pending intent`() = runTest {
        val f = Fixture()
        assertEquals(DiscoveryInteractionOutcome.APPLIED, f.coordinator().interact(7, DiscoveryInteractionKind.FOLLOW, true))
        coEvery { f.transport.post(any(), any(), any(), any()) } throws DiscoveryFailure(503)
        assertTrue(runCatching { f.coordinator().interact(7, DiscoveryInteractionKind.FOLLOW, false) }.isFailure)
        val original = requireNotNull(f.intent)
        val value = Json.parseToJsonElement(original.bytes().decodeToString()).jsonObject
        assertEquals(7L, value.getValue("author_id").jsonPrimitive.long)
        assertFalse(value.containsKey("article_id"))
        val rejected = JsonObject(value + mapOf("outcome" to JsonPrimitive("REJECTED"), "reason" to JsonPrimitive("AUTHOR_FOLLOW_DISABLED")))
        coEvery { f.transport.operation(original.operationId, any()) } returns buildJsonObject {
            put("status", "COMPLETED"); put("result", rejected)
        }.toString().encodeToByteArray()
        assertEquals(DiscoveryInteractionOutcome.REJECTED, f.coordinator().recover())
        assertNull(f.intent)
        coVerify(exactly = 2) { f.authority.issueAction(any(), any()) }
        coVerify(exactly = 2) { f.transport.post(any(), any(), any(), any()) }
    }

    @Test
    fun `follow rejects article target and unrecognized or mismatched rejection receipts`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } answers {
            val request = Json.parseToJsonElement(requireNotNull(f.intent).bytes().decodeToString()).jsonObject
            JsonObject(request + mapOf("outcome" to JsonPrimitive("REJECTED"), "reason" to JsonPrimitive("UNKNOWN"))).toString().encodeToByteArray()
        }
        assertTrue(runCatching { f.coordinator().interact(7, DiscoveryInteractionKind.FOLLOW, true) }.isFailure)
        assertNotNull(f.intent)
        val value = Json.parseToJsonElement(requireNotNull(f.intent).bytes().decodeToString()).jsonObject
        assertTrue(runCatching { DiscoveryArticleInteraction.parse(JsonObject(value - "author_id" + ("article_id" to JsonPrimitive(7)))) }.isFailure)
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test
    fun `article unavailable receipt resolves durable intent without reporting application`() = runTest {
        for (kind in listOf(DiscoveryInteractionKind.LIKE, DiscoveryInteractionKind.FAVORITE)) {
            val f = Fixture()
            coEvery { f.transport.post(any(), any(), any(), any()) } answers {
                val command = Json.parseToJsonElement(requireNotNull(f.intent).bytes().decodeToString()).jsonObject
                JsonObject(command + mapOf("outcome" to JsonPrimitive("REJECTED"), "reason" to JsonPrimitive("ARTICLE_UNAVAILABLE")))
                    .toString().encodeToByteArray()
            }
            assertEquals(DiscoveryInteractionOutcome.REJECTED, f.coordinator().interact(9, kind, true))
            assertNull(f.intent)
            coVerify(exactly = 1) { f.store.complete(any(), any()) }
        }
    }

    @Test
    fun `persist before proof and side effect and storage failure sends nothing`() = runTest {
        val f = Fixture()
        f.coordinator().interact(1, DiscoveryInteractionKind.LIKE, true)
        assertEquals(listOf("stage", "proof", "post", "complete"), f.events)
        assertNull(f.intent)
        f.events.clear()
        coEvery { f.store.stage(any(), any()) } throws IllegalStateException("test disk full")
        assertTrue(runCatching { f.coordinator().interact(1, DiscoveryInteractionKind.LIKE, false) }.isFailure)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `lost response keeps exact operation and completed recovery never repeats side effect`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } throws DiscoveryFailure(503)
        assertTrue(runCatching { f.coordinator().interact(3, DiscoveryInteractionKind.FAVORITE, true) }.isFailure)
        val original = requireNotNull(f.intent)
        val receipt = Json.parseToJsonElement(f.receipt().decodeToString())
        coEvery { f.transport.operation(original.operationId, any()) } returns buildJsonObject {
            put("status", "COMPLETED"); put("result", receipt)
        }.toString().encodeToByteArray()
        f.coordinator().recover()
        assertNull(f.intent)
        coVerify(exactly = 1) { f.authority.issueAction(any(), any()) }
        coVerify(exactly = 1) { f.transport.post(any(), any(), any(), any()) }
        coVerify { f.store.complete(f.identity, original.operationId) }
    }

    @Test
    fun `pending recovery reuses operation and identity change after proof sends nothing`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } throws DiscoveryFailure(503)
        assertTrue(runCatching { f.coordinator().interact(2, DiscoveryInteractionKind.LIKE, true) }.isFailure)
        val original = requireNotNull(f.intent)
        coEvery { f.authority.issueAction(any(), any()) } answers {
            assertEquals(original.operationId, secondArg<DiscoveryActionTarget>().operationId)
            f.current = null
            DiscoveryCode(DiscoverySecret("NEW"), "proof2", DiscoverySecret("v"), 105000, 105000, 0)
        }
        assertTrue(runCatching { f.coordinator().recover() }.exceptionOrNull() is CancellationException)
        assertEquals(original, f.intent)
        coVerify(exactly = 1) { f.transport.post(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test
    fun `mismatched or late receipt is never marked complete`() = runTest {
        for (late in listOf(false, true)) {
            val f = Fixture()
            coEvery { f.transport.post(any(), any(), any(), any()) } answers {
                if (late) { f.elapsed = 5_000; f.receipt() } else """{"count":1}""".encodeToByteArray()
            }
            assertTrue(runCatching { f.coordinator().interact(2, DiscoveryInteractionKind.LIKE, true) }.isFailure)
            assertNotNull(f.intent)
            coVerify(exactly = 0) { f.store.complete(any(), any()) }
        }
    }
}
