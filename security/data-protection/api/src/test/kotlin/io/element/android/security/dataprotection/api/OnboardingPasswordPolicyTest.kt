/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingPasswordPolicyTest {
    @Test
    fun `valid password satisfies every requirement`() {
        assertThat(OnboardingPasswordPolicy.isSatisfiedBy("StrongPassword2026")).isTrue()
    }

    @Test
    fun `numeric-only password reports its exact missing requirements`() {
        assertThat(OnboardingPasswordPolicy.unmetRequirements("18076776131"))
            .containsExactly(
                OnboardingPasswordRequirement.MinimumLength,
                OnboardingPasswordRequirement.UppercaseLetter,
                OnboardingPasswordRequirement.LowercaseLetter,
            )
            .inOrder()
    }

    @Test
    fun `password rejects excessive length and control characters`() {
        val password = "Aa1\n" + "x".repeat(OnboardingPasswordPolicy.MAXIMUM_LENGTH)

        assertThat(OnboardingPasswordPolicy.unmetRequirements(password))
            .containsAtLeast(
                OnboardingPasswordRequirement.MaximumLength,
                OnboardingPasswordRequirement.NoControlCharacters,
            )
    }
}
