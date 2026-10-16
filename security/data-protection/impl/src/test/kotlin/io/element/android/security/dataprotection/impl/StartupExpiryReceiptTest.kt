/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class StartupExpiryReceiptTest {
    private val installation = "test-installation-original"
    private val operation = LifecycleOperation(
        operationId = "test-expired-startup-operation", lifecycleId = FullAppLifecycleId.LC_02,
        idempotencyKey = "test-original-idempotency-key", subjects = LifecycleSubjectHashes(installationId = installation),
        status = LifecycleOperationStatus.FAILED_FINAL, currentPhase = LifecyclePhase.RECEIPTED,
        deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 2100,
        terminalReceiptId = "test-terminal-expiry-receipt",
    )

    private fun receipt(op: LifecycleOperation = operation, login: Boolean = false) = buildJsonObject {
        put("schema_version", 1); put("catalog_id", op.catalogId)
        put("receipt_id", op.terminalReceiptId); put("operation_id", op.operationId)
        put("lifecycle_id", op.lifecycleId.wireValue); put("terminal_state", "FAILED_FINAL")
        put("started_at_ms", 1000); put("completed_at_ms", 2100); put("signature_b64", "signed-test-receipt")
        if (!login) put("contract_revision", "restore-coordination-expiry-v1")
        put("subjects", buildJsonObject {
            put("installation_id", installation)
            op.subjects.accountIdHash?.let { put("account_id_hash", it) }
            op.subjects.matrixDeviceIdHash?.let { put("matrix_device_id_hash", it) }
        })
        put("failure", buildJsonObject {
            put("component", "CONTROL_PLANE"); put("deadline_ms", 2000)
            if (login) {
                put("result_code", "STARTUP_COORDINATION_DEADLINE_EXPIRED")
                put("grants_access", false); put("domain_completion", "UNVERIFIED")
            } else {
                put("result_code", "RESTORE_COMPLETION_DEADLINE_EXPIRED"); put("trigger", "process_create")
                put("compensation_state", "NOT_REQUIRED"); put("protected_access_authorization", "NOT_GRANTED_BY_EXPIRY")
            }
        })
    }

    @Test
    fun `exact immutable LC02 and LC06 expiry receipts allow new check only`() {
        var verified: JsonObject? = null
        assertThat(verifyStartupExpiryReceipt(receipt(), operation, "process_create", installation, 3000) { payload, signature ->
            verified = payload
            signature == "signed-test-receipt"
        }).isTrue()
        assertThat(verified).isEqualTo(JsonObject(receipt().filterKeys { it != "signature_b64" }))
        val login = operation.copy(lifecycleId = FullAppLifecycleId.LC_06,
            subjects = operation.subjects.copy(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)))
        assertThat(verifyStartupExpiryReceipt(receipt(login, true), login, "post_login", installation, 3000) { _, _ -> true }).isTrue()
    }

    @Test
    fun `signature rejection wrong identity time and failure kind remain closed`() {
        val raw = receipt()
        assertThat(verifyStartupExpiryReceipt(raw, operation, "process_create", installation, 3000) { _, _ -> false }).isFalse()
        val substitutions = listOf(
            "schema_version" to JsonPrimitive(2), "receipt_id" to JsonPrimitive("other-receipt"),
            "operation_id" to JsonPrimitive("other-operation"), "lifecycle_id" to JsonPrimitive("LC-22"),
            "terminal_state" to JsonPrimitive("CANCELLED"), "started_at_ms" to JsonPrimitive(999),
            "completed_at_ms" to JsonPrimitive(1999),
            "subjects" to buildJsonObject { put("installation_id", "other-installation") },
            "subjects" to buildJsonObject { put("installation_id", installation); put("account_id_hash", "a".repeat(64)) },
            "failure" to JsonObject(raw.getValue("failure").jsonObject + ("result_code" to JsonPrimitive("DEVICE_REVOKED"))),
            "failure" to JsonObject(raw.getValue("failure").jsonObject + ("deadline_ms" to JsonPrimitive(9000))),
            "failure" to JsonObject(raw.getValue("failure").jsonObject + ("compensation_state" to JsonPrimitive("REQUIRED"))),
        )
        substitutions.forEach { (key, value) ->
            assertThat(verifyStartupExpiryReceipt(JsonObject(raw + (key to value)), operation,
                "process_create", installation, 3000) { _, _ -> true }).isFalse()
        }
        assertThat(verifyStartupExpiryReceipt(raw, operation, "foreground", installation, 3000) { _, _ -> true }).isFalse()
        assertThat(verifyStartupExpiryReceipt(raw, operation, "process_create", "other-installation", 3000) { _, _ -> true }).isFalse()
        assertThat(verifyStartupExpiryReceipt(raw, operation, "process_create", installation, 1999) { _, _ -> true }).isFalse()
    }

    @Test
    fun `absent failure proof and wrong login access disposition remain closed`() {
        assertThat(verifyStartupExpiryReceipt(JsonObject(receipt().filterKeys { it != "failure" }), operation,
            "process_create", installation, 3000) { _, _ -> true }).isFalse()
        val login = operation.copy(lifecycleId = FullAppLifecycleId.LC_06)
        val raw = receipt(login, true)
        val bad = JsonObject(raw + ("failure" to JsonObject(raw.getValue("failure").jsonObject + ("grants_access" to JsonPrimitive(true)))))
        assertThat(verifyStartupExpiryReceipt(bad, login, "post_login", installation, 3000) { _, _ -> true }).isFalse()
    }
}
