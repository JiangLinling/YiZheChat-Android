/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticOutcome
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticSink
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticStage
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import timber.log.Timber

class DeviceProofKmsDiagnosticsTest {
    @Test
    fun `same operation attempts mint new nonce and sign again with isolated diagnostics`() = runTest {
        val trust = CountingTrust()
        val provider = DeviceProofKmsAuthorizationProvider(trust)
        val tree = RecordingTree()
        val first = KmsTransportTrace("op-a", "share_release")
        val second = KmsTransportTrace("op-a", "share_release")
        Timber.plant(tree)
        try {
            val a = withContext(LifecycleOperationContext("op-a", FullAppLifecycleId.LC_13)) {
                checkNotNull(provider.authorizationForTrace(first)).asHeaderValue()
            }
            val b = withContext(LifecycleOperationContext("op-a", FullAppLifecycleId.LC_13)) {
                checkNotNull(provider.authorizationForTrace(second)).asHeaderValue()
            }
            assertThat(a).isNotEqualTo(b)
            assertThat(trust.currentCalls).isEqualTo(2)
            assertThat(trust.signCalls).isEqualTo(2)
            val payloads = trust.statements.map {
                Json.parseToJsonElement(it.decodeToString().removePrefix("YZKMS0002")).jsonObject
            }
            assertThat(payloads[0].getValue("operation_id").jsonPrimitive.content).isEqualTo("op-a")
            assertThat(payloads[1].getValue("operation_id").jsonPrimitive.content).isEqualTo("op-a")
            assertThat(payloads[0].getValue("nonce").jsonPrimitive.content)
                .isNotEqualTo(payloads[1].getValue("nonce").jsonPrimitive.content)
            for (trace in listOf(first, second)) {
                assertThat(tree.messages.any { it.contains("stage=kms_proof_trust_current") && it.contains("identity=${trace.requestId}") }).isTrue()
                assertThat(tree.messages.any { it.contains("stage=kms_proof_sign_control") && it.contains("identity=${trace.requestId}") }).isTrue()
            }
            assertThat(tree.messages.joinToString()).doesNotContain(a)
            assertThat(tree.messages.joinToString()).doesNotContain(b)
            payloads.forEach { assertThat(tree.messages.joinToString()).doesNotContain(it.getValue("nonce").jsonPrimitive.content) }
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `missing operation stays denied before signing`() = runTest {
        val trust = CountingTrust()
        assertThat(DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("", "other"))).isNull()
        assertThat(trust.currentCalls).isEqualTo(1)
        assertThat(trust.signCalls).isEqualTo(0)
    }

    @Test
    fun `trust failure stays nullable and only fixed reason is logged`() = runTest {
        val trust = CountingTrust().also { it.failure = IllegalStateException("PRIVATE_PAYLOAD") }
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            assertThat(DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("op", "other"))).isNull()
            assertThat(tree.messages.joinToString()).contains("scope=provider_exception")
            assertThat(tree.messages.joinToString()).doesNotContain("PRIVATE_PAYLOAD")
            assertThat(trust.signCalls).isEqualTo(0)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `cancellation is rethrown unchanged before signing`() = runTest {
        val cancelled = CancellationException("cancelled fixture")
        val trust = CountingTrust().also { it.failure = cancelled }
        val actual = try {
            DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("op", "other"))
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertThat(actual).isSameInstanceAs(cancelled)
        assertThat(trust.signCalls).isEqualTo(0)
    }

    @Test
    fun `installation change during signature stays denied`() = runTest {
        val trust = CountingTrust().also { it.changeInstallation = true }
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            val result = withContext(LifecycleOperationContext("op", FullAppLifecycleId.LC_13)) {
                DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("op", "share_release"))
            }
            assertThat(result).isNull()
            assertThat(trust.currentCalls).isEqualTo(1)
            assertThat(trust.signCalls).isEqualTo(1)
            assertThat(tree.messages.count { it.contains("stage=kms_proof_unavailable") }).isEqualTo(1)
            assertThat(tree.messages.joinToString()).contains("scope=identity_changed")
            assertThat(tree.messages.joinToString()).doesNotContain("scope=provider_exception")
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `signature cancellation escapes unchanged and is not nullable authorization`() = runTest {
        val cancelled = CancellationException("signature fixture cancellation")
        val trust = CountingTrust().also { it.signFailure = cancelled }
        val actual = withContext(LifecycleOperationContext("op", FullAppLifecycleId.LC_13)) {
            try {
                DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("op", "share_release"))
                null
            } catch (failure: CancellationException) {
                failure
            }
        }
        assertThat(actual).isSameInstanceAs(cancelled)
        assertThat(trust.signCalls).isEqualTo(1)
    }

    @Test
    fun `signature failure remains nullable and does not disclose exception`() = runTest {
        val trust = CountingTrust().also { it.signFailure = IllegalStateException("PRIVATE_SIGNATURE") }
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            val actual = withContext(LifecycleOperationContext("op", FullAppLifecycleId.LC_13)) {
                DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(KmsTransportTrace("op", "share_release"))
            }
            assertThat(actual).isNull()
            assertThat(trust.signCalls).isEqualTo(1)
            assertThat(tree.messages.joinToString()).contains("scope=provider_exception")
            assertThat(tree.messages.joinToString()).doesNotContain("PRIVATE_SIGNATURE")
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `part observer binds original request and operation without payload data`() = runTest {
        val trust = CountingTrust()
        val tree = RecordingTree()
        val first = KmsTransportTrace("operation-a", "share_release")
        val second = KmsTransportTrace("operation-b", "share_release")
        Timber.plant(tree)
        try {
            for (trace in listOf(first, second)) {
                withContext(LifecycleOperationContext(trace.operationId, FullAppLifecycleId.LC_13)) {
                    checkNotNull(DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(trace))
                }
            }
            trust.currentObservers[0]!!.finished(DeviceTrustDiagnosticStage.ROOT_READY, 10, 1_010, DeviceTrustDiagnosticOutcome.OK)
            trust.signObservers[1]!!.finished(DeviceTrustDiagnosticStage.HARDWARE_SIGN, 20, 2_020, DeviceTrustDiagnosticOutcome.ERROR)
            assertThat(tree.messages.any { it.contains("call=CURRENT request=${first.requestId} operation=operation-a") }).isTrue()
            assertThat(tree.messages.any { it.contains("call=SIGN_CONTROL request=${second.requestId} operation=operation-b") }).isTrue()
            val parts = tree.messages.filter { it.contains("call=") }.joinToString()
            trust.statements.forEach { assertThat(parts).doesNotContain(it.decodeToString()) }
            assertThat(parts).contains("outcome=ERROR")
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `legacy default overload emits no invented stages`() = runTest {
        val fake = FakeDeviceTrustService()
        var callbacks = 0
        val sink = DeviceTrustDiagnosticSink { _, _, _, _ -> callbacks++ }
        assertThat(fake.current(sink)).isEqualTo(fake.current())
        val statement = "legacy-statement-fixture".encodeToByteArray()
        val plain = fake.signControlStatement(statement)
        val observed = fake.signControlStatement(statement, sink)
        assertThat(observed.installationId).isEqualTo(plain.installationId)
        assertThat(observed.attestationVersion).isEqualTo(plain.attestationVersion)
        assertThat(observed.signature).isEqualTo(plain.signature)
        assertThat(callbacks).isEqualTo(0)
    }

    @Test
    fun `throwing log tree cannot replace authorization or its part observer result`() = runTest {
        val trust = CountingTrust()
        val trace = KmsTransportTrace("operation-logger-failure", "share_release")
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                throw IllegalStateException("private-logger-failure")
            }
        }
        Timber.plant(tree)
        try {
            val result = withContext(LifecycleOperationContext(trace.operationId, FullAppLifecycleId.LC_13)) {
                DeviceProofKmsAuthorizationProvider(trust).authorizationForTrace(trace)
            }
            assertThat(result).isNotNull()
            trust.currentObservers.single()!!.finished(DeviceTrustDiagnosticStage.ROOT_READY, 10, 20, DeviceTrustDiagnosticOutcome.OK)
            assertThat(trust.currentCalls).isEqualTo(1)
            assertThat(trust.signCalls).isEqualTo(1)
        } finally {
            Timber.uproot(tree)
        }
    }

    private class CountingTrust(
        private val fake: FakeDeviceTrustService = FakeDeviceTrustService(
            DeviceTrustSnapshot("fixture-installation", HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                DeviceTrustState.TRUSTED, 1, 1),
        ),
    ) : DeviceTrustService by fake {
        val currentObservers = mutableListOf<DeviceTrustDiagnosticSink?>()
        val signObservers = mutableListOf<DeviceTrustDiagnosticSink?>()
        override suspend fun current(diagnostics: DeviceTrustDiagnosticSink?): DeviceTrustSnapshot {
            currentObservers += diagnostics
            return current()
        }
        override suspend fun signControlStatement(statement: ByteArray, diagnostics: DeviceTrustDiagnosticSink?): DeviceControlRequestSignature {
            signObservers += diagnostics
            return signControlStatement(statement)
        }
        var currentCalls = 0
        var signCalls = 0
        var failure: Exception? = null
        var signFailure: Exception? = null
        val statements = mutableListOf<ByteArray>()
        var changeInstallation = false
        override suspend fun current(): DeviceTrustSnapshot {
            currentCalls++
            failure?.let { throw it }
            return fake.current()
        }
        override suspend fun signControlStatement(statement: ByteArray): DeviceControlRequestSignature {
            signCalls++
            signFailure?.let { throw it }
            statements += statement.copyOf()
            return fake.signControlStatement(statement).let {
                if (changeInstallation) it.copy(installationId = "changed-fixture") else it
            }
        }
    }

    private class RecordingTree : Timber.Tree() {
        val messages = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }
}
