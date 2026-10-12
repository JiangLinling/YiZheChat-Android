/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

data class DeletedIdentityRecord(
    val accountIdHash: String,
    val generation: Long,
    val tombstoneSequence: Long,
    val destroyedAtEpochMillis: Long,
)

data class DeletedIdentityApplyResult(
    val fromSequenceExclusive: Long,
    val toSequenceInclusive: Long,
    val appliedAccountHashes: Set<String>,
    val idempotentReplay: Boolean,
)

/**
 * Process-wide, non-expiring projection of signed ACCOUNT tombstones.
 *
 * Consumers hash a canonical Matrix user id before querying this boundary. A tombstone is never
 * removed by logout, cache eviction, an older sync, or restoration of an SDK database.
 */
interface DeletedIdentityProjection {
    val revision: StateFlow<Long>

    fun isDeleted(accountIdHash: String): Boolean

    fun record(accountIdHash: String): DeletedIdentityRecord?
}

fun DeletedIdentityProjection.isDeletedMatrixUserId(matrixUserId: String): Boolean {
    return recordForMatrixUserId(matrixUserId) != null
}

fun DeletedIdentityProjection.recordForMatrixUserId(matrixUserId: String): DeletedIdentityRecord? {
    if (!matrixUserId.startsWith("@") || matrixUserId.isBlank()) return null
    val accountIdHash = MessageDigest.getInstance("SHA-256")
        .digest(matrixUserId.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return record(accountIdHash)
}
