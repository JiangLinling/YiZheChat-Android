/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

/**
 * Signed account-history boundary established after an explicit Matrix identity reset.
 *
 * Content older than [resetCommittedAtEpochMillis] is not automatically trusted by the new
 * identity. Consumers may conservatively suppress only Matrix events that also carry the real
 * `AuthenticityNotGuaranteed` shield; other security warnings remain untouched.
 */
data class AccountHistoryAccessBoundary(
    val resetCommittedAtEpochMillis: Long,
) {
    init {
        require(resetCommittedAtEpochMillis > 0L)
    }
}

interface AccountHistoryAccessBoundaryProvider {
    suspend fun current(): AccountHistoryAccessBoundary?

    data object Noop : AccountHistoryAccessBoundaryProvider {
        override suspend fun current(): AccountHistoryAccessBoundary? = null
    }
}
