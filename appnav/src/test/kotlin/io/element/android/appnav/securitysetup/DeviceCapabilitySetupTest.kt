/*
 * Copyright 2025 New Vector Ltd
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.securitysetup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceCapabilitySetupTest {
    @Test
    fun `completion marker is persisted for verification callback completion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("device_capability_setup", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        context.markDeviceCapabilitySetupCompleted()

        assertThat(preferences.getBoolean("complete_v4", false)).isTrue()
        assertThat(preferences.contains("current_index_v2")).isFalse()
    }
}
