/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A signed historical timeout permits a new check, never restores the old operation or grants access. */
internal fun verifyStartupExpiryReceipt(
    receipt: JsonObject,
    operation: LifecycleOperation,
    trigger: String,
    installationId: String,
    now: Long,
    verify: (JsonObject, String) -> Boolean,
): Boolean {
    fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    val signature = receipt.text("signature_b64") ?: return false
    if (!verify(JsonObject(receipt.filterKeys { it != "signature_b64" }), signature)) return false
    if (operation.status != LifecycleOperationStatus.FAILED_FINAL || operation.deadlineEpochMillis > now ||
        operation.subjects.installationId != installationId || operation.subjects.roomIdHash != null ||
        operation.subjects.eventIdHash != null) {
            return false
        }
    val process = operation.lifecycleId == FullAppLifecycleId.LC_02 && trigger == "process_create"
    val login = operation.lifecycleId == FullAppLifecycleId.LC_06 && trigger in setOf("post_login", "reactivation")
    if (!process && !login) return false
    if (receipt.text("schema_version") != "1" || receipt.text("catalog_id") != operation.catalogId ||
        receipt.text("receipt_id") != operation.terminalReceiptId || receipt.text("operation_id") != operation.operationId ||
        receipt.text("lifecycle_id") != operation.lifecycleId.wireValue || receipt.text("terminal_state") != "FAILED_FINAL" ||
        receipt.text("started_at_ms")?.toLongOrNull() != operation.createdAtEpochMillis) {
            return false
        }
    val completed = receipt.text("completed_at_ms")?.toLongOrNull() ?: return false
    if (completed < operation.deadlineEpochMillis || completed > now + 5_000) return false
    val subjects = receipt["subjects"]?.jsonObject ?: return false
    if (subjects.text("installation_id") != installationId ||
        subjects.text("account_id_hash") != operation.subjects.accountIdHash ||
        subjects.text("matrix_device_id_hash") != operation.subjects.matrixDeviceIdHash ||
        subjects.text("room_id_hash") != null || subjects.text("event_id_hash") != null) {
            return false
        }
    val failure = receipt["failure"]?.jsonObject ?: return false
    if (failure.text("component") != "CONTROL_PLANE" ||
        failure.text("deadline_ms")?.toLongOrNull() != operation.deadlineEpochMillis) {
            return false
        }
    return if (process) {
        receipt.text("contract_revision") == "restore-coordination-expiry-v1" &&
            failure.text("trigger") == trigger && failure.text("compensation_state") == "NOT_REQUIRED" &&
            failure.text("protected_access_authorization") == "NOT_GRANTED_BY_EXPIRY" &&
            failure.text("result_code") in setOf("RESTORE_ADMISSION_DEADLINE_EXPIRED", "RESTORE_COMPLETION_DEADLINE_EXPIRED")
    } else {
        failure.text("result_code") == "STARTUP_COORDINATION_DEADLINE_EXPIRED" &&
            failure.text("grants_access") == "false" && failure.text("domain_completion") == "UNVERIFIED"
    }
}
