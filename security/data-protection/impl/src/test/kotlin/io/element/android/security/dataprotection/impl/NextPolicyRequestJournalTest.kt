/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Storage fault/concurrency harness. This does not certify Android AtomicFile or a native DAK. */
class NextPolicyRequestJournalTest {
    @Test
    fun `response loss and journal reconstruction preserve all original request bytes`() {
        val file = MemoryFile()
        NextPolicyRequestPhase.entries.forEach { phase ->
            val bytes = " $phase original nonce and signature\n".encodeToByteArray()
            val journal = NextPolicyRequestJournal(file)
            journal.prepare("op1", HASH, phase, bytes) { assertThat(it).isEqualTo(bytes) }
            assertThat(NextPolicyRequestJournal(file).pending("op1", HASH, phase) {}).isEqualTo(bytes)
            assertThat(NextPolicyRequestJournal(file).prepare("op1", HASH, phase, bytes) {}).isEqualTo(bytes)
        }
        assertThat(file.writes).isEqualTo(3)
    }

    @Test
    fun `concurrent identical submissions create one immutable durable request`() {
        val file = MemoryFile()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..24).map { Callable {
                NextPolicyRequestJournal(file).prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
            } })
            results.forEach { assertThat(it.get()).isEqualTo(BODY) }
            assertThat(file.writes).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `changed retry nonce signature timestamp or authority cannot overwrite pending`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
        val original = file.bytes!!.copyOf()
        reject { journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, "changed".encodeToByteArray()) {} }
        reject { journal.prepare("op1", OTHER_HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        reject { journal.pending("op1", OTHER_HASH, NextPolicyRequestPhase.ARTIFACT) {} }
        assertThat(file.bytes).isEqualTo(original)
    }

    @Test
    fun `failed atomic commit does not expose a first request or damage the last request`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        file.failWrite = true
        assertThrows(IOException::class.java) { journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        assertThat(journal.pending("op1", HASH, NextPolicyRequestPhase.ARTIFACT) {}).isNull()
        file.failWrite = false; journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
        val original = file.bytes!!.copyOf(); file.failWrite = true
        assertThrows(IOException::class.java) { journal.prepare("op1", HASH, NextPolicyRequestPhase.CHALLENGE, BODY) {} }
        assertThat(file.bytes).isEqualTo(original)
    }

    @Test
    fun `revalidation failure and invalid phase never mutate journal`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        reject { journal.prepare("op1", HASH, NextPolicyRequestPhase.CHALLENGE, BODY) {} }
        reject { journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) { error("authority revoked") } }
        assertThat(file.bytes).isNull()
        journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
        reject { journal.pending("op1", HASH, NextPolicyRequestPhase.ARTIFACT) { error("authority expired") } }
        assertThat(file.writes).isEqualTo(1)
    }

    @Test
    fun `validated consumption retains original signed receipt and rejects changed acknowledgement`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        NextPolicyRequestPhase.entries.forEach { journal.prepare("op1", HASH, it, BODY) {} }
        val ack = "CONSUMED with original projection".encodeToByteArray()
        reject { journal.recordConsumed("op1", HASH, ack) { _, _ -> error("bad authenticated ack") } }
        journal.recordConsumed("op1", HASH, ack) { receipt, actual ->
            assertThat(receipt).isEqualTo(BODY); assertThat(actual).isEqualTo(ack)
        }
        journal.recordConsumed("op1", HASH, ack) { _, _ -> }
        assertThat(file.writes).isEqualTo(4)
        assertThat(NextPolicyRequestJournal(file).pending("op1", HASH, NextPolicyRequestPhase.RECEIPT) {}).isEqualTo(BODY)
        reject { journal.recordConsumed("op1", HASH, "APPLIED instead".encodeToByteArray()) { _, _ -> } }
    }

    @Test
    fun `caller buffers and validator mutations cannot change durable original`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        val input = BODY.copyOf()
        val output = journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, input) { it.fill(0) }
        input.fill(1); output.fill(2)
        val read = journal.pending("op1", HASH, NextPolicyRequestPhase.ARTIFACT) { it.fill(3) }!!
        assertThat(read).isEqualTo(BODY); read.fill(4)
        assertThat(journal.pending("op1", HASH, NextPolicyRequestPhase.ARTIFACT) {}).isEqualTo(BODY)
    }

    @Test
    fun `corrupt truncated unknown schema digest and noncanonical records fail closed`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
        val original = file.bytes!!.decodeToString()
        listOf("{}", original.dropLast(1), original + " ", original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            original.replace("\"bodyDigestSha256\":\"", "\"bodyDigestSha256\":\"0"),
            original.replace("\"requests\":", "\"unknown\":1,\"requests\":"),
        ).forEach { changed ->
            file.bytes = changed.encodeToByteArray()
            assertThrows(Exception::class.java) { journal.pending("op1", HASH, NextPolicyRequestPhase.ARTIFACT) {} }
        }
        assertThat(file.writes).isEqualTo(1)
    }

    @Test
    fun `different account policy jobs retain previous pending and capacity fails without eviction`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        repeat(256) { journal.prepare("op$it", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        val original = file.bytes!!.copyOf()
        reject { journal.prepare("op256", OTHER_HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        assertThat(file.bytes).isEqualTo(original)
        assertThat(journal.pending("op0", HASH, NextPolicyRequestPhase.ARTIFACT) {}).isEqualTo(BODY)
    }

    @Test
    fun `invalid identifiers empty oversized bodies and unreadable store never become success`() {
        val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        reject { journal.prepare("../op", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        reject { journal.prepare("op1", "bad", NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        listOf(ByteArray(0), ByteArray(256 * 1024 + 1)).forEach {
            reject { journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, it) {} }
        }
        file.failRead = true
        assertThrows(IOException::class.java) { journal.prepare("op1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {} }
        assertThat(file.writes).isEqualTo(0)
    }

    @Test
    fun `device signer signs canonical payload with original DAK and exact nested envelope`() = runTest {
        val f = SignerFixture()
        val envelope = f.sign()
        assertThat(envelope.keys).containsExactly("contract", "payload", "deviceKeyId", "devicePublicKeySha256",
            "deviceSignatureAlgorithm", "deviceSignatureB64")
        assertThat(envelope["devicePublicKeySha256"]).isEqualTo(JsonPrimitive(f.deviceDigest))
        assertThat(envelope["deviceKeyId"]).isEqualTo(JsonPrimitive(f.deviceDigest.take(16)))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(f.devicePair.public); update(f.verifier.canonicalJson(envelope["payload"]!!))
            verify(Base64.getDecoder().decode((envelope["deviceSignatureB64"] as JsonPrimitive).content))
        }
        assertThat(verified).isTrue()
        coVerify(exactly = 1) { f.trust.signControlStatement(any()) }
    }

    @Test
    fun `key installation attestation or restriction changed during signing rejects envelope`() = runTest {
        val f = SignerFixture()
        listOf(f.snapshot.copy(installationId = "different"), f.snapshot.copy(attestationVersion = 8),
            f.snapshot.copy(attestationPublicKeySha256 = "9".repeat(64)),
            f.snapshot.copy(trustState = DeviceTrustState.REVOKED),
        ).forEach { changed ->
            coEvery { f.trust.current() } returnsMany listOf(f.snapshot, changed)
            assertThat(runCatching { f.sign() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `wrong signature identity empty signature and absent trusted hardware fail closed`() = runTest {
        val f = SignerFixture()
        listOf(DeviceControlRequestSignature("other", 7, byteArrayOf(1)),
            DeviceControlRequestSignature("installation-1", 8, byteArrayOf(1)),
            DeviceControlRequestSignature("installation-1", 7, ByteArray(0)),
        ).forEach { bad ->
            coEvery { f.trust.signControlStatement(any()) } returns bad
            assertThat(runCatching { f.sign() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
        listOf(f.snapshot.copy(trustState = DeviceTrustState.MANAGED),
            f.snapshot.copy(hardwareSecurityLevel = HardwareSecurityLevel.SOFTWARE),
            f.snapshot.copy(hardwareSecurityLevel = HardwareSecurityLevel.UNKNOWN),
        ).forEach { bad ->
            coEvery { f.trust.current() } returns bad
            assertThat(runCatching { f.sign() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `account generation boot artifact or policy changed during signing retains pending`() = runTest {
        val f = SignerFixture()
        val alteredTarget = JsonObject(f.context.targetSnapshot + ("accountMobileGeneration" to JsonPrimitive(2)))
        val variants = listOf(f.context.copy(accountIdHash = "9".repeat(64)), f.context.copy(targetSnapshot = alteredTarget),
            f.context.copy(policyVersion = 8), f.context.copy(policyDigestSha256 = "9".repeat(64)),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("bootId" to JsonPrimitive("new-boot")))),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("appArtifactSha256" to JsonPrimitive("9".repeat(64))))))
        variants.forEach { after ->
            var calls = 0
            assertThat(runCatching { f.sign { if (++calls == 1) f.context else after } }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `signed failed and cleanup pending outcomes keep their exact strongest projection`() = runTest {
        val f = SignerFixture()
        listOf("FAILED", "BLOCKED", "APPLIED_CLEANUP_PENDING").forEach { result ->
            val observations = f.observations().map {
                it.copy(result = result, cleanupOperationIds = if (result == "APPLIED_CLEANUP_PENDING") listOf("cleanup-original") else emptyList())
            }
            val envelope = NextPolicyReceiptSigner(f.trust, f.verifier).sign(f.verify(), f.context, { f.context },
                MONOTONIC + 10, NOW + 10, observations)
            assertThat((envelope["payload"] as JsonObject)["result"]).isEqualTo(JsonPrimitive(result))
        }
    }

    @Test
    fun `lost HTTP response restarts with original device signature without a second signing call`() = runTest {
        val f = SignerFixture(); val file = MemoryFile(); val journal = NextPolicyRequestJournal(file)
        val bytes = f.verifier.canonicalJson(f.sign())
        journal.prepare("policy-operation-1", HASH, NextPolicyRequestPhase.ARTIFACT, BODY) {}
        journal.prepare("policy-operation-1", HASH, NextPolicyRequestPhase.CHALLENGE, BODY) {}
        journal.prepare("policy-operation-1", HASH, NextPolicyRequestPhase.RECEIPT, bytes) {}
        repeat(3) {
            assertThat(NextPolicyRequestJournal(file).pending("policy-operation-1", HASH, NextPolicyRequestPhase.RECEIPT) {})
                .isEqualTo(bytes)
        }
        coVerify(exactly = 1) { f.trust.signControlStatement(any()) }
    }

    private class SignerFixture {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyDigest = digest(pair.public.encoded)
        val verifier = ManagementSignatureVerifier(mockk<ManagementControlConfiguration>().also { configuration ->
            every { configuration.isConfigured } returns true
            every { configuration.signingKeyId } returns keyDigest.take(16)
            every { configuration.signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(pair.public.encoded)
        })
        val devicePair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val deviceDigest = digest(devicePair.public.encoded)
        val trust = mockk<DeviceTrustService>()
        val snapshot = DeviceTrustSnapshot("installation-1", HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            DeviceTrustState.TRUSTED, 7, NOW, attestationPublicKeySha256 = deviceDigest)
        init {
            coEvery { trust.current() } returns snapshot
            coEvery { trust.signControlStatement(any()) } answers {
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(devicePair.private); update(firstArg<ByteArray>()); sign()
                }
                DeviceControlRequestSignature("installation-1", 7, signature)
            }
        }
        val context = NextPolicyChallengeContext(
            operationId = "policy-operation-1", policyId = "policy-1", policyVersion = 7,
            policyDigestSha256 = "a".repeat(64), installationId = "installation-1", accountIdHash = "b".repeat(64),
            targetSnapshot = buildJsonObject {
                put("bootId", "boot-next-1"); put("appVersionCode", 123)
                put("appArtifactSha256", "c".repeat(64)); put("accountMobileGeneration", 1)
            },
            requiredCapabilities = setOf("SCREEN_CAPTURE", "KMS_LEASE"), devicePublicKeySha256 = deviceDigest,
        )
        suspend fun sign(current: suspend () -> NextPolicyChallengeContext = { context }) =
            NextPolicyReceiptSigner(trust, verifier).sign(verify(), context, current, MONOTONIC + 10, NOW + 10, observations())
        fun payload(): JsonObject = buildJsonObject {
            put("operationId", context.operationId); put("policyDomain", "DATA_PROTECTION")
            put("policyId", context.policyId); put("policyVersion", context.policyVersion)
            put("policyDigestSha256", context.policyDigestSha256); put("installationId", context.installationId)
            put("accountIdHash", context.accountIdHash); put("challengeId", "policy-challenge-1")
            put("challengeNonceB64", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
            put("issuedAtMs", NOW); put("expiresAtMs", NOW + 60_000)
            put("requiredCapabilities", JsonArray(context.requiredCapabilities.sorted().map(::JsonPrimitive)))
            put("targetSnapshot", context.targetSnapshot)
        }
        fun envelope(payload: JsonObject = payload()): JsonObject {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private); update(verifier.canonicalJson(payload)); sign()
            }
            return buildJsonObject {
                put("contract", "next.policy-apply-challenge.v1"); put("payload", payload)
                put("serverKeyId", keyDigest.take(16)); put("serverSignatureAlgorithm", "SHA256withECDSA")
                put("serverSignatureB64", Base64.getEncoder().encodeToString(signature))
            }
        }
        fun verify(envelope: JsonObject = envelope(), context: NextPolicyChallengeContext = this.context) =
            VerifiedNextPolicyChallenge.verify(envelope, context, NOW, MONOTONIC, verifier)
        fun observations() = listOf(
            NextCapabilityObservation("SCREEN_CAPTURE", "APPLIED", "e".repeat(64), emptyList()),
            NextCapabilityObservation("KMS_LEASE", "APPLIED", "f".repeat(64), emptyList()),
        )
        fun digest(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun reject(block: () -> Unit) = assertThrows(IllegalStateException::class.java, block)
    private class MemoryFile : NextPolicyJournalFile {
        private val lock = ReentrantLock()
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        var failRead = false
        override fun <T> locked(block: () -> T): T = lock.withLock(block)
        override fun read(): ByteArray? { if (failRead) throw IOException("synthetic storage read failure"); return bytes?.copyOf() }
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("synthetic atomic commit failure")
            this.bytes = bytes.copyOf(); writes++
        }
    }
    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MONOTONIC = 100_000L
        val HASH = "a".repeat(64)
        val OTHER_HASH = "b".repeat(64)
        val BODY = "original raw body".encodeToByteArray()
    }
}
