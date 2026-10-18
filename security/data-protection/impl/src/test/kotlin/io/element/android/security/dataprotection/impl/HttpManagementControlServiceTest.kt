/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.PermanentAccountDeletionClientReceipt
import io.element.android.security.dataprotection.api.PermanentAccountDeletionState
import io.element.android.security.securestorage.api.DeviceTrustState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HttpManagementControlServiceTest {
    @Test
    fun `sync response treats omitted policy as absent while signed trust remains required`() {
        val json = Json { ignoreUnknownKeys = false }
        val requiredFields = """
            "server_time_epoch_millis":2000000,
            "device_trust":{"decision":{},"key_id":"authority-key","signature_b64":"authority-signature"},
            "commands":[]
        """.trimIndent()

        val omitted = json.decodeFromString(SyncResponseWire.serializer(), "{$requiredFields}")
        val explicitNull = json.decodeFromString(SyncResponseWire.serializer(), "{$requiredFields,\"policy\":null}")

        assertThat(omitted.policy).isNull()
        assertThat(explicitNull.policy).isNull()
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                SyncResponseWire.serializer(),
                """{"server_time_epoch_millis":2000000,"commands":[],"policy":null}""",
            )
        }
    }

    @Test
    fun `sync policy channel decodes the exact nullable R05 revision envelope`() {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
        val response = json.decodeFromString(
            SyncResponseWire.serializer(),
            """{
                "server_time_epoch_millis":2000000,
                "policy":{
                    "contract":"CP-POLICY","signatureSchemaVersion":1,
                    "revisionId":"revision-1","operationId":"operation-1","domain":"GLOBAL_POLICY",
                    "version":1,"payloadDigest":"${"a".repeat(64)}","policy":{},
                    "publicationCanonical":{},"publicationSignatureB64":"publication-signature",
                    "legacyPolicy":{},"legacyPolicySignatureB64":"legacy-signature","keyId":"key-1",
                    "effectiveAt":1999999,"expiresAt":null,
                    "serverFenceEvidenceDigest":"${"b".repeat(64)}"
                },
                "device_trust":{"decision":{},"key_id":"authority-key","signature_b64":"authority-signature"},
                "commands":[]
            }""".trimIndent(),
        )

        val envelope = json.decodeFromJsonElement(
            SignedPolicyRevisionEnvelopeWire.serializer(),
            checkNotNull(response.policy),
        )
        assertThat(envelope.contract).isEqualTo("CP-POLICY")
        assertThat(envelope.expiresAt).isNull()
    }

    @Test
    fun `only explicit upgrade rejection is classified as pre freeze blocked`() {
        val body = """{"detail":{"code":"LC22_UPGRADE_IN_PROGRESS","retryable":true}}""".encodeToByteArray()
        assertThat(isPermanentDeletionSubmissionBlocked(503, body)).isTrue()
        assertThat(isPermanentDeletionSubmissionBlocked(500, body)).isFalse()
        assertThat(isPermanentDeletionSubmissionBlocked(503, "unavailable".encodeToByteArray())).isFalse()
        assertThat(isPermanentDeletionSubmissionBlocked(503, """{"detail":"unavailable"}""".encodeToByteArray())).isFalse()
    }

    @Test
    fun `permanent deletion requests use the guarded protocol revision`() {
        val prepare = AccountDeletionPrepareWire(
            accountIdHash = "a".repeat(64),
            idempotencyKey = "account-deletion-idempotency-0001",
            confirmationTokenSha256 = "b".repeat(64),
            eraseData = true,
        )
        val commit = AccountDeletionCommitWire(
            deletionIntentId = "account-deletion-intent-0001",
            accountIdHash = "a".repeat(64),
            confirmationToken = "confirmation-token-value-00000001",
        )

        assertThat(prepare.protocolVersion).isEqualTo(2)
        assertThat(commit.protocolVersion).isEqualTo(2)
    }

    @Test
    fun `permanent deletion prepare response requires the frozen irreversible boundary`() {
        val response = Json.decodeFromString(
            AccountDeletionResponseWire.serializer(),
            """{"state":"PREPARED","account_state":"FROZEN","irreversible":true,"receipt":{},"key_id":"key","signature_b64":"signature"}""",
        )

        assertThat(response.state).isEqualTo("PREPARED")
        assertThat(response.accountState).isEqualTo("FROZEN")
        assertThat(response.irreversible).isTrue()
    }

    @Test
    fun `signed security erasure status exposes only a client clear capability`() {
        val now = 2_000_000L
        val operationId = "operation-delete-contract-0001"
        val milestone = buildJsonObject {
            put("schema_version", 1)
            put("contract_revision", "lc22-v2")
            put("purpose", "SECURITY_ERASURE_COMPLETE")
            put("operation_id", operationId)
            put("account_id_hash", "a".repeat(64))
            put("generation", 2)
            put("policy_version", "lc22-v2")
            put("completed_at_ms", now - 1)
            put("postcondition_hash", "b".repeat(64))
        }
        val wire = AccountDeletionStatusWire(
            operationId = operationId,
            accountState = "FROZEN",
            deletionState = "CLIENT_CLEAR_PENDING",
            securityErasureComplete = true,
            securityErasureReceipt = SecurityErasureEnvelopeWire(
                milestone = milestone,
                keyId = "authority-key",
                signatureBase64 = "authority-signature",
            ),
            policyVersion = "lc22-v2",
            generation = 2,
            requiredResults = emptyList(),
            unresolvedContradictions = emptyList(),
            retryable = true,
            clientReceiptCapability = DeletionScopedClientCapabilityWire(
                token = "deletion-scoped-token-client-clear-000001",
                expiresAtEpochMillis = now + 60_000,
                resourceScopeHash = "c".repeat(64),
                capability = "CLIENT_CLEAR",
            ),
        )

        val accepted = HttpManagementControlService.Validators.validateAccountDeletionStatus(
            wire = wire,
            expectedOperationId = operationId,
            nowEpochMillis = now,
            verifyMilestone = { true },
        )

        assertThat(accepted.deletionState).isEqualTo(PermanentAccountDeletionState.CLIENT_CLEAR_PENDING)
        assertThat(accepted.securityErasureMilestone?.operationId).isEqualTo(operationId)
        assertThat(accepted.clientReceiptCapability?.resourceScopeHash).isEqualTo("c".repeat(64))
        assertThrows(IllegalStateException::class.java) {
            HttpManagementControlService.Validators.validateAccountDeletionStatus(
                wire = wire,
                expectedOperationId = operationId,
                nowEpochMillis = now,
                verifyMilestone = { false },
            )
        }
    }

    @Test
    fun `deletion scoped token is authorized only for its client clear receipt`() {
        val operationId = "operation-delete-contract-0001"
        val allowedPath = "/v1/account-deletions/operations/$operationId/client-receipts"

        assertThat(isDeletionScopedCapabilityUseAllowed("POST", allowedPath, operationId)).isTrue()
        assertThat(isDeletionScopedCapabilityUseAllowed("GET", allowedPath, operationId)).isFalse()
        assertThat(isDeletionScopedCapabilityUseAllowed("POST", "/v1/login", operationId)).isFalse()
        assertThat(isDeletionScopedCapabilityUseAllowed("POST", "/_matrix/client/v3/sync", operationId)).isFalse()
        assertThat(isDeletionScopedCapabilityUseAllowed("POST", "/v1/kms/unwrap", operationId)).isFalse()
        assertThat(
            isDeletionScopedCapabilityUseAllowed(
                "POST",
                "/v1/account-deletions/operations/another-operation/client-receipts",
                operationId,
            )
        ).isFalse()
    }

    @Test
    fun `client clear signature binds exact receipt fields without bearer token`() {
        val receipt = PermanentAccountDeletionClientReceipt(
            operationId = "operation-delete-contract-0001",
            installationId = "installation-contract-0001",
            resourceScopeHash = "a".repeat(64),
            manifestDigestSha256 = "b".repeat(64),
            attestationEvidenceHash = "c".repeat(64),
            occurredAtEpochMillis = 1_234_567L,
            deletionScopedToken = "deletion-scoped-token-client-clear-000001",
        )

        val canonical = canonicalDeletionClientReceiptPayload(receipt).decodeToString()

        assertThat(canonical).isEqualTo(
            "{\"attestation_evidence_hash\":\"${"c".repeat(64)}\",\"installation_id\":\"installation-contract-0001\"," +
                "\"manifest_digest_sha256\":\"${"b".repeat(64)}\",\"occurred_at_epoch_millis\":1234567," +
                "\"operation_id\":\"operation-delete-contract-0001\",\"protocol_version\":1," +
                "\"resource_scope_hash\":\"${"a".repeat(64)}\"}"
        )
        assertThat(canonical).doesNotContain(receipt.deletionScopedToken)
    }

    @Test
    fun `sync transport failures keep distinct safe reason codes`() {
        assertThat(managementSyncFailureReasonCode(UnknownHostException())).isEqualTo("CONTROL_DNS_UNAVAILABLE")
        assertThat(managementSyncFailureReasonCode(ConnectException())).isEqualTo("CONTROL_CONNECTION_FAILED")
        assertThat(managementSyncFailureReasonCode(SocketTimeoutException())).isEqualTo("CONTROL_REQUEST_TIMEOUT")
        assertThat(managementSyncFailureReasonCode(SSLHandshakeException("test"))).isEqualTo("CONTROL_TLS_REJECTED")
        assertThat(managementSyncFailureReasonCode(IOException())).isEqualTo("CONTROL_NETWORK_IO")
    }

    @Test
    fun `only stable enrollment and upgrade-attestation codes permit enrollment bootstrap`() {
        assertThat(isStableEnrollmentBootstrapFailure(statusCode = 401, reasonCode = "DEVICE_NOT_ENROLLED")).isTrue()
        assertThat(isStableEnrollmentBootstrapFailure(statusCode = 401, reasonCode = "DEVICE_ATTESTATION_REFRESH_REQUIRED")).isTrue()

        listOf(
            401 to "UNAUTHORIZED",
            401 to "SIGNATURE_INVALID",
            401 to "DEVICE_REVOKED",
            401 to "INSTALLATION_IDENTITY_MISMATCH",
            403 to "DEVICE_NOT_ENROLLED",
            503 to "DEVICE_NOT_ENROLLED",
            403 to "DEVICE_ATTESTATION_REFRESH_REQUIRED",
            503 to "DEVICE_ATTESTATION_REFRESH_REQUIRED",
            401 to null,
        ).forEach { (status, reason) ->
            assertThat(isStableEnrollmentBootstrapFailure(statusCode = status, reasonCode = reason)).isFalse()
        }
    }

    @Test
    fun `clean data enrolls once while concurrent callers observe the completed enrollment`() {
        assertThat(
            deviceEnrollmentDecision(
                forceRemoteEnrollment = false,
                observedAttestationVersion = 0,
                currentAttestationVersion = 0,
                currentTrustState = DeviceTrustState.NOT_ENROLLED,
            )
        ).isEqualTo(DeviceEnrollmentDecision.ENROLL)

        assertThat(
            deviceEnrollmentDecision(
                forceRemoteEnrollment = false,
                observedAttestationVersion = 0,
                currentAttestationVersion = 1,
                currentTrustState = DeviceTrustState.PENDING,
            )
        ).isEqualTo(DeviceEnrollmentDecision.SKIP)
    }

    @Test
    fun `security states never trigger enrollment`() {
        listOf(
            DeviceTrustState.INTEGRITY_WARNING,
            DeviceTrustState.REVOKED,
            DeviceTrustState.LOST,
        ).forEach { state ->
            assertThat(
                deviceEnrollmentDecision(
                    forceRemoteEnrollment = true,
                    observedAttestationVersion = 1,
                    currentAttestationVersion = 1,
                    currentTrustState = state,
                )
            ).isEqualTo(DeviceEnrollmentDecision.BLOCK)
        }
    }

    @Test
    fun `stable remote not-enrolled fallback may refresh the same local identity`() {
        assertThat(
            deviceEnrollmentDecision(
                forceRemoteEnrollment = true,
                observedAttestationVersion = 1,
                currentAttestationVersion = 1,
                currentTrustState = DeviceTrustState.PENDING,
            )
        ).isEqualTo(DeviceEnrollmentDecision.ENROLL)
    }
}
