/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.appnav.securitysetup

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.StartupSecurityReadiness
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import org.junit.Test

class StartupDeviceReadinessTest {
    private fun ready() = StartupSecurityReadiness(
        state = StartupSecurityReadinessState.READY_FOR_LC01,
        policyVersion = 1,
        installationId = "verified-installation",
        deviceSecurityState = ManagedDeviceSecurityState.READY,
        observedAtEpochMillis = 1_000,
        expiresAtEpochMillis = 2_000,
    )
    @Test fun `fresh signed startup READY can be reused`() {
        assertThat(canReuseStartupDeviceReady(ready(), 1_500)).isTrue()
    }
    @Test fun `partial registration must reach its exit action instead of cached approval`() {
        assertThat(canReuseStartupDeviceReady(ready().copy(reasonCode = "REGISTRATION_PENDING"), 1_500)).isFalse()
    }
    @Test fun `expiry and clock rollback force a new check`() {
        assertThat(canReuseStartupDeviceReady(ready(), 2_000)).isFalse()
        assertThat(canReuseStartupDeviceReady(ready(), 999)).isFalse()
        assertThat(canReuseStartupDeviceReady(ready().copy(expiresAtEpochMillis = null), 1_500)).isFalse()
    }
    @Test fun `every incomplete or denied startup state forces a new check`() {
        StartupSecurityReadinessState.entries.filter { it != StartupSecurityReadinessState.READY_FOR_LC01 }.forEach {
            assertThat(canReuseStartupDeviceReady(ready().copy(state = it), 1_500)).isFalse()
        }
    }
    @Test fun `real device admission requirements cannot use the READY shortcut`() {
        ManagedDeviceSecurityState.entries.filter { it != ManagedDeviceSecurityState.READY }.forEach {
            assertThat(canReuseStartupDeviceReady(ready().copy(deviceSecurityState = it), 1_500)).isFalse()
        }
    }
    @Test fun `missing authority binding forces a new check`() {
        assertThat(canReuseStartupDeviceReady(null, 1_500)).isFalse()
        assertThat(canReuseStartupDeviceReady(ready().copy(policyVersion = null), 1_500)).isFalse()
        assertThat(canReuseStartupDeviceReady(ready().copy(installationId = ""), 1_500)).isFalse()
    }
}
