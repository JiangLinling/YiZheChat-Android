/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecyclePhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Non-secret metadata in the existing lifecycle journal, never draft text or a send grant. */
data class PendingLifecycleDraftStart(val request: LifecycleOperationRequest, val installationId: String) {
    init {
        require(request.lifecycleId == FullAppLifecycleId.LC_14 && request.trigger == "draft_change")
        require(request.accountIdHash != null && request.matrixDeviceIdHash != null && request.roomIdHash != null)
        require(request.eventIdHash == null && request.roomCreationProtocol == null)
        require(installationId.length in 16..256)
    }
}

/** Persists only non-secret operation identifiers needed to re-verify lifecycle closure after process death. */
interface LifecycleOperationIdStore {
    fun latest(lifecycleId: FullAppLifecycleId): String?

    fun remember(operation: LifecycleOperation)

    fun pendingClientReceipts(): List<LifecycleClientReceipt> = emptyList()

    fun pendingDraftStarts(): List<PendingLifecycleDraftStart> = emptyList()

    /** One commit retires the original start only with the last acknowledged fact. */
    fun savePendingDraftState(starts: List<PendingLifecycleDraftStart>, receipts: List<LifecycleClientReceipt>) {
        error("Durable draft lifecycle storage is not configured.")
    }

    fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) {
        error("Durable client receipt storage is not configured.")
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SharedPreferencesLifecycleOperationIdStore(
    @ApplicationContext context: Context,
) : LifecycleOperationIdStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun latest(lifecycleId: FullAppLifecycleId): String? =
        preferences.getString(lifecycleId.preferenceKey(), null)

    override fun remember(operation: LifecycleOperation) {
        if (operation.lifecycleId !in PERSISTED_LIFECYCLES) return
        preferences.edit()
            .putString(operation.lifecycleId.preferenceKey(), operation.operationId)
            .apply()
    }

    override fun pendingClientReceipts(): List<LifecycleClientReceipt> =
        preferences.getStringSet(PENDING_RECEIPTS_KEY, emptySet()).orEmpty().map { encoded ->
            val value = Json.parseToJsonElement(encoded).jsonObject
            LifecycleClientReceipt(
                operationId = value.getValue("operation").jsonPrimitive.content,
                component = LifecycleComponent.valueOf(value.getValue("component").jsonPrimitive.content),
                phase = LifecyclePhase.valueOf(value.getValue("phase").jsonPrimitive.content),
                resultCode = value.getValue("result").jsonPrimitive.content,
                receiptReference = value.getValue("reference").jsonPrimitive.content,
                occurredAtEpochMillis = value.getValue("occurredAt").jsonPrimitive.long,
            )
        }

    override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) {
        val encoded = encodeReceipts(receipts)
        check(preferences.edit().putStringSet(PENDING_RECEIPTS_KEY, encoded).commit()) {
            "Unable to persist lifecycle client receipts."
        }
    }

    override fun pendingDraftStarts(): List<PendingLifecycleDraftStart> =
        preferences.getStringSet(PENDING_DRAFT_STARTS_KEY, emptySet()).orEmpty().map { encoded ->
            val value = Json.parseToJsonElement(encoded).jsonObject
            PendingLifecycleDraftStart(
                request = LifecycleOperationRequest(
                    operationId = value.getValue("operation").jsonPrimitive.content,
                    lifecycleId = FullAppLifecycleId.LC_14,
                    trigger = "draft_change",
                    idempotencyKey = value.getValue("idempotency").jsonPrimitive.content,
                    accountIdHash = value.getValue("account").jsonPrimitive.content,
                    matrixDeviceIdHash = value.getValue("device").jsonPrimitive.content,
                    roomIdHash = value.getValue("room").jsonPrimitive.content,
                    deadlineEpochMillis = value.getValue("deadline").jsonPrimitive.long,
                ),
                installationId = value.getValue("installation").jsonPrimitive.content,
            )
        }

    override fun savePendingDraftState(starts: List<PendingLifecycleDraftStart>, receipts: List<LifecycleClientReceipt>) {
        require(starts.size <= MAX_PENDING_DRAFT_STARTS && starts.map { it.request.operationId }.distinct().size == starts.size)
        val encoded = starts.map { entry ->
            val request = entry.request
            buildJsonObject {
                put("operation", request.operationId)
                put("idempotency", request.idempotencyKey)
                put("account", request.accountIdHash)
                put("device", request.matrixDeviceIdHash)
                put("room", request.roomIdHash)
                put("deadline", request.deadlineEpochMillis)
                put("installation", entry.installationId)
            }.toString()
        }.toSet()
        val encodedReceipts = encodeReceipts(receipts)
        check(preferences.edit().putStringSet(PENDING_DRAFT_STARTS_KEY, encoded)
            .putStringSet(PENDING_RECEIPTS_KEY, encodedReceipts).commit()) { "Unable to persist draft lifecycle state." }
    }

    private fun encodeReceipts(receipts: List<LifecycleClientReceipt>): Set<String> {
        require(receipts.size <= MAX_PENDING_RECEIPTS)
        return receipts.map { receipt ->
            require(receipt.operationId.length in 16..256)
            require(receipt.receiptReference.length <= 512 && receipt.resultCode.length <= 80)
            buildJsonObject {
                put("operation", receipt.operationId)
                put("component", receipt.component.name)
                put("phase", receipt.phase.name)
                put("result", receipt.resultCode)
                put("reference", receipt.receiptReference)
                put("occurredAt", receipt.occurredAtEpochMillis)
            }.toString()
        }.toSet()
    }

    private fun FullAppLifecycleId.preferenceKey(): String = "latest_${wireValue.lowercase()}_operation_id"

    private companion object {
        const val PREFERENCES_NAME = "app_lifecycle_operation_ids_v1"
        const val PENDING_RECEIPTS_KEY = "pending_client_receipts_v1"
        const val PENDING_DRAFT_STARTS_KEY = "pending_lc14_draft_starts_v1"
        const val MAX_PENDING_DRAFT_STARTS = 4096
        const val MAX_PENDING_RECEIPTS = 8192
        val PERSISTED_LIFECYCLES = setOf(FullAppLifecycleId.LC_05)
    }
}

internal object NoopLifecycleOperationIdStore : LifecycleOperationIdStore {
    override fun latest(lifecycleId: FullAppLifecycleId): String? = null

    override fun remember(operation: LifecycleOperation) = Unit

    // Legacy unit-test constructors only; production always uses the committed preferences store.
    override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) = Unit
}
