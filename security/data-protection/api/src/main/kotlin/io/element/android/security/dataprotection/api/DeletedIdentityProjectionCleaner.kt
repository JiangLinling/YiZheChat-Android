/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

data class DeletedIdentityProjectionCleanupTarget(
    val deletedIdentity: DeletedIdentityRecord,
    val roomIds: Set<String>,
)

data class DeletedIdentityProjectionCleanupResult(
    val clearedRoomIds: Set<String>,
    val failedRoomIds: Set<String>,
)

/** Clears only session-local room projections that point at an authoritatively deleted identity. */
interface DeletedIdentityProjectionCleaner {
    suspend fun clear(target: DeletedIdentityProjectionCleanupTarget): DeletedIdentityProjectionCleanupResult
}
