/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.security.applyCallWindowSecurity
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallWindowSecurityTest {
    @Test
    fun `BLOCK adds and ALLOW clears secure flag`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.applyCallWindowSecurity(ScreenCapturePolicy.BLOCK)
        assertThat(activity.hasSecureFlag()).isTrue()

        activity.applyCallWindowSecurity(ScreenCapturePolicy.ALLOW)
        assertThat(activity.hasSecureFlag()).isFalse()
    }
}

private fun ComponentActivity.hasSecureFlag(): Boolean =
    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
