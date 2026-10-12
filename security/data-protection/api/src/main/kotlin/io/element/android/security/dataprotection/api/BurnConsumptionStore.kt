/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

data class BurnConsumptionKey(
    val accountIdHash: String,
    val roomIdHash: String,
    val eventIdHash: String,
    val contentId: String,
) {
    init {
        require(HASH_PATTERN.matches(accountIdHash))
        require(HASH_PATTERN.matches(roomIdHash))
        require(HASH_PATTERN.matches(eventIdHash))
        require(contentId.length in 16..256)
    }

    private companion object {
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

enum class BurnConsumptionState {
    OPENING,
    VIEWING,
    END_PENDING,
    TERMINAL_CONFIRMED,
    FAILED,
}

data class PendingBurnConsumption(
    val key: BurnConsumptionKey,
    val rootOperationId: String?,
    val viewOperationId: String,
    val sessionId: String,
    val installationId: String,
    val policySnapshotSha256: String?,
    val operationDeadlineEpochMillis: Long,
    val state: BurnConsumptionState,
    val viewExpiresAtEpochMillis: Long?,
    val endReason: String?,
    val lastAttemptAtEpochMillis: Long,
    val failureCode: String?,
) {
    init {
        require(rootOperationId == null || rootOperationId.length in 16..256)
        require(viewOperationId.length in 16..256)
        require(sessionId.length in 16..128)
        require(installationId.length in 16..256)
        require(policySnapshotSha256 == null || HASH_PATTERN.matches(policySnapshotSha256))
        require(operationDeadlineEpochMillis > 0)
        require(viewExpiresAtEpochMillis == null || viewExpiresAtEpochMillis > 0)
        require(lastAttemptAtEpochMillis > 0)
        require(endReason == null || endReason.length in 1..128)
        require(failureCode == null || failureCode.length in 1..128)
    }

    private companion object {
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

interface BurnConsumptionStore {
    suspend fun get(key: BurnConsumptionKey): PendingBurnConsumption?

    suspend fun pending(): List<PendingBurnConsumption>

    /** Persist a new or forward-only checkpoint. Immutable operation/session/device facts may never change. */
    suspend fun persist(record: PendingBurnConsumption): PendingBurnConsumption

    /** Remove only a server-confirmed terminal checkpoint. */
    suspend fun removeConfirmed(key: BurnConsumptionKey): Boolean
}

fun BurnConsumptionState.canTransitionTo(next: BurnConsumptionState): Boolean = when (this) {
    BurnConsumptionState.OPENING -> next in setOf(
        BurnConsumptionState.OPENING,
        BurnConsumptionState.VIEWING,
        BurnConsumptionState.END_PENDING,
        BurnConsumptionState.FAILED,
    )
    BurnConsumptionState.VIEWING -> next in setOf(
        BurnConsumptionState.VIEWING,
        BurnConsumptionState.END_PENDING,
        BurnConsumptionState.FAILED,
    )
    BurnConsumptionState.END_PENDING -> next in setOf(
        BurnConsumptionState.END_PENDING,
        BurnConsumptionState.TERMINAL_CONFIRMED,
        BurnConsumptionState.FAILED,
    )
    BurnConsumptionState.TERMINAL_CONFIRMED -> next == BurnConsumptionState.TERMINAL_CONFIRMED
    BurnConsumptionState.FAILED -> next == BurnConsumptionState.FAILED
}
