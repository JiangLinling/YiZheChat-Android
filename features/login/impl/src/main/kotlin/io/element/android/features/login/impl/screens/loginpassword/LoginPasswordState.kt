/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import android.os.Parcelable
import io.element.android.appconfig.AuthenticationConfig
import io.element.android.features.login.impl.accountprovider.AccountProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.security.dataprotection.api.OnboardingPasswordPolicy
import io.element.android.security.dataprotection.api.OnboardingUsernameSuggestion
import io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation
import kotlinx.parcelize.Parcelize

data class LoginPasswordState(
    val accountProvider: AccountProvider,
    val formState: LoginFormState,
    val loginAction: AsyncData<SessionId>,
    val mode: LoginPasswordMode = LoginPasswordMode.LOGIN,
    val invitationFormState: InvitationRegistrationFormState = InvitationRegistrationFormState.Default,
    val invitationAction: AsyncData<VerifiedOnboardingInvitation> = AsyncData.Uninitialized,
    val usernameSuggestion: AsyncData<OnboardingUsernameSuggestion> = AsyncData.Uninitialized,
    val eventSink: (LoginPasswordEvents) -> Unit
) {
    val displayedLoginAccount: String
        get() = usernameSuggestion.dataOrNull()?.loginAccount
            ?: invitationFormState.preferredStem
                .takeIf { invitationFormState.displayName.isNotBlank() && it.length >= 2 }
                ?.let { stem -> AuthenticationConfig.YIZHECHAT_USER_PREFIX + stem }
                .orEmpty()

    val submitEnabled: Boolean
        get() = when (mode) {
            LoginPasswordMode.LOGIN -> loginAction !is AsyncData.Failure &&
                formState.login.isNotEmpty() &&
                formState.password.isNotEmpty()
            LoginPasswordMode.INVITATION_REGISTER -> loginAction !is AsyncData.Failure &&
                invitationAction is AsyncData.Success &&
                usernameSuggestion is AsyncData.Success &&
                invitationFormState.displayName.isNotBlank() &&
                OnboardingPasswordPolicy.isSatisfiedBy(invitationFormState.password) &&
                invitationFormState.password == invitationFormState.passwordConfirmation
        }
}

enum class LoginPasswordMode {
    LOGIN,
    INVITATION_REGISTER,
}

@Parcelize
data class LoginFormState(
    val login: String,
    val password: String,
) : Parcelable {
    companion object {
        val Default = LoginFormState("", "")
        val savedStateSaver = androidx.compose.runtime.saveable.Saver<LoginFormState, LoginFormState>(
            save = { it.copy(password = "") },
            restore = { it.copy(password = "") },
        )
    }
}

@Parcelize
data class InvitationRegistrationFormState(
    val inviteCode: String,
    val publicId: String?,
    val displayName: String,
    val preferredStem: String,
    val password: String,
    val passwordConfirmation: String,
    val idempotencyKey: String,
) : Parcelable {
    companion object {
        val savedStateSaver = androidx.compose.runtime.saveable.Saver<InvitationRegistrationFormState, InvitationRegistrationFormState>(
            save = { it.copy(password = "", passwordConfirmation = "") },
            restore = { it.copy(password = "", passwordConfirmation = "") },
        )
        val Default = InvitationRegistrationFormState(
            inviteCode = "",
            publicId = null,
            displayName = "",
            preferredStem = "",
            password = "",
            passwordConfirmation = "",
            idempotencyKey = "",
        )
    }
}
