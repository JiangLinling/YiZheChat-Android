/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

/** Actual local object/read cleanup. Does not assert that all resources or runtimes of LC16 have ended. */
data class LifecycleMediaObjectCleanup(
    val operationId: String,
    val subjects: LifecycleSubjectHashes,
    val recordSha256: String,
    val confirmedAbsentAtEpochMillis: Long,
    val leaseQuiescentAtEpochMillis: Long,
) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{16,256}")))
        require(!subjects.installationId.isNullOrBlank() && subjects.installationId.length <= 256)
        require(subjects.accountIdHash != null && subjects.matrixDeviceIdHash != null)
        require(recordSha256.matches(Regex("[0-9a-f]{64}")))
        require(confirmedAbsentAtEpochMillis > 0 && leaseQuiescentAtEpochMillis > 0)
    }
}
