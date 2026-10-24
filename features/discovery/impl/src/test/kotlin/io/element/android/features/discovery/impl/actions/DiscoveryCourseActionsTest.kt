/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.network.*
import io.element.android.features.discovery.impl.session.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryCourseActionsTest {
    @Test fun `session expiry after retirement leaves confirmed locator recoverable without resending`() = runTest {
        val f = Fixture()
        val command = command()
        val locator = DiscoveryEnrollmentResultLocator(command.operationId, 1, 2, listOf(3), 4)
        var saved: DiscoveryEnrollmentResultLocator? = null
        coEvery { f.store.rememberEnrollment(f.identity, command, locator) } answers {
            assertEquals(command, f.pending)
            saved = thirdArg()
            f.events += "remember"
        }
        coEvery { f.access.withSession<String?>(any()) } coAnswers {
            firstArg<suspend (DiscoverySession) -> String?>()(f.session)
            throw DiscoveryFailure(401)
        }
        assertTrue(runCatching {
            f.actions().execute<String>(command, confirmedEnrollment = { locator }) { _, _ -> "confirmed" }
        }.isFailure)
        assertEquals(listOf("stage", "proof", "post", "remember", "complete"), f.events)
        assertNull(f.pending)
        assertEquals(locator, saved)
        coEvery { f.access.withSession<DiscoveryEnrollmentResultLocator?>(any()) } coAnswers {
            firstArg<suspend (DiscoverySession) -> DiscoveryEnrollmentResultLocator?>()(f.session)
        }
        coEvery { f.store.enrollmentResult(f.identity, 1, 2) } answers { saved }
        assertEquals(locator, f.actions().enrollmentResult(1, 2))
        coVerify(exactly = 1) { f.transport.post(any(), any(), any(), any()) }
    }

    @Test fun `result storage failure retains original command instead of retiring it`() = runTest {
        val f = Fixture()
        val command = command()
        val locator = DiscoveryEnrollmentResultLocator(command.operationId, 1, 2, listOf(3), 4)
        coEvery { f.store.rememberEnrollment(f.identity, command, locator) } throws IllegalStateException("storage unavailable")
        assertTrue(runCatching {
            f.actions().execute<String>(command, confirmedEnrollment = { locator }) { _, _ -> "confirmed" }
        }.isFailure)
        assertEquals(command, f.pending)
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test fun `page type gate rejects another business intent before querying or replaying it`() = runTest {
        val f = Fixture()
        f.pending = command()
        assertTrue(runCatching {
            f.actions().execute<String>(null, accepts = { it is DiscoveryAddressCommand }) { _, _ -> "must not consume" }
        }.isFailure)
        coVerify(exactly = 0) { f.transport.operation(any(), any()) }
        coVerify(exactly = 0) { f.transport.post(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
        assertEquals(command(), f.pending)
    }
    private fun command() = DiscoveryCourseEnrollmentCommand("11111111-1111-4111-8111-111111111111", 1, 2, "22222222-2222-4222-8222-222222222222", "a".repeat(64))

    private class Fixture {
        val identity = DiscoveryAccountScope("a".repeat(64), "b".repeat(64), "installation", 1, 100)
        val access = mockk<DefaultDiscoveryAccess>()
        val gate = mockk<AndroidDiscoveryIdentityGate>()
        val authority = mockk<DiscoveryCodeClient>()
        val transport = mockk<PinnedDiscoveryTransport>()
        val clock = mockk<AndroidDiscoveryClock> {
            every { wallMillis() } returns 100_000L
            every { elapsedMillis() } returns 0L
        }
        val store = mockk<SecureDiscoveryInteractionStore>()
        var pending: DiscoveryExplicitCommand? = null
        val events = mutableListOf<String>()
        val session = DiscoverySession(DiscoverySecret("guest"), "login", 160_000L, 60_000L)
        var sentBody: ByteArray? = null
        var response: ByteArray? = null
        init {
            coEvery { access.withSession<String?>(any()) } coAnswers { firstArg<suspend (DiscoverySession) -> String?>()(session) }
            coEvery { gate.current() } returns identity
            coEvery { store.pending(identity) } answers { pending }
            coEvery { store.stage(identity, any()) } answers { pending = secondArg(); events += "stage" }
            coEvery { store.complete(identity, any()) } answers {
                assertEquals(pending?.operationId, secondArg<String>()); pending = null; events += "complete"
            }
            coEvery { authority.issueAction(identity, any()) } answers {
                val command = pending as DiscoveryCourseCommand
                val target = secondArg<DiscoveryActionTarget>()
                assertEquals(command.path, target.path)
                assertEquals(sha256(command.bytes()), target.bodySha256)
                events += "proof"
                DiscoveryCode(DiscoverySecret("proof"), "exchange", DiscoverySecret("verifier"), 105_000, 105_000, 0)
            }
            coEvery { transport.operation(any(), any()) } returns """{"status":"PENDING"}""".encodeToByteArray()
            coEvery { transport.post(any(), any(), any(), any()) } answers {
                assertEquals((pending as DiscoveryCourseCommand).path, secondArg<String>())
                assertEquals("Bearer guest", arg<Map<String, String>>(3)["Authorization"])
                sentBody = arg(2); events += "post"
                receipt().toString().encodeToByteArray().also { response = it }
            }
        }
        fun receipt(): JsonObject {
            val command = pending as DiscoveryCourseEnrollmentCommand
            return buildJsonObject {
                put("operation_id", command.operationId); put("course_id", command.courseId); put("session_id", command.sessionId)
                put("quote_id", command.quoteId); put("selection_sha256", command.selectionSha256); put("outcome", "APPLIED")
                put("enrollment_ids", buildJsonArray { add(3) }); put("order_id", 4)
            }
        }
        fun actions() = DiscoveryBusinessActions(access, gate, authority, transport, clock, store)
    }

    @Test fun `enrollment uses original journal before exact path proof and zeroes payloads`() = runTest {
        val f = Fixture()
        val value = f.actions().execute(command()) { _, _ -> f.events += "consume"; "current enrollment queried" }
        assertEquals("current enrollment queried", value)
        assertEquals(listOf("stage", "proof", "post", "consume", "complete"), f.events)
        assertTrue(f.sentBody!!.all { it == 0.toByte() })
        assertTrue(f.response!!.all { it == 0.toByte() })
    }

    @Test fun `failed strict receipt parsing retains original intent and recovery never reposts completed enrollment`() = runTest {
        val f = Fixture()
        assertTrue(runCatching { f.actions().execute<String>(command()) { _, _ -> error("invalid nested receipt") } }.isFailure)
        assertEquals(command(), f.pending)
        val recovered = buildJsonObject { put("status", "COMPLETED"); put("result", f.receipt()) }.toString().encodeToByteArray()
        coEvery { f.transport.operation(command().operationId, any()) } returns recovered
        assertEquals("confirmed", f.actions().execute<String>(null) { original, _ -> assertEquals(command(), original); "confirmed" })
        coVerify(exactly = 1) { f.transport.post(any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.authority.issueAction(any(), any()) }
        assertNull(f.pending)
        assertTrue(recovered.all { it == 0.toByte() })
    }

    @Test fun `different session receipt does not retire pending enrollment`() = runTest {
        val f = Fixture()
        coEvery { f.transport.post(any(), any(), any(), any()) } answers {
            JsonObject(f.receipt() + ("session_id" to JsonPrimitive(999))).toString().encodeToByteArray()
        }
        assertTrue(runCatching { f.actions().execute<String>(command()) { _, _ -> fail("must reject mismatched session"); "" } }.isFailure)
        assertEquals(command(), f.pending)
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test fun `article intent cannot be consumed as a course operation`() = runTest {
        val f = Fixture()
        f.pending = DiscoveryArticleInteraction(command().operationId, 7, DiscoveryInteractionKind.FAVORITE, true)
        assertTrue(runCatching { f.actions().execute<String>(null) { _, _ -> "wrong owner" } }.isFailure)
        coVerify(exactly = 0) { f.authority.issueAction(any(), any()) }
        coVerify(exactly = 0) { f.store.complete(any(), any()) }
    }

    @Test fun `journal round trip preserves course command and old article commands`() {
        val course = command()
        assertEquals(course, DiscoveryExplicitCommand.parse(Json.parseToJsonElement(course.bytes().decodeToString()).jsonObject))
        val article = DiscoveryArticleCommentCommand(course.operationId, 1, "comment", null)
        assertEquals(article, DiscoveryExplicitCommand.parse(Json.parseToJsonElement(article.bytes().decodeToString()).jsonObject))
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryActionTarget(course.operationId, "a".repeat(64), "/mini/academy/mock-pay")
        }
    }
}
