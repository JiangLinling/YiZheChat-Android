/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

enum class OnboardingPasswordRequirement {
    MinimumLength,
    MaximumLength,
    UppercaseLetter,
    LowercaseLetter,
    Digit,
    NoControlCharacters,
}

object OnboardingPasswordPolicy {
    const val MINIMUM_LENGTH = 12
    const val MAXIMUM_LENGTH = 128

    fun unmetRequirements(password: String): List<OnboardingPasswordRequirement> = buildList {
        if (password.length < MINIMUM_LENGTH) add(OnboardingPasswordRequirement.MinimumLength)
        if (password.length > MAXIMUM_LENGTH) add(OnboardingPasswordRequirement.MaximumLength)
        if (password.none { it in 'A'..'Z' }) add(OnboardingPasswordRequirement.UppercaseLetter)
        if (password.none { it in 'a'..'z' }) add(OnboardingPasswordRequirement.LowercaseLetter)
        if (password.none { it in '0'..'9' }) add(OnboardingPasswordRequirement.Digit)
        if (password.any(Char::isISOControl)) add(OnboardingPasswordRequirement.NoControlCharacters)
    }

    fun isSatisfiedBy(password: String): Boolean = unmetRequirements(password).isEmpty()
}
