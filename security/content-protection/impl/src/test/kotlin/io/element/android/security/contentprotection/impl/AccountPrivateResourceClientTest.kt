/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.*
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.*
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import io.element.android.security.securestorage.test.FakeVaultKeyService
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AccountPrivateResourceClientTest {
    @Before fun clock() { mockkStatic(SystemClock::class); every { SystemClock.elapsedRealtime() } returns 1_000L }
    @After fun releaseClock() { unmockkStatic(SystemClock::class) }

    @Test fun `visible resource renews a near expiry grant without changing the security generation`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("release", any()) } returns fixture.grant
            fixture.client.read(fixture.scope, fixture.record).fill(0)
            val generation = fixture.client.authorizationRevision.value
            every { SystemClock.elapsedRealtime() } returns 56_000L
            assertThat(fixture.client.readAuthorizationRemainingMillis(fixture.scope, fixture.record)).isEqualTo(5_000)
            assertThat(fixture.client.renewReadAuthorization(fixture.scope, fixture.record)).isEqualTo(60_000)
            assertThat(fixture.client.authorizationRevision.value).isEqualTo(generation)
            fixture.client.read(fixture.scope, fixture.record).fill(0)
            coVerify(exactly = 2) { fixture.kms.requestPrivateResource("release", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `renewal after revocation cannot keep a displayed lease alive`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("release", any()) } returns fixture.grant
            fixture.client.read(fixture.scope, fixture.record).fill(0)
            val generation = fixture.client.authorizationRevision.value
            fixture.runtime.clearSensitiveCaches()
            assertThat(fixture.client.authorizationRevision.value).isGreaterThan(generation)
            assertThat(fixture.client.readAuthorizationRemainingMillis(fixture.scope, fixture.record)).isEqualTo(0)
            every { fixture.policy.expiresAtEpochMillis } returns 1L
            assertThat(runCatching { fixture.client.renewReadAuthorization(fixture.scope, fixture.record) }.isFailure).isTrue()
            coVerify(exactly = 1) { fixture.kms.requestPrivateResource("release", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `concurrent reads coalesce one release and cached keys never enter the file store`() = runTest {
        val fixture = fixture()
        try {
            val ready = CompletableDeferred<Unit>()
            coEvery { fixture.kms.requestPrivateResource("release", any()) } coAnswers { ready.await(); fixture.grant }
            val first = async { fixture.client.read(fixture.scope, fixture.record) }
            val second = async { fixture.client.read(fixture.scope, fixture.record) }
            runCurrent()
            ready.complete(Unit)
            assertThat(first.await().decodeToString()).isEqualTo("private body")
            assertThat(second.await().decodeToString()).isEqualTo("private body")
            coVerify(exactly = 1) { fixture.kms.requestPrivateResource("release", any()) }
            assertThat(fixture.persisted).isEmpty()
        } finally { fixture.client.invalidate() }
    }

    @Test fun `a late grant after global lock cannot publish plaintext or repopulate cache`() = runTest {
        val fixture = fixture()
        try {
            val ready = CompletableDeferred<Unit>()
            coEvery { fixture.kms.requestPrivateResource("release", any()) } coAnswers { ready.await(); fixture.grant }
            val result = async { runCatching { fixture.client.read(fixture.scope, fixture.record) } }
            runCurrent()
            fixture.runtime.clearSensitiveCaches()
            ready.complete(Unit)
            assertThat(result.await().isFailure).isTrue()
            fixture.client.read(fixture.scope, fixture.record).fill(0)
            coVerify(exactly = 2) { fixture.kms.requestPrivateResource("release", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `observing deletion while a release is delayed rejects the late grant`() = runTest {
        val fixture = fixture()
        try {
            val ready = CompletableDeferred<Unit>()
            coEvery { fixture.kms.requestPrivateResource("release", any()) } coAnswers { ready.await(); fixture.grant }
            val result = async { runCatching { fixture.client.read(fixture.scope, fixture.record) } }
            runCurrent()
            coEvery { fixture.kms.requestPrivateResource("fetch", any()) } returns
                """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":2,"version_id":"version-test-0001","deleted":true}"""
            assertThat(fixture.client.fetch(fixture.scope, fixture.record.resourceId).deleted).isTrue()
            ready.complete(Unit)
            assertThat(result.await().isFailure).isTrue()
            assertThat(runCatching { fixture.client.read(fixture.scope, fixture.record) }.isFailure).isTrue()
            coVerify(exactly = 1) { fixture.kms.requestPrivateResource("release", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `policy replacement during a release invalidates the result`() = runTest {
        val fixture = fixture()
        try {
            val ready = CompletableDeferred<Unit>()
            coEvery { fixture.kms.requestPrivateResource("release", any()) } coAnswers { ready.await(); fixture.grant }
            val result = async { runCatching { fixture.client.read(fixture.scope, fixture.record) } }
            runCurrent()
            every { fixture.policy.version } returns 2L
            ready.complete(Unit)
            assertThat(result.await().isFailure).isTrue()
        } finally { fixture.client.invalidate() }
    }

    @Test fun `expired uncommitted preparation is retired only after a signed current head`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("prepare", any()) } returns fixture.grant
            coEvery { fixture.kms.requestPrivateResource("put", any()) } throws ContentProtectionException.Expired()
            assertThat(runCatching { fixture.client.write(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 0, byteArrayOf(1)) }.isFailure).isTrue()
            coEvery { fixture.kms.requestPrivateResource("fetch", any()) } returns
                """{"resource_id":"resource-test-0001","revision":0,"deleted":false}"""
            assertThat(fixture.client.resumePending(fixture.scope, fixture.record.resourceId)?.revision).isEqualTo(0)
            assertThat(fixture.client.resumePending(fixture.scope, fixture.record.resourceId)).isNull()
            coVerify(exactly = 1) { fixture.kms.requestPrivateResource("fetch", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `expired response cannot erase an intent while its version remains current`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("prepare", any()) } returns fixture.grant
            coEvery { fixture.kms.requestPrivateResource("put", any()) } throws ContentProtectionException.Expired()
            assertThat(runCatching { fixture.client.write(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 0, byteArrayOf(1)) }.isFailure).isTrue()
            coEvery { fixture.kms.requestPrivateResource("fetch", any()) } returns
                """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":1,"version_id":"version-test-0001","deleted":false}"""
            repeat(2) {
                assertThat(runCatching { fixture.client.resumePending(fixture.scope, fixture.record.resourceId) }.isFailure).isTrue()
            }
            coVerify(exactly = 2) { fixture.kms.requestPrivateResource("fetch", any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `a conflicting intent is retired only when the signed head has advanced`() = runTest {
        for (advanced in listOf(false, true)) {
            val fixture = fixture()
            try {
                coEvery { fixture.kms.requestPrivateResource("prepare", any()) } returns fixture.grant
                coEvery { fixture.kms.requestPrivateResource("put", any()) } throws IllegalStateException("PRIVATE_RESOURCE_CONFLICT")
                val saved = runCatching { fixture.client.write(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 0, byteArrayOf(1)) }
                assertThat(saved.isFailure).isTrue()
                coEvery { fixture.kms.requestPrivateResource("fetch", any()) } returns if (advanced)
                    """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":2,"version_id":"version-test-0002","deleted":false}"""
                else """{"resource_id":"resource-test-0001","revision":0,"deleted":false}"""
                val resumed = runCatching { fixture.client.resumePending(fixture.scope, fixture.record.resourceId) }
                assertThat(resumed.isSuccess).isEqualTo(advanced)
                if (advanced) assertThat(fixture.client.resumePending(fixture.scope, fixture.record.resourceId)).isNull()
                else assertThat(runCatching { fixture.client.resumePending(fixture.scope, fixture.record.resourceId) }.isFailure).isTrue()
            } finally { fixture.client.invalidate() }
        }
    }

    @Test fun `one displayed read completes one operation after fetch and key release`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("release", any()) } returns fixture.grant
            coEvery { fixture.kms.requestPrivateResource("fetch", any()) } returns
                """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":1,"version_id":"version-test-0001","deleted":false}"""
            fixture.client.withReadOperation(fixture.scope) {
                fixture.client.fetch(fixture.scope, fixture.record.resourceId)
                coVerify(exactly = 0) { fixture.coordinator.recordClientReceipt(any()) }
                fixture.client.read(fixture.scope, fixture.record).fill(0)
                coVerify(exactly = 0) { fixture.coordinator.recordClientReceipt(any()) }
            }
            coVerify(exactly = 1) { fixture.coordinator.start(any()) }
            coVerify(exactly = 2) { fixture.coordinator.recordClientReceipt(any()) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `ciphertext replay retains the semantic trigger and original operation`() = runTest {
        val fixture = fixture()
        try {
            val op = "private-pin-original-operation"
            coEvery { fixture.kms.requestPrivateResource("prepare", any()) } returns fixture.grant
            coEvery { fixture.kms.requestPrivateResource("put", any()) } throws ContentProtectionException.KmsUnavailable()
            assertThat(runCatching {
                fixture.client.writeWithLifecycleOperation(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 1,
                    "synthetic updated body".encodeToByteArray(), op, "private_pin")
            }.isFailure).isTrue()
            assertThat(fixture.persisted.map { it.decodeToString() }.any { "\"__trigger\":\"private_pin\"" in it }).isTrue()
            coEvery { fixture.kms.requestPrivateResource("put", any()) } returns
                """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":2,"version_id":"version-test-0001","deleted":false}"""
            assertThat(fixture.client.resumePending(fixture.scope, fixture.record.resourceId)?.revision).isEqualTo(2)
            assertThat(fixture.client.resumePending(fixture.scope, fixture.record.resourceId)).isNull()
            coVerify(exactly = 2) { fixture.coordinator.start(match { it.trigger == "private_pin" && it.operationId == op }) }
            coVerify(exactly = 0) { fixture.coordinator.start(match { it.trigger == "private_edit" }) }
            coVerify(exactly = 2) { fixture.kms.requestPrivateResource("put", match {
                val body = Json.parseToJsonElement(it).jsonObject
                "__trigger" !in body && body["operation_id"]?.jsonPrimitive?.content == op
            }) }
        } finally { fixture.client.invalidate() }
    }

    @Test fun `a new body uses create and rejects unknown mutation triggers before prepare`() = runTest {
        val fixture = fixture()
        try {
            coEvery { fixture.kms.requestPrivateResource("prepare", any()) } returns fixture.grant
            coEvery { fixture.kms.requestPrivateResource("put", any()) } returns
                """{"resource_id":"resource-test-0001","resource_kind":"COMMON_BODY","revision":1,"version_id":"version-test-0001","deleted":false}"""
            fixture.client.write(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 0, byteArrayOf(1))
            coVerify(exactly = 1) { fixture.coordinator.start(match { it.trigger == "private_create" }) }
            assertThat(runCatching {
                fixture.client.writeWithLifecycleOperation(fixture.scope, fixture.record.resourceId, "COMMON_BODY", 1,
                    byteArrayOf(1), "unknown-trigger-operation", "private_delete")
            }.isFailure).isTrue()
            coVerify(exactly = 1) { fixture.kms.requestPrivateResource("prepare", any()) }
        } finally { fixture.client.invalidate() }
    }

    private data class Fixture(val client: DefaultAccountPrivateResourceClient, val kms: KmsShareClient,
        val runtime: DefaultMatrixContentProtectionRuntime, val scope: PrivateResourceScope, val record: PrivateResourceRecord,
        val grant: String, val persisted: MutableList<ByteArray>, val policy: DataProtectionPolicyV1, val coordinator: AppOperationalStateCoordinator)

    private suspend fun fixture(): Fixture {
        val scope = PrivateResourceScope("a".repeat(64), "b".repeat(64), AccountVaultId("private-vault-test"))
        val vault = FakeVaultKeyService()
        vault.provision(VaultProvisioningRequest(scope.vaultId, scope.accountIdHash, false, true))
        vault.unlock(scope.vaultId)
        val trust = FakeDeviceTrustService(DeviceTrustSnapshot("installation-private-test", HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            DeviceTrustState.TRUSTED, 1, System.currentTimeMillis()))
        val policy = mockk<DataProtectionPolicyV1> {
            every { policyId } returns "test-private-policy"
            every { version } returns 1L
            every { effectiveAtEpochMillis } returns 0L
            every { expiresAtEpochMillis } returns null
        }
        val provider = mockk<DataProtectionPolicyProvider> {
            every { current() } returns ActiveDataProtectionPolicy(policy, DataProtectionPolicySource.VERIFIED_REMOTE)
        }
        val descriptor = DeviceShareKeyDescriptor("installation-private-test", "key-private-test", ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            byteArrayOf(1), HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT)
        val key = ByteArray(32) { 7 }
        val deviceKeys = mockk<DeviceShareKeyService> {
            coEvery { descriptor() } returns descriptor
            coEvery { unwrapShare(any(), any(), any()) } answers { key.copyOf() }
        }
        val coordinator = mockk<AppOperationalStateCoordinator> {
            coEvery { start(any()) } coAnswers {
                val request = firstArg<LifecycleOperationRequest>()
                mockk<LifecycleOperation> {
                    every { operationId } returns request.operationId
                    every { lifecycleId } returns request.lifecycleId
                }
            }
            coEvery { recordClientReceipt(any()) } returns mockk { every { status } returns LifecycleOperationStatus.SUCCEEDED }
        }
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers {
            firstArg<List<LifecycleClientReceipt>>().forEach { coordinator.recordClientReceipt(it) }
        }
        val refs = mutableMapOf<String, String>()
        val editor = mockk<SharedPreferences.Editor> {
            every { putString(any(), any()) } answers { refs[firstArg()] = secondArg(); this@mockk }
            every { remove(any()) } answers { refs.remove(firstArg<String>()); this@mockk }
            every { commit() } returns true
        }
        val preferences = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { refs[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        val context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns preferences }
        val persisted = mutableListOf<ByteArray>()
        val files = spyk(InMemorySensitiveFileStore())
        coEvery { files.write(any(), any()) } coAnswers {
            val writer = secondArg<suspend (java.io.OutputStream) -> Unit>()
            val output = java.io.ByteArrayOutputStream()
            writer(output)
            persisted += output.toByteArray()
            callOriginal()
        }
        val runtime = DefaultMatrixContentProtectionRuntime().also { it.attach() }
        val kms = mockk<KmsShareClient>()
        val client = DefaultAccountPrivateResourceClient(kms, deviceKeys, vault, trust, provider, coordinator, runtime, files, context)
        val binding = MessageDigest.getInstance("SHA-256").digest(
            """{"account":"${scope.accountIdHash}","domain":"account-private-resource-v1","kind":"COMMON_BODY","resource":"resource-test-0001","version":"version-test-0001"}""".encodeToByteArray())
        val nonce = ByteArray(12) { 3 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(binding); doFinal("private body".encodeToByteArray())
        }
        val now = System.currentTimeMillis()
        val grant = buildJsonObject {
            put("resource_kind", "COMMON_BODY"); put("version_id", "version-test-0001")
            put("context_sha256", binding.joinToString("") { "%02x".format(it.toInt() and 255) })
            put("encrypted_key_b64", Base64.getEncoder().encodeToString(byteArrayOf(1)))
            put("server_time_ms", now); put("expires_at_ms", now + 60_000)
        }.toString()
        return Fixture(client, kms, runtime, scope,
            PrivateResourceRecord("resource-test-0001", "COMMON_BODY", 1, "version-test-0001", false, cipher, nonce), grant, persisted, policy, coordinator)
    }
}
