/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.api

import io.element.android.security.securestorage.api.AuthorizedSecureMediaLease
import kotlin.time.Duration

/** Secret-bearing Matrix encrypted-file material from an authenticated protected descriptor. */
data class SegmentedMatrixEncryptedFile(
    val version: String,
    val algorithm: String,
    val keyType: String,
    val keyOperations: Set<String>,
    val extractable: Boolean,
    val keyBase64Url: String,
    val ivBase64: String,
) {
    override fun toString(): String =
        "SegmentedMatrixEncryptedFile(version=$version, algorithm=$algorithm, keyType=$keyType, " +
            "keyOperations=$keyOperations, extractable=$extractable, keyBase64Url=<redacted>, ivBase64=<redacted>)"
}

data class SegmentedProtectedMediaLocator(
    val mxcUri: String,
    val expectedOuterSha256Hex: String,
    val expectedOuterLength: Long,
    val encryptedFile: SegmentedMatrixEncryptedFile,
)

data class SegmentedProtectedMediaResource(
    val assetId: String,
    val mediaVersion: Long,
    val roomIdSha256Hex: String,
    val contentContractId: String,
    val contentPolicyVersion: Long,
    val resourceId: String,
    val contentId: String,
    val kind: String,
    val protectedContentKind: String,
    val index: Int,
    val expectedPlaintextLength: Long,
    val immutableContextSha256Hex: String,
    val locator: SegmentedProtectedMediaLocator,
)

data class SegmentedProtectedMediaAcquisitionRequest(
    val resource: SegmentedProtectedMediaResource,
    /** Unique owner/generation identity; used to derive a collision-resistant staging object identity. */
    val acquisitionId: String,
    val mimeType: String = "video/mp4",
    val displayName: String? = null,
    val timeToLive: Duration,
)

data class SegmentedOuterFetchReceipt(
    val mxcAuthority: String,
    val homeserverAuthority: String,
    val actualOuterSha256Hex: String,
    val actualOuterLength: Long,
    val authenticated: Boolean,
    val redirectCount: Int,
)

data class SegmentedProtectedMediaAcquisition(
    val lease: AuthorizedSecureMediaLease,
    val fetchReceipt: SegmentedOuterFetchReceipt,
) : AutoCloseable {
    override fun close() = lease.close()
}

/** Matrix owns network and outer decryption; callers receive only an authorized local lease. */
fun interface SegmentedProtectedMediaResourceAcquirer {
    suspend fun acquire(request: SegmentedProtectedMediaAcquisitionRequest): SegmentedProtectedMediaAcquisition
}
