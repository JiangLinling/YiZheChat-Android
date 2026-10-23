/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** Fresh signed authority projection. INVITED is never an accepted friendship. */
data class ManagedDirectRoomResult(
    val roomId: String,
    val status: String,
    val clientOperationIds: List<String>,
    val installationId: String = "",
    val issuedAtEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long = 0,
)
