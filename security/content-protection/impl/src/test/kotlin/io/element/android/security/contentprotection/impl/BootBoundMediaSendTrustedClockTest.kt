/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfiguration
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import org.junit.Test

class BootBoundMediaSendTrustedClockTest {
    @Test fun `server anchor advances only by elapsed realtime on same boot`() {
        val time = MutableClock(100, 1_000); val boot = MutableBoot(7)
        val clock = BootBoundMediaSendTrustedClock(time, boot, config())
        val sample = clock.requestStarted(); time.elapsed = 1_010
        assertThat(clock.acceptServerAnchor(sample, 50_000).getOrThrow().lowerBoundMillis).isEqualTo(50_000)
        assertThat(clock.currentTimeMillis().getOrThrow().upperBoundMillis).isEqualTo(50_010)
        time.elapsed = 1_110
        assertThat(clock.currentTimeMillis().getOrThrow().lowerBoundMillis).isEqualTo(50_100)
        assertThat(clock.currentTimeMillis().getOrThrow().upperBoundMillis).isEqualTo(50_110)
    }

    @Test fun `reboot rollback and excessive uncertainty fail closed`() {
        val time = MutableClock(100, 1_000); val boot = MutableBoot(7); val clock = BootBoundMediaSendTrustedClock(time, boot, config())
        val sample = clock.requestStarted(); time.elapsed = 1_100
        assertThat(clock.acceptServerAnchor(sample, 50_000).isFailure).isTrue()
        time.elapsed = 1_001; clock.acceptServerAnchor(clock.requestStarted(), 50_000)
        boot.value = 8
        assertThat(clock.currentTimeMillis().isFailure).isTrue()
    }

    @Test fun `delayed forty millisecond response retains half RTT uncertainty`() {
        val time = MutableClock(100, 1_000); val clock = BootBoundMediaSendTrustedClock(time, MutableBoot(7), config())
        val sample = clock.requestStarted(); time.elapsed = 1_040
        val interval = clock.acceptServerAnchor(sample, 50_000).getOrThrow()
        assertThat(interval.lowerBoundMillis).isEqualTo(50_000)
        assertThat(interval.upperBoundMillis).isEqualTo(50_040)
        assertThat(interval.midpointMillis).isEqualTo(50_020)
    }

    private fun config() = MediaSendAuthorityConfigurationProvider {
        MediaSendAuthorityConfiguration("https://cp.example", setOf("sha256/${"A".repeat(43)}="), ByteArray(91), 25)
    }
    private class MutableClock(var wall: Long, var elapsed: Long) : OfflineLicenseClock {
        override fun wallClockEpochMillis() = wall; override fun elapsedRealtimeMillis() = elapsed
    }
    private class MutableBoot(var value: Long) : DeviceRuntimeBindingProvider { override fun bootCount() = value }
}
