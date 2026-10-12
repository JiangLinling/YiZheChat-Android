/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import org.junit.Test

class BuiltInDataProtectionPolicyProviderTest {
    private val provider = BuiltInDataProtectionPolicyProvider()

    @Test
    fun `built in policy is a strict non expiring baseline`() {
        val activePolicy = provider.current()

        assertThat(activePolicy.source).isEqualTo(DataProtectionPolicySource.BUILT_IN_BASELINE)
        assertThat(activePolicy.policy.schemaVersion).isEqualTo(DataProtectionPolicyV1.SCHEMA_VERSION)
        assertThat(activePolicy.policy.version).isEqualTo(2)
        assertThat(activePolicy.policy.expiresAtEpochMillis).isNull()
        assertThat(activePolicy.policy.notificationExposure).isEqualTo(NotificationExposure.CONTENT_ALLOWED)
        assertThat(activePolicy.policy.hardwareTrustRequirement).isEqualTo(HardwareTrustRequirement.TEE_OR_STRONGBOX)
        assertThat(activePolicy.policy.allowContentExport).isFalse()
        assertThat(activePolicy.policy.allowClipboard).isFalse()
        assertThat(activePolicy.policy.allowExternalShare).isFalse()
        assertThat(activePolicy.policy.screenCapturePolicy).isEqualTo(ScreenCapturePolicy.BLOCK)
        assertThat(activePolicy.policy.requireContentEnvelope).isTrue()
        assertThat(activePolicy.policy.allowRtc).isFalse()
    }

    @Test
    fun `bounded offline policy locks after its signed deadline`() {
        val policy = BASELINE_POLICY.policy.copy(maximumOfflineMillis = 1_000)

        assertThat(isOfflineDeadlineExceeded(policy, 10_000, 11_000)).isFalse()
        assertThat(isOfflineDeadlineExceeded(policy, 10_000, 11_001)).isTrue()
        assertThat(isOfflineDeadlineExceeded(policy, 0, 1)).isTrue()
    }

    @Test
    fun `online required and expired policies fail closed`() {
        val online = BASELINE_POLICY.policy.copy(
            maximumOfflineMillis = 0,
            offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
        )
        val expiring = BASELINE_POLICY.policy.copy(expiresAtEpochMillis = 20_000)

        assertThat(isOfflineDeadlineExceeded(online, 10_000, 10_000)).isFalse()
        assertThat(isOfflineDeadlineExceeded(online, 10_000, 10_001)).isTrue()
        assertThat(isOfflineDeadlineExceeded(expiring, 10_000, 20_000)).isTrue()
    }
}
