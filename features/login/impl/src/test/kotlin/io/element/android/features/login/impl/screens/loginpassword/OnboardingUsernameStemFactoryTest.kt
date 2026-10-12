/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingUsernameStemFactoryTest {
    @Test
    fun `transliterates Chinese display names and normalizes numbered suffix`() {
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("天军")).isEqualTo("tianjun")
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("三合")).isEqualTo("sanhe")
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("三合1号")).isEqualTo("sanhe")
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("三合2号")).isEqualTo("sanhe02")
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("三合12号")).isEqualTo("sanhe12")
    }

    @Test
    fun `keeps only lowercase ASCII account characters`() {
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("  San He  ")).isEqualTo("sanhe")
        assertThat(OnboardingUsernameStemFactory.fromDisplayName("🌱")).matches("user[0-9a-f]{8}")
    }
}
