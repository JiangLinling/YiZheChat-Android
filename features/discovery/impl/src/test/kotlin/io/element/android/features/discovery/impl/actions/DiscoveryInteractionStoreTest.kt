/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import android.content.SharedPreferences
import io.element.android.features.discovery.impl.session.DiscoveryAccountScope
import io.element.android.features.discovery.impl.session.sha256
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.security.securestorage.api.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal class DiscoveryInteractionStoreTest {
    @Test
    fun `confirmed mall result survives retirement and acknowledgement is login bound`() = runTest {
        val f = Fixture()
        val command = DiscoveryMallCommand(DiscoveryMallAction.CANCEL, buildJsonObject {
            put("operation_id", UUID.randomUUID().toString()); put("mall_order_id", 7)
        })
        val result = DiscoveryMallResultLocator(command.operationId, command.action, 7)
        f.store().stage(f.scope, command)
        assertEquals(command, f.store().pending(f.scope))
        f.store().rememberMall(f.scope, command, result)
        f.store().complete(f.scope, command.operationId)
        assertNull(f.store().pending(f.scope))
        assertEquals(result, f.store().mallResult(f.scope))
        assertTrue(runCatching { f.store().dismissMall(f.scope.copy(loginTimestamp = 200), result) }.isFailure)
        f.store().dismissMall(f.scope, result)
        assertTrue(f.index.isEmpty() && f.objects.isEmpty())
    }

    @Test
    fun `confirmed profile locator survives retirement and other commands without retaining form`() = runTest {
        val f = Fixture()
        val target = io.element.android.features.discovery.impl.screens.member.PersonEditTarget(
            io.element.android.features.discovery.impl.screens.member.PersonEditKind.NEW_CHILD, null)
        val command = DiscoveryPersonCommand(UUID.randomUUID().toString(), true, null,
            io.element.android.features.discovery.impl.screens.member.personForm(
                io.element.android.features.discovery.impl.screens.member.MemberPersonDraft(fullName = "Private child"), true))
        val saved = DiscoveryPersonResultLocator(command.operationId, target, 1234567, 7654321)
        f.store().stage(f.scope, command)
        f.store().rememberPerson(f.scope, command, saved)
        assertTrue(runCatching { f.store().dismissPerson(f.scope, saved) }.isFailure)
        f.store().complete(f.scope, command.operationId)
        assertEquals(saved, f.store().personResult(f.scope))
        assertNull(f.store().pending(f.scope))
        assertFalse(f.index.values.any { it.contains("Private child") || it.contains("1234567") })
        assertFalse(f.objects.values.any { it.decodeToString().contains("Private child") })
        assertNull(f.store().personResult(f.scope.copy(loginTimestamp = 200)))
        assertTrue(runCatching { f.store().dismissPerson(f.scope.copy(loginTimestamp = 200), saved) }.isFailure)
        val other = f.command()
        f.store().stage(f.scope, other)
        f.store().complete(f.scope, other.operationId)
        assertEquals(saved, f.store().personResult(f.scope))
        f.store().dismissPerson(f.scope, saved)
        assertTrue(f.index.isEmpty() && f.objects.isEmpty())
    }

    @Test
    fun `profile locator publication failure retains original creation command`() = runTest {
        val f = Fixture()
        val target = io.element.android.features.discovery.impl.screens.member.PersonEditTarget(
            io.element.android.features.discovery.impl.screens.member.PersonEditKind.NEW_CHILD, null)
        val command = DiscoveryPersonCommand(UUID.randomUUID().toString(), true, null,
            io.element.android.features.discovery.impl.screens.member.personForm(
                io.element.android.features.discovery.impl.screens.member.MemberPersonDraft(fullName = "Child"), true))
        f.store().stage(f.scope, command)
        f.failCommit = f.commits + 2
        val saved = DiscoveryPersonResultLocator(command.operationId, target, 2, 3)
        assertTrue(runCatching { f.store().rememberPerson(f.scope, command, saved) }.isFailure)
        assertEquals(command, f.store().pending(f.scope))
        assertNull(f.store().personResult(f.scope))
        f.store().rememberPerson(f.scope, command, saved)
        f.store().complete(f.scope, command.operationId)
        assertEquals(saved, f.store().personResult(f.scope))
    }

    private fun enrollment() = DiscoveryCourseEnrollmentCommand(
        UUID.randomUUID().toString(), 1, 10, UUID.randomUUID().toString(), "a".repeat(64),
    )
    private fun locator(command: DiscoveryCourseEnrollmentCommand) = DiscoveryEnrollmentResultLocator(
        command.operationId, command.courseId, command.sessionId, listOf(938571L, 938572L), 938573L,
    )

    @Test
    fun `result survives original intent retirement and store recreation until explicit dismissal`() = runTest {
        val f = Fixture()
        val command = enrollment()
        val result = locator(command)
        f.store().stage(f.scope, command)
        f.store().rememberEnrollment(f.scope, command, result)
        assertEquals(command, f.store().pending(f.scope))
        assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
        assertTrue(runCatching { f.store().dismissEnrollment(f.scope, 1, 10, 938571) }.isFailure)
        f.store().complete(f.scope, command.operationId)
        assertNull(f.store().pending(f.scope))
        assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
        assertEquals(1, f.objects.size)
        assertFalse(f.index.values.any { it.contains(command.operationId) || it.contains("938571") || it.contains("938573") })
        assertNull(f.store().enrollmentResult(f.scope.copy(loginTimestamp = 200), 1, 10))
        assertTrue(runCatching { f.store().dismissEnrollment(f.scope, 1, 10, 999) }.isFailure)
        f.store().dismissEnrollment(f.scope, 1, 10, 938571)
        assertNull(f.store().enrollmentResult(f.scope, 1, 10))
        assertTrue(f.index.isEmpty() && f.objects.isEmpty())
    }

    @Test
    fun `failed result publication preserves original intent and retries exact result`() = runTest {
        for (writeCrash in listOf(false, true)) {
            val f = Fixture()
            val command = enrollment()
            val result = locator(command)
            f.store().stage(f.scope, command)
            if (writeCrash) f.crashDuringWrite = true else f.failCommit = f.commits + 2
            assertTrue(runCatching { f.store().rememberEnrollment(f.scope, command, result) }.isFailure)
            f.crashDuringWrite = false
            assertEquals(command, f.store().pending(f.scope))
            assertNull(f.store().enrollmentResult(f.scope, 1, 10))
            assertEquals(1, f.objects.size)
            f.store().rememberEnrollment(f.scope, command, result)
            // Recreated process between durable result and retirement still owns the same command.
            assertEquals(command, f.store().pending(f.scope))
            f.store().rememberEnrollment(f.scope, command, result)
            f.store().complete(f.scope, command.operationId)
            assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
        }
    }

    @Test
    fun `failed intent retirement cannot lose its already durable result`() = runTest {
        val f = Fixture()
        val command = enrollment()
        val result = locator(command)
        f.store().stage(f.scope, command)
        f.store().rememberEnrollment(f.scope, command, result)
        f.failCommit = f.commits + 2
        assertTrue(runCatching { f.store().complete(f.scope, command.operationId) }.isFailure)
        assertEquals(command, f.store().pending(f.scope))
        assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
        f.store().complete(f.scope, command.operationId)
        assertNull(f.store().pending(f.scope))
        assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
    }

    @Test
    fun `other operations preserve confirmed result and mismatched result cannot overwrite intent`() = runTest {
        val f = Fixture()
        val command = enrollment()
        val result = locator(command)
        f.store().stage(f.scope, command)
        assertTrue(runCatching { f.store().rememberEnrollment(f.scope, command, result.copy(sessionId = 11)) }.isFailure)
        assertEquals(command, f.store().pending(f.scope))
        f.store().rememberEnrollment(f.scope, command, result)
        f.store().complete(f.scope, command.operationId)
        val article = f.command()
        f.store().stage(f.scope, article)
        f.store().complete(f.scope, article.operationId)
        assertEquals(result, f.store().enrollmentResult(f.scope, 1, 10))
        assertNull(f.store().pending(f.scope))
    }

    private class Fixture {
        val scope = DiscoveryAccountScope(sha256("@a:test"), sha256("PHONE"), "install", 1, 100)
        val matrix = mockk<MatrixClient> {
            every { sessionId } returns SessionId("@a:test")
            every { deviceId } returns DeviceId("PHONE")
        }
        val preferences = mockk<SharedPreferences>()
        val index = mutableMapOf<String, String>()
        val objects = mutableMapOf<String, ByteArray>()
        val files = mockk<SensitiveFileStore>()
        var commits = 0
        var failCommit = -1
        var crashDuringWrite = false
        init {
            every { preferences.getString(any(), any()) } answers { index[firstArg()] ?: secondArg() }
            every { preferences.edit() } answers {
                val edits = mutableMapOf<String, String?>()
                mockk<SharedPreferences.Editor>().also { editor ->
                    every { editor.putString(any(), any()) } answers { edits[firstArg()] = secondArg(); editor }
                    every { editor.remove(any()) } answers { edits[firstArg()] = null; editor }
                    every { editor.commit() } answers {
                        if (++commits == failCommit) false else {
                            edits.forEach { (key, value) -> if (value == null) index.remove(key) else index[key] = value }
                            true
                        }
                    }
                }
            }
            coEvery { files.write(any(), any()) } coAnswers {
                val request = firstArg<SecureObjectWriteRequest>()
                val buffer = ByteArrayOutputStream()
                secondArg<suspend (OutputStream) -> Unit>()(buffer)
                objects[request.objectId.value] = buffer.toByteArray()
                check(!crashDuringWrite) { "Test crash after object creation." }
                SecureObjectReference(request.objectId, request.vaultId, request.purpose, KeyEnvelopeId(UUID.randomUUID().toString().replace("-", "")),
                    1, buffer.size().toLong(), buffer.size() + 100L, sha256(request.associatedData))
            }
            coEvery { files.read<Any>(any(), any()) } coAnswers {
                val reference = firstArg<SecureObjectReference>()
                secondArg<suspend (InputStream) -> Any>()(objects.getValue(reference.objectId.value).inputStream())
            }
            coEvery { files.deleteObject(any()) } answers { objects.remove(firstArg<SecureObjectIdentity>().objectId.value); true }
            coEvery { files.delete(any()) } answers { objects.remove(firstArg<SecureObjectReference>().objectId.value); true }
        }
        fun store() = SecureDiscoveryInteractionStore(matrix, files, preferences)
        fun command() = DiscoveryArticleInteraction(UUID.randomUUID().toString(), 938571L, DiscoveryInteractionKind.LIKE, true)
    }

    @Test
    fun `comment uses the existing encrypted slot and leaves no plaintext in preferences`() = runTest {
        val f = Fixture()
        val command = DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), 938571, "只存在加密对象的正文", 7)
        f.store().stage(f.scope, command)
        assertEquals(command, f.store().pending(f.scope))
        assertFalse(f.index.values.any { it.contains(command.content) || it.contains(command.operationId) || it.contains("938571") })
        assertTrue(runCatching { f.store().stage(f.scope, f.command()) }.isFailure)
        f.store().complete(f.scope, command.operationId)
        assertNull(f.store().pending(f.scope))
        assertTrue(f.objects.isEmpty())
    }

    @Test
    fun `confirmed retirement does not turn object cleanup failure into a failed send`() = runTest {
        val f = Fixture()
        val command = DiscoveryArticleCommentCommand(UUID.randomUUID().toString(), 5, "已发送", null)
        f.store().stage(f.scope, command)
        coEvery { f.files.delete(any()) } returns false
        f.store().complete(f.scope, command.operationId)
        assertTrue(f.index.keys.single().endsWith(".retired"))
        assertTrue(runCatching { f.store().pending(f.scope) }.isFailure)
        coEvery { f.files.delete(any()) } answers { f.objects.remove(firstArg<SecureObjectReference>().objectId.value); true }
        assertNull(f.store().pending(f.scope))
        assertTrue(f.index.isEmpty() && f.objects.isEmpty())
    }

    @Test
    fun `same login restores exact intent through secure storage without business data in preferences`() = runTest {
        val f = Fixture()
        val command = f.command()
        f.store().stage(f.scope, command)
        assertEquals(command, f.store().pending(f.scope))
        assertFalse(f.index.values.any { it.contains(command.operationId) || it.contains("938571") || it.contains("enabled") })
        assertTrue(runCatching { f.store().stage(f.scope, f.command()) }.isFailure)
        f.store().complete(f.scope, command.operationId)
        assertNull(f.store().pending(f.scope))
        assertTrue(f.objects.isEmpty())
        coVerify { f.files.write(match { it.purpose == SecureObjectPurpose.OFFLINE_OUTBOX }, any()) }
    }

    @Test
    fun `crash before reference publish leaves a recoverable object journal and cannot invent an intent`() = runTest {
        for (afterWrite in listOf(false, true)) {
            val f = Fixture()
            if (afterWrite) f.failCommit = 2 else f.crashDuringWrite = true
            assertTrue(runCatching { f.store().stage(f.scope, f.command()) }.isFailure)
            assertEquals(1, f.objects.size)
            assertTrue(f.index.keys.single().endsWith(".pending"))
            assertNull(f.store().pending(f.scope))
            assertTrue(f.objects.isEmpty())
            assertTrue(f.index.isEmpty())
        }
    }

    @Test
    fun `wrong vault or binding and foreign pending object fail closed without reading or deleting host objects`() = runTest {
        val f = Fixture()
        f.store().stage(f.scope, f.command())
        val key = f.index.keys.single()
        val original = f.index.getValue(key)
        for (field in listOf("vault", "binding", "key")) {
            f.index[key] = JsonObject(Json.parseToJsonElement(original).jsonObject + (field to JsonPrimitive("tampered"))).toString()
            assertTrue(runCatching { f.store().pending(f.scope) }.isFailure)
        }
        f.index[key] = original
        f.index["$key.pending"] = UUID.randomUUID().toString()
        assertTrue(runCatching { f.store().pending(f.scope) }.isFailure)
        coVerify(exactly = 0) { f.files.read<Any>(any(), any()) }
        coVerify(exactly = 0) { f.files.deleteObject(any()) }
    }

    @Test
    fun `another account cannot read intent and a new host login does not resume an old login action`() = runTest {
        val f = Fixture()
        f.store().stage(f.scope, f.command())
        assertTrue(runCatching { f.store().pending(f.scope.copy(accountHash = "f".repeat(64))) }.isFailure)
        assertTrue(runCatching { f.store().pending(f.scope.copy(loginTimestamp = 0)) }.isFailure)
        assertNull(f.store().pending(f.scope.copy(loginTimestamp = 200)))
        coVerify(exactly = 0) { f.files.read<Any>(any(), any()) }
    }

    @Test
    fun `reading batch preserves explicit intent and rejects overflow without leaking business data`() = runTest {
        val f = Fixture()
        val command = f.command()
        f.store().stage(f.scope, command)
        val rows = (1..32).map { DiscoveryReadingVisit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 938571L) }
        f.store().updateReadings(f.scope) { rows }
        assertEquals(rows, f.store().readings(f.scope))
        assertEquals(command, f.store().pending(f.scope))
        assertFalse(f.index.values.any { value -> rows.any { value.contains(it.visitId) || value.contains(it.finishId) } || value.contains("938571") })
        assertTrue(runCatching { f.store().updateReadings(f.scope) { it + DiscoveryReadingVisit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1) } }.isFailure)
        assertEquals(rows, f.store().readings(f.scope))
        assertTrue(f.store().readings(f.scope.copy(loginTimestamp = 200)).isEmpty())
        f.store().updateReadings(f.scope) { emptyList() }
        assertEquals(command, f.store().pending(f.scope))
        assertEquals(1, f.objects.size)
    }

    @Test
    fun `reading replacement keeps original on publication failure and recovers new state after cleanup failure`() = runTest {
        val f = Fixture()
        val row = DiscoveryReadingVisit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 9)
        f.store().updateReadings(f.scope) { listOf(row) }
        f.failCommit = f.commits + 2
        assertTrue(runCatching { f.store().updateReadings(f.scope) { listOf(row.copy(milliseconds = 15000)) } }.isFailure)
        assertEquals(listOf(row), f.store().readings(f.scope))
        assertEquals(1, f.objects.size)
        coEvery { f.files.delete(any()) } returns false
        assertTrue(runCatching { f.store().updateReadings(f.scope) { listOf(row.copy(milliseconds = 20000, ended = true)) } }.isFailure)
        assertTrue(f.index.keys.any { it.endsWith(".retired") })
        coEvery { f.files.delete(any()) } answers { f.objects.remove(firstArg<SecureObjectReference>().objectId.value); true }
        assertEquals(listOf(row.copy(milliseconds = 20000, ended = true)), f.store().readings(f.scope))
        assertEquals(1, f.objects.size)
        assertTrue(runCatching { f.store().updateReadings(f.scope) { it.map { item -> item.copy(milliseconds = 21000) } } }.isFailure)
        assertTrue(runCatching { f.store().updateReadings(f.scope) { it.map { item -> item.copy(ended = false) } } }.isFailure)
    }
}
