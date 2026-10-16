/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbeddedNtfyConnectionServiceTest {
    @Test
    fun `OEMs known to freeze background sockets use aggressive ntfy recovery intervals`() {
        assertThat(ntfyWebSocketPingIntervalSeconds("HUAWEI")).isEqualTo(3L)
        assertThat(ntfyWebSocketPingIntervalSeconds(" honor ")).isEqualTo(3L)
        assertThat(ntfyWebSocketPingIntervalSeconds("Xiaomi")).isEqualTo(3L)
        assertThat(ntfyWebSocketPingIntervalSeconds("REDMI")).isEqualTo(3L)
        assertThat(ntfyWebSocketPingIntervalSeconds("Poco")).isEqualTo(3L)
        assertThat(ntfyLivenessCheckIntervalMillis("Huawei")).isEqualTo(3_000L)
        assertThat(ntfyLivenessCheckIntervalMillis("Xiaomi")).isEqualTo(3_000L)
        assertThat(ntfyStaleConnectionTimeoutMillis("Xiaomi", isNextDeployment = true)).isEqualTo(60_000L)
        assertThat(ntfyStaleConnectionTimeoutMillis("HUAWEI", isNextDeployment = true)).isEqualTo(60_000L)
        assertThat(ntfyStaleConnectionTimeoutMillis("Redmi", isNextDeployment = true)).isEqualTo(60_000L)
    }

    @Test
    fun `established text silent websocket is left to ping pong and failed opening remains bounded`() {
        val timeout = ntfyStaleConnectionTimeoutMillis("Xiaomi", isNextDeployment = true)
        assertThat(shouldRefreshNtfyOpening(opened = true, lastActivityAt = 1_000, now = 1_801_000, timeoutMillis = timeout)).isFalse()
        assertThat(shouldRefreshNtfyOpening(opened = false, lastActivityAt = 1_000, now = 60_999, timeoutMillis = timeout)).isFalse()
        assertThat(shouldRefreshNtfyOpening(opened = false, lastActivityAt = 1_000, now = 61_000, timeoutMillis = timeout)).isTrue()
    }

    @Test
    fun `quiet socket survives ordinary ntfy keepalive interval but missing application heartbeat still recovers`() {
        val timeout = ntfyStaleConnectionTimeoutMillis("Xiaomi", isNextDeployment = true)
        for (elapsed in listOf(12_000L, 15_000L, 30_000L, 45_000L, 59_999L)) {
            assertThat(isConnectionStale(lastActivityAt = 1_000L, now = 1_000L + elapsed, timeoutMillis = timeout)).isFalse()
        }
        assertThat(isConnectionStale(lastActivityAt = 1_000L, now = 61_000L, timeoutMillis = timeout)).isTrue()
        // An actual text keepalive resets application liveness; transport pongs
        // do not update this timestamp and are checked by OkHttp itself.
        assertThat(isConnectionStale(lastActivityAt = 46_000L, now = 61_000L, timeoutMillis = timeout)).isFalse()
    }

    @Test
    fun `other Android devices use battery friendly ntfy recovery intervals`() {
        assertThat(ntfyWebSocketPingIntervalSeconds("Samsung")).isEqualTo(30L)
        assertThat(ntfyLivenessCheckIntervalMillis("OPPO")).isEqualTo(30_000L)
        assertThat(ntfyStaleConnectionTimeoutMillis("Samsung", isNextDeployment = true)).isEqualTo(60_000L)
    }

    @Test
    fun `stable deployment keeps battery friendly stale timeout on aggressive OEMs`() {
        assertThat(ntfyStaleConnectionTimeoutMillis("Xiaomi", isNextDeployment = false)).isEqualTo(60_000L)
        assertThat(ntfyStaleConnectionTimeoutMillis("HUAWEI", isNextDeployment = false)).isEqualTo(60_000L)
    }

    @Test
    fun `connection refresh is forced on the first service start`() {
        assertThat(shouldForceConnectionRefresh(lastRefreshAt = null, now = 1_000L)).isTrue()
    }

    @Test
    fun `connection refresh is debounced for duplicate start requests`() {
        assertThat(
            shouldForceConnectionRefresh(
                lastRefreshAt = 1_000L,
                now = 1_500L,
                minimumIntervalMillis = 1_000L,
            )
        ).isFalse()
    }

    @Test
    fun `connection refresh replaces stale sockets after the interval`() {
        assertThat(
            shouldForceConnectionRefresh(
                lastRefreshAt = 1_000L,
                now = 2_000L,
                minimumIntervalMillis = 1_000L,
            )
        ).isTrue()
    }

    @Test
    fun `connection without an activity timestamp is not treated as stale`() {
        assertThat(isConnectionStale(lastActivityAt = null, now = 100_000L, timeoutMillis = 90_000L)).isFalse()
    }

    @Test
    fun `active connection remains healthy before timeout`() {
        assertThat(
            isConnectionStale(
                lastActivityAt = 10_000L,
                now = 99_999L,
                timeoutMillis = 90_000L,
            )
        ).isFalse()
    }

    @Test
    fun `connection is stale at timeout boundary`() {
        assertThat(
            isConnectionStale(
                lastActivityAt = 10_000L,
                now = 100_000L,
                timeoutMillis = 90_000L,
            )
        ).isTrue()
    }

    @Test
    fun `elapsed realtime reset does not mark connection stale`() {
        assertThat(
            isConnectionStale(
                lastActivityAt = 100_000L,
                now = 10_000L,
                timeoutMillis = 90_000L,
            )
        ).isFalse()
    }

    @Test
    fun `message retry delay backs off and is capped`() {
        assertThat(messageRetryDelayMillis(attempt = 0)).isEqualTo(2_000L)
        assertThat(messageRetryDelayMillis(attempt = 1)).isEqualTo(2_000L)
        assertThat(messageRetryDelayMillis(attempt = 2)).isEqualTo(4_000L)
        assertThat(messageRetryDelayMillis(attempt = 5)).isEqualTo(32_000L)
        assertThat(messageRetryDelayMillis(attempt = 6)).isEqualTo(60_000L)
        assertThat(messageRetryDelayMillis(attempt = Int.MAX_VALUE)).isEqualTo(60_000L)
    }

    @Test
    fun `authentication rejection retires credential instead of reconnecting forever`() {
        assertThat(ntfyFailureAction(401)).isEqualTo(NtfyFailureAction.RETIRE_CREDENTIAL)
        assertThat(ntfyFailureAction(403)).isEqualTo(NtfyFailureAction.RETIRE_CREDENTIAL)
        assertThat(ntfyFailureAction(429)).isEqualTo(NtfyFailureAction.RETRY)
        assertThat(ntfyFailureAction(null)).isEqualTo(NtfyFailureAction.RETRY)
    }

    @Test
    fun `rate limit retry honors server delay and adds bounded jitter`() {
        assertThat(
            ntfyReconnectDelayMillis(attempt = 1, retryAfterHeader = "120", jitterMillis = 750L)
        ).isEqualTo(120_750L)
        assertThat(
            ntfyReconnectDelayMillis(attempt = 6, retryAfterHeader = null, jitterMillis = 250L)
        ).isEqualTo(60_250L)
        assertThat(
            ntfyReconnectDelayMillis(attempt = 1, retryAfterHeader = "invalid", jitterMillis = 5_000L)
        ).isEqualTo(3_000L)
    }
}
