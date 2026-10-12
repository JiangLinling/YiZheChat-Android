/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.api

/** Original non-secret lifecycle identity. This is attribution, not permission to read media. */
data class SecureTransientMediaOwner(
    val operationId: String,
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val installationId: String?,
    val roomIdHash: String?,
    val eventIdHash: String?,
) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{16,256}")))
        require(listOf(accountIdHash, matrixDeviceIdHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(listOfNotNull(roomIdHash, eventIdHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(installationId == null || installationId.length in 1..256)
    }
}

/** Independent local object/read observations; does not attest to UI/runtime/KMS termination. */
data class SecureTransientObjectDeletion(
    val owner: SecureTransientMediaOwner,
    val identity: SecureObjectIdentity,
    val recordSha256: String,
    val confirmedAbsentAtEpochMillis: Long,
    val leaseQuiescentAtEpochMillis: Long? = null,
)
