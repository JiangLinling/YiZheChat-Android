/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Original-byte fault harness; callbacks do not claim real server/native authority or effects. */
class NextPolicyLifecycleTransportTest {
    @Test
    fun `lost lifecycle start response and reconstruction replay identical persisted creation and request`() = runTest {
        val f = Fixture()
        f.loseStart = true
        fails { f.send() }
        val created = f.starts.single().copyOf()
        val durable = f.requests.bytes!!.copyOf()
        f.loseStart = false
        f.engine = f.createEngine()
        f.send()
        assertThat(f.starts.last()).isEqualTo(created)
        assertThat(f.requests.bytes).isEqualTo(durable)
        assertThat(f.posts.single().body).isEqualTo(BODY.toList())
        assertThat(f.lifecycle.writes).isEqualTo(1)
        assertThat(f.requests.writes).isEqualTo(1)
    }

    @Test
    fun `three phases use separate LC01 and LC09 identities without ambient parent`() = runTest {
        val f = Fixture()
        f.send()
        f.send(NextPolicyRequestPhase.CHALLENGE)
        f.send(NextPolicyRequestPhase.RECEIPT, RECEIPT)
        val starts = f.starts.map { Json.decodeFromString<LifecycleOperationStartWire>(it.decodeToString()) }
        assertThat(starts.map { it.lifecycleId }).containsExactly("LC-01", "LC-09", "LC-09").inOrder()
        assertThat(starts.map { it.trigger }).containsExactly("periodic_release_check", "policy_changed", "policy_changed").inOrder()
        assertThat(starts[0].operationId).isNotEqualTo(starts[1].operationId)
        assertThat(f.posts.map { it.operationId }).containsExactly(starts[0].operationId, starts[1].operationId, starts[1].operationId).inOrder()
        assertThat(f.posts.map { it.path }).containsExactly(
            "/v2/app/artifact-acceptances",
            "/v2/policy-application-challenges",
            "/v2/policy-application-challenges/challenge-1/receipts"
        ).inOrder()
    }

    @Test
    fun `lost modern response cannot regenerate request or extend original deadline`() = runTest {
        val f = Fixture()
        f.losePost = true
        fails { f.send() }
        val first = f.posts.single()
        f.losePost = false
        f.engine = f.createEngine()
        f.send()
        assertThat(f.posts.last()).isEqualTo(first)
        val original = f.lifecycle.bytes!!.copyOf()
        fails { f.send(scope = SCOPE.copy(deadlineEpochMillis = 20000)) }
        assertThat(f.lifecycle.bytes).isEqualTo(original)
        assertThat(f.posts).hasSize(2)
    }

    @Test
    fun `changed account device installation or authority cannot overwrite a pending intent`() = runTest {
        val f = Fixture()
        f.send()
        val original = f.lifecycle.bytes!!.copyOf()
        listOf(
            SCOPE.copy(accountIdHash = "d".repeat(64)),
            SCOPE.copy(matrixDeviceIdHash = "e".repeat(64)),
            SCOPE.copy(installationId = "other-installation-1"),
            SCOPE.copy(authorityDigestSha256 = "f".repeat(64))
        )
            .forEach { scope -> fails { f.send(scope = scope) } }
        assertThat(f.lifecycle.bytes).isEqualTo(original)
        assertThat(f.posts).hasSize(1)
    }

    @Test
    fun `mismatched server operation identity lifecycle scope and deadline never send modern request`() = runTest {
        val changes = listOf<(LifecycleOperation) -> LifecycleOperation>(
            { it.copy(operationId = "wrong-operation-id-1") },
            { it.copy(idempotencyKey = "wrong-idempotency-1") },
            { it.copy(idempotencyKey = it.operationId) },
            { it.copy(idempotencyKey = "d".repeat(64)) },
            { it.copy(lifecycleId = FullAppLifecycleId.LC_09) },
            { it.copy(deadlineEpochMillis = 20000) },
            { it.copy(subjects = it.subjects.copy(accountIdHash = "e".repeat(64))) },
            { it.copy(subjects = it.subjects.copy(matrixDeviceIdHash = "f".repeat(64))) },
            { it.copy(subjects = it.subjects.copy(installationId = "wrong-installation-1")) },
            { it.copy(subjects = it.subjects.copy(roomIdHash = "d".repeat(64))) },
        )
        changes.forEach { change ->
            val f = Fixture()
            f.changeOperation = change
            fails { f.send() }
            assertThat(f.posts).isEmpty()
        }
    }

    @Test
    fun `blocked cancelled failed and terminated operation cannot become transport success`() = runTest {
        listOf(
            LifecycleOperationStatus.BLOCKED,
            LifecycleOperationStatus.CANCELLED,
            LifecycleOperationStatus.FAILED_FINAL,
            LifecycleOperationStatus.TERMINATED
        ).forEach { status ->
            val f = Fixture()
            f.changeOperation = { it.copy(status = status, terminalReceiptId = if (status.isTerminal) "original-terminal" else null) }
            fails { f.send() }
            assertThat(f.posts).isEmpty()
        }
    }

    @Test
    fun `authority failure at every boundary preserves pending evidence and never returns success`() = runTest {
        (1..4).forEach { boundary ->
            val f = Fixture()
            f.rejectAuthorityAt = boundary
            fails { f.send() }
            assertThat(f.posts.size).isEqualTo(if (boundary == 4) 1 else 0)
            if (boundary > 1) assertThat(f.requests.bytes).isNotNull()
        }
    }

    @Test
    fun `failed lifecycle or request journal commit prevents first network attempt`() = runTest {
        listOf(true, false).forEach { lifecycleFails ->
            val f = Fixture()
            if (lifecycleFails) f.lifecycle.failWrite = true else f.requests.failWrite = true
            fails { f.send() }
            assertThat(f.starts).isEmpty()
            assertThat(f.posts).isEmpty()
        }
    }

    @Test
    fun `revalidation of original bytes and changed retry body reject without new network`() = runTest {
        val f = Fixture()
        f.send()
        val previous = f.requests.bytes!!.copyOf()
        fails { f.send(body = "changed nonce signature timestamp".encodeToByteArray()) }
        f.rejectBody = true
        fails { f.send() }
        assertThat(f.requests.bytes).isEqualTo(previous)
        assertThat(f.posts).hasSize(1)
    }

    @Test
    fun `consumed journal receipt is still reconciled by network after reconstruction`() = runTest {
        val f = Fixture()
        f.send()
        f.send(NextPolicyRequestPhase.CHALLENGE)
        f.send(NextPolicyRequestPhase.RECEIPT, RECEIPT)
        NextPolicyRequestJournal(f.requests).recordConsumed(SCOPE.jobId, SCOPE.authorityDigestSha256, "ACK".encodeToByteArray()) { _, _ -> }
        f.engine = f.createEngine()
        f.send(NextPolicyRequestPhase.RECEIPT, RECEIPT)
        assertThat(f.posts).hasSize(4)
        assertThat(f.posts.last().body).isEqualTo(RECEIPT.toList())
        assertThat(f.requests.writes).isEqualTo(4)
    }

    @Test
    fun `invalid challenge path and out of order phases cannot send or replace durable body`() = runTest {
        val f = Fixture()
        fails { f.send(NextPolicyRequestPhase.CHALLENGE) }
        assertThat(f.starts).isEmpty()
        fails { f.send(NextPolicyRequestPhase.RECEIPT, "{\"payload\":{\"challengeId\":\"../escape\"}}".encodeToByteArray()) }
        assertThat(f.starts).isEmpty()
        assertThat(f.requests.bytes).isNull()
    }

    @Test
    fun `concurrent identical requests serialize one durable intent and original body`() = runTest {
        val f = Fixture()
        (1..12).map { async { f.send() } }.awaitAll()
        assertThat(f.lifecycle.writes).isEqualTo(1)
        assertThat(f.requests.writes).isEqualTo(1)
        assertThat(f.posts.map { it.operationId }.distinct()).hasSize(1)
        f.posts.forEach { assertThat(it.body).isEqualTo(BODY.toList()) }
    }

    @Test
    fun `corrupt unknown schema and noncanonical lifecycle journal never become success`() = runTest {
        val f = Fixture()
        f.send()
        val original = f.lifecycle.bytes!!.decodeToString()
        listOf(
            "{}",
            original.dropLast(1),
            original + " ",
            original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            original.replace("\"artifactStartBodyB64\":\"", "\"artifactStartBodyB64\":\"A")
        )
            .forEach { changed ->
                f.lifecycle.bytes = changed.encodeToByteArray()
                fails { f.send() }
            }
        assertThat(f.posts).hasSize(1)
    }

    @Test
    fun `caller and transport callbacks cannot mutate persisted original request bytes`() = runTest {
        val f = Fixture()
        f.mutateCallbacks = true
        val supplied = BODY.copyOf()
        f.send(body = supplied)
        supplied.fill(0)
        assertThat(NextPolicyRequestJournal(f.requests).pending(SCOPE.jobId, SCOPE.authorityDigestSha256, NextPolicyRequestPhase.ARTIFACT) {}).isEqualTo(BODY)
        f.mutateCallbacks = false
        f.send()
        assertThat(f.posts.last().body).isEqualTo(BODY.toList())
    }

    private class Fixture {
        val lifecycle = MemoryFile()
        val requests = MemoryFile()
        val starts = mutableListOf<ByteArray>()
        val posts = mutableListOf<Post>()
        var loseStart = false
        var losePost = false
        var rejectBody = false
        var mutateCallbacks = false
        var rejectAuthorityAt = 0
        var authorityChecks = 0
        var changeOperation: (LifecycleOperation) -> LifecycleOperation = { it }
        var engine = createEngine()
        fun createEngine() = NextPolicyLifecycleTransport(NextPolicyLifecycleJournal(lifecycle), NextPolicyRequestJournal(requests), { bytes ->
            check(lifecycle.bytes != null && requests.bytes != null)
            starts += bytes.copyOf()
            if (loseStart) throw IOException("lost start response")
            val w = Json.decodeFromString<LifecycleOperationStartWire>(bytes.decodeToString())
            changeOperation(LifecycleOperation(
                operationId = w.operationId,
                lifecycleId = FullAppLifecycleId.fromWireValue(w.lifecycleId),
                idempotencyKey = java.security.MessageDigest.getInstance("SHA-256").digest(w.idempotencyKey.encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                subjects = LifecycleSubjectHashes(w.accountIdHash, SCOPE.installationId, w.matrixDeviceIdHash),
                status = LifecycleOperationStatus.RUNNING,
                currentPhase = LifecyclePhase.INTENT_PERSISTED,
                deadlineEpochMillis = w.deadlineEpochMillis,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1
            ))
        }, { path, bytes, id ->
            posts += Post(path, bytes.toList(), id)
            if (mutateCallbacks) bytes.fill(3)
            if (losePost) throw IOException("lost modern response")
            "authenticated response requires separate validation".encodeToByteArray()
        })
        suspend fun send(
            phase: NextPolicyRequestPhase = NextPolicyRequestPhase.ARTIFACT,
            body: ByteArray = BODY,
            scope: NextPolicyLifecycleScope = SCOPE,
        ): ByteArray =
            engine.send(scope, phase, body, {
                authorityChecks++
                check(authorityChecks != rejectAuthorityAt)
            }, { bytes ->
                check(!rejectBody)
                if (mutateCallbacks) bytes.fill(2)
            })
    }
    private data class Post(val path: String, val body: List<Byte>, val operationId: String)
    private class MemoryFile : NextPolicyJournalFile {
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        private val lock = ReentrantLock()
        override fun <T> locked(block: () -> T): T = lock.withLock(block)
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("atomic commit failed")
            this.bytes = bytes.copyOf()
            writes++
        }
    }
    private suspend fun fails(block: suspend () -> Unit) {
        check(runCatching { block() }.exceptionOrNull() != null)
    }
    private companion object {
        val SCOPE = NextPolicyLifecycleScope("job-1", "a".repeat(64), "installation-00001", "b".repeat(64), "c".repeat(64), 10000)
        val BODY = "original phase bytes with stable nonce signature and timestamp".encodeToByteArray()
        val RECEIPT = "{\"payload\":{\"challengeId\":\"challenge-1\"}}".encodeToByteArray()
    }
}
