/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

internal fun interface SegmentedMediaWallClockPrototype {
    fun nowEpochMs(): Long
}

internal fun interface SegmentedMediaElapsedClockPrototype {
    fun elapsedRealtimeMs(): Long
}

internal enum class SegmentedMediaAuthorizationPurposePrototype {
    SEEK_ADMISSION,
    SEEK_DELIVERY,
    VERIFICATION_ADMISSION,
    VERIFICATION_PROGRESS,
    VERIFICATION_DELIVERY,
    PUBLICATION,
    READ_ADMISSION,
    READ_DELIVERY,
}

internal enum class SegmentedMediaAuthorizationDecisionPrototype {
    AUTHORIZED,
    OWNER_MISMATCH,
    LEASE_MISMATCH,
    GENERATION_MISMATCH,
    EXPIRED,
    REVOKED,
    AUTHORITY_UNAVAILABLE,
}

/**
 * Adapter over the existing lease authority. Implementations must consult the current authoritative lease record;
 * this prototype deliberately has no local grant, refresh, or deadline-extension path.
 */
internal fun interface SegmentedMediaLeaseAuthorizationValidatorPrototype {
    fun validate(
        context: SegmentedMediaReadContextPrototype,
        effectiveNowEpochMs: Long,
        purpose: SegmentedMediaAuthorizationPurposePrototype,
    ): SegmentedMediaAuthorizationDecisionPrototype
}

/**
 * Supplies rollback-resistant time to the authoritative validator. Wall-clock rollback cannot move effective time
 * backwards, while a forward wall-clock correction expires access immediately. The authority still owns the real
 * deadline and revocation state.
 */
internal class SegmentedMediaAuthorizationGatePrototype(
    private val validator: SegmentedMediaLeaseAuthorizationValidatorPrototype,
    private val wallClock: SegmentedMediaWallClockPrototype,
    private val elapsedClock: SegmentedMediaElapsedClockPrototype,
) {
    private val baselineWallEpochMs = wallClock.nowEpochMs()
    private val baselineElapsedMs = elapsedClock.elapsedRealtimeMs()
    private var lastEffectiveNowEpochMs = baselineWallEpochMs

    @Synchronized
    fun check(
        context: SegmentedMediaReadContextPrototype,
        purpose: SegmentedMediaAuthorizationPurposePrototype,
    ): SegmentedMediaAuthorizationDecisionPrototype {
        if (context.ownerToken.isBlank() || context.leaseId.isBlank() || context.generation <= 0L) {
            return SegmentedMediaAuthorizationDecisionPrototype.AUTHORITY_UNAVAILABLE
        }
        val elapsedDeltaMs = (elapsedClock.elapsedRealtimeMs() - baselineElapsedMs).coerceAtLeast(0L)
        val elapsedProjectedEpochMs = saturatingAdd(baselineWallEpochMs, elapsedDeltaMs)
        val effectiveNowEpochMs = maxOf(
            wallClock.nowEpochMs(),
            elapsedProjectedEpochMs,
            lastEffectiveNowEpochMs,
        )
        lastEffectiveNowEpochMs = effectiveNowEpochMs
        return validator.validate(context, effectiveNowEpochMs, purpose)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
