/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.MediaSendClockSample
import io.element.android.security.contentprotection.api.MediaSendTrustedTime
import io.element.android.security.contentprotection.api.MediaSendTrustedClock
import io.element.android.security.contentprotection.api.OfflineLicenseClock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BootBoundMediaSendTrustedClock(
    private val clock: OfflineLicenseClock,
    private val runtimeBindingProvider: DeviceRuntimeBindingProvider,
    private val configurationProvider: MediaSendAuthorityConfigurationProvider,
) : MediaSendTrustedClock {
    @Volatile private var anchor: Anchor? = null

    override fun requestStarted(): MediaSendClockSample = MediaSendClockSample(
        bootCount = runtimeBindingProvider.bootCount().also { require(it >= 0) },
        elapsedRealtimeMillis = clock.elapsedRealtimeMillis().also { require(it >= 0) },
    )

    @Synchronized
    override fun acceptServerAnchor(
        sample: MediaSendClockSample,
        serverIssuedAtMillis: Long,
    ): Result<MediaSendTrustedTime> = runCatching {
        require(serverIssuedAtMillis > 0)
        val configuration = requireNotNull(configurationProvider.configuration())
        val nowBoot = runtimeBindingProvider.bootCount()
        val responseElapsed = clock.elapsedRealtimeMillis()
        require(nowBoot == sample.bootCount && responseElapsed >= sample.elapsedRealtimeMillis)
        val roundTrip = Math.subtractExact(responseElapsed, sample.elapsedRealtimeMillis)
        require(roundTrip <= configuration.maximumClockUncertaintyMillis * 2)
        val midpoint = Math.addExact(sample.elapsedRealtimeMillis, roundTrip / 2)
        val uncertainty = (roundTrip + 1) / 2
        require(uncertainty <= configuration.maximumClockUncertaintyMillis)
        val candidate = Anchor(sample.bootCount, midpoint, serverIssuedAtMillis, uncertainty)
        anchor?.let { previous ->
            require(previous.bootCount == candidate.bootCount)
            val previousAtCandidate = Math.addExact(previous.serverEpochMillis, midpoint - previous.elapsedRealtimeMillis)
            require(candidate.serverEpochMillis + candidate.uncertaintyMillis >= previousAtCandidate - previous.uncertaintyMillis)
        }
        anchor = candidate
        candidate.intervalAt(responseElapsed)
    }

    override fun currentTimeMillis(): Result<MediaSendTrustedTime> = runCatching {
        val current = requireNotNull(anchor)
        require(runtimeBindingProvider.bootCount() == current.bootCount)
        val elapsed = clock.elapsedRealtimeMillis()
        require(elapsed >= current.elapsedRealtimeMillis)
        current.intervalAt(elapsed)
    }

    private fun Anchor.intervalAt(elapsedRealtimeMillis: Long): MediaSendTrustedTime {
        val center = Math.addExact(serverEpochMillis, elapsedRealtimeMillis - this.elapsedRealtimeMillis)
        return MediaSendTrustedTime(
            lowerBoundMillis = Math.subtractExact(center, uncertaintyMillis),
            upperBoundMillis = Math.addExact(center, uncertaintyMillis),
        )
    }

    private data class Anchor(
        val bootCount: Long,
        val elapsedRealtimeMillis: Long,
        val serverEpochMillis: Long,
        val uncertaintyMillis: Long,
    )
}
