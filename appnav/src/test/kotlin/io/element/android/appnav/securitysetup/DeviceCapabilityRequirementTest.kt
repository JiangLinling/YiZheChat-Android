/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav.securitysetup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceCapabilityRequirementTest {
    @Test
    fun `do not disturb access does not block setup`() {
        assertThat(DeviceCapability.DO_NOT_DISTURB.isRequiredForSetup()).isFalse()
    }

    @Test
    fun `feature capabilities never become startup blockers`() {
        val requiredCapabilities = DeviceCapability.entries.filter(DeviceCapability::isRequiredForSetup)

        assertThat(requiredCapabilities).isEmpty()
    }
}
