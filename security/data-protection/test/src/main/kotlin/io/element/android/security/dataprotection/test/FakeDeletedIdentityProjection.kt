/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.DeletedIdentityRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeDeletedIdentityProjection(
    initialAccountHashes: Set<String> = emptySet(),
) : DeletedIdentityProjection {
    private val records = initialAccountHashes.associateWith { accountHash ->
        DeletedIdentityRecord(accountHash, generation = 1, tombstoneSequence = 1, destroyedAtEpochMillis = 1)
    }.toMutableMap()
    private val mutableRevision = MutableStateFlow(records.size.toLong())

    override val revision: StateFlow<Long> = mutableRevision

    override fun isDeleted(accountIdHash: String): Boolean = accountIdHash in records

    override fun record(accountIdHash: String): DeletedIdentityRecord? = records[accountIdHash]

    fun markDeleted(accountIdHash: String, generation: Long = 1) {
        records[accountIdHash] = DeletedIdentityRecord(
            accountIdHash = accountIdHash,
            generation = generation,
            tombstoneSequence = mutableRevision.value + 1,
            destroyedAtEpochMillis = 1,
        )
        mutableRevision.value += 1
    }
}
