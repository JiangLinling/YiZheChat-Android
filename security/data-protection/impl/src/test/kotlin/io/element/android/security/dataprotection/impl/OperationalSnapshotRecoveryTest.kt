/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class OperationalSnapshotRecoveryTest {
    private val now = 1_000_001L
    private val ids = listOf("operation-recovery-0001", "operation-recovery-0002")
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshot = AppOperationalSnapshot(
        accountIdHash = "a".repeat(64),
        installationId = "installation-batch-test-01",
        matrixDeviceIdHash = "b".repeat(64),
        issuedAtEpochMillis = 1_000_000,
        expiresAtEpochMillis = 1_060_000,
        overallStatus = AppOperationalStatus.READY,
        components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith {
            ComponentOperationalState(AppOperationalStatus.READY, it, "test-revision", 1_000_000, 1_060_000)
        },
        activeOperationIds = ids,
        signingKeyId = "test-signing-key",
        signatureBase64 = "signature-original-snapshot",
    )
    private fun operation(id: String, anonymous: Boolean = false) = LifecycleOperationWire(
        schemaVersion = 1,
        catalogId = "yizhechat-full-app-lifecycle-v1",
        operationId = id,
        lifecycleId = "LC-13",
        idempotencyKey = id,
        subjects = LifecycleSubjectHashesWire(
            accountIdHash = if (anonymous) null else snapshot.accountIdHash,
            installationId = snapshot.installationId,
            matrixDeviceIdHash = if (anonymous) null else "c".repeat(64),
        ),
        state = "RUNNING",
        currentPhase = "INTENT_PERSISTED",
        authorityRevisions = emptyMap(),
        steps = emptyList(),
        deadlineEpochMillis = 1_060_000,
        compensationState = "NOT_REQUIRED",
        createdAtEpochMillis = 1_000_000,
        updatedAtEpochMillis = 1_000_000,
    )
    private fun response(operations: List<LifecycleOperationWire> = listOf(operation(ids[0]), operation(ids[1], true))) = buildJsonObject {
        put("schema_version", 1)
        put("purpose", "OPERATIONAL_SNAPSHOT_RECOVERY")
        put("installation_id", snapshot.installationId)
        put("account_id_hash", snapshot.accountIdHash)
        put("matrix_device_id_hash", snapshot.matrixDeviceIdHash)
        put("nonce", "nonce-original-request-000001")
        put("snapshot_sha256", "d".repeat(64))
        put("issued_at_ms", now)
        put("expires_at_ms", snapshot.expiresAtEpochMillis)
        put("signing_key_id", snapshot.signingKeyId)
        put("signature_b64", "signature-original-batch")
        put("operations", json.parseToJsonElement(json.encodeToString(operations)))
    }
    private fun decode(payload: JsonObject, verified: Boolean = true, time: Long = now) = decodeOperationalRecoveryBatch(
        json,
        payload,
        snapshot,
        ids,
        "nonce-original-request-000001",
        "d".repeat(64),
        time,
    ) { _, _, _ -> verified }

    @Test fun `original older Matrix device and signed anonymous operation are preserved`() {
        val result = decode(response())
        assertThat(result.map { it.operationId }).containsExactlyElementsIn(ids).inOrder()
        assertThat(result[0].subjects.matrixDeviceIdHash).isEqualTo("c".repeat(64))
        assertThat(result[1].subjects.accountIdHash).isNull()
    }

    @Test fun `signature scope nonce digest and freshness failures reject the whole response`() {
        assertThat(runCatching { decode(response(), verified = false) }.isFailure).isTrue()
        val changes = mapOf(
            "installation_id" to "installation-other-0001",
            "account_id_hash" to "e".repeat(64),
            "matrix_device_id_hash" to "e".repeat(64),
            "nonce" to "nonce-another-request-000001",
            "snapshot_sha256" to "e".repeat(64),
            "purpose" to "ANOTHER_PURPOSE",
            "expires_at_ms" to "1060001",
        )
        changes.forEach { (field, value) ->
            assertThat(runCatching { decode(JsonObject(response() + (field to JsonPrimitive(value)))) }.isFailure).isTrue()
        }
        assertThat(runCatching { decode(response(), time = snapshot.expiresAtEpochMillis) }.isFailure).isTrue()
    }

    @Test fun `missing duplicate extra or cross owner operations reject the whole response`() {
        val first = operation(ids[0])
        listOf(
            listOf(first),
            listOf(first, first),
            listOf(first, operation("operation-unsigned-0003")),
            listOf(first, operation(ids[1]).copy(subjects = first.subjects.copy(accountIdHash = "e".repeat(64)))),
            listOf(first, operation(ids[1]).copy(subjects = first.subjects.copy(installationId = "installation-other-0001"))),
        ).forEach { operations -> assertThat(runCatching { decode(response(operations)) }.isFailure).isTrue() }
    }
}
