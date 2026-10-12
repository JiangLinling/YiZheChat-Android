/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.dataprotection.api.LifecycleStageTiming

/** Exact pairwise equality metadata only; never a cache key or an authorization decision. */
internal object ProtectedReadContextDiagnostics {
    fun descriptor(identity: ProtectedTimelineDecryptionCache.ReadIdentity): String =
        "p${identity.readPurpose?.name ?: "NONE"}_c${identity.releaseClass?.name ?: "NONE"}" +
            "_a${identity.authorizationRevision}_v${identity.previewRevision}"

    fun matchingFields(
        identity: ProtectedTimelineDecryptionCache.ReadIdentity,
        other: ProtectedTimelineDecryptionCache.ReadIdentity,
    ): Int {
        var bits = 0
        if (identity.projection == other.projection) bits = bits or 1
        if (identity.operationId == other.operationId) bits = bits or 2
        if (identity.matrixDeviceId == other.matrixDeviceId) bits = bits or 4
        if (identity.installationId == other.installationId) bits = bits or 8
        if (identity.authorizationRevision == other.authorizationRevision) bits = bits or 16
        if (identity.previewRevision == other.previewRevision) bits = bits or 32
        if (identity.releaseClass == other.releaseClass) bits = bits or 64
        if (identity.encryptedMessage == other.encryptedMessage) bits = bits or 128
        if (identity.readPurpose == other.readPurpose) bits = bits or 256
        return bits
    }

    fun observed(
        stage: String,
        identity: ProtectedTimelineDecryptionCache.ReadIdentity,
        other: ProtectedTimelineDecryptionCache.ReadIdentity?,
    ) {
        // Diagnostics must not turn a successful product path into a failure.
        runCatching {
            LifecycleStageTiming.observed(stage, identity.projection.contentId.value,
                "${descriptor(identity)}_m${other?.let { matchingFields(identity, it) } ?: "none"}", identity.operationId)
        }
    }
}
