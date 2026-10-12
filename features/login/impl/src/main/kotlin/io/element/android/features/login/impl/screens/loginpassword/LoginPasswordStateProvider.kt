/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.login.impl.accountprovider.AccountProvider
import io.element.android.features.login.impl.accountprovider.anAccountProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.security.dataprotection.api.OnboardingUsernameSuggestion
import io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation

open class LoginPasswordStateProvider : PreviewParameterProvider<LoginPasswordState> {
    override val values: Sequence<LoginPasswordState>
        get() = sequenceOf(
            aLoginPasswordState(),
            // Loading
            aLoginPasswordState(loginAction = AsyncData.Loading()),
            // Error
            aLoginPasswordState(loginAction = AsyncData.Failure(Exception("An error occurred"))),
            // Invitation registration
            aLoginPasswordState(
                mode = LoginPasswordMode.INVITATION_REGISTER,
                invitationFormState = InvitationRegistrationFormState(
                    inviteCode = "381204",
                    publicId = "abcdefghijklmnopqrstuvwxyz012345",
                    displayName = "三合",
                    preferredStem = "sanhe",
                    password = "Strong-passphrase-2026",
                    passwordConfirmation = "Strong-passphrase-2026",
                    idempotencyKey = "preview-idempotency-key-0001",
                ),
                invitationAction = AsyncData.Success(
                    VerifiedOnboardingInvitation(
                        activationSession = "preview-activation-session",
                        inviteCode = "381204",
                        inviterUserId = "@yizheinviter:yizhesy.cn",
                        inviterDisplayName = "推荐人",
                        expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
                    )
                ),
                usernameSuggestion = AsyncData.Success(
                    OnboardingUsernameSuggestion(
                        displayName = "三合",
                        loginAccount = "yizhesanhe",
                        matrixUserId = "@yizhesanhe:yizhesy.cn",
                    )
                ),
            ),
        )
}

fun aLoginPasswordState(
    accountProvider: AccountProvider = anAccountProvider(),
    formState: LoginFormState = LoginFormState.Default,
    loginAction: AsyncData<SessionId> = AsyncData.Uninitialized,
    mode: LoginPasswordMode = LoginPasswordMode.LOGIN,
    invitationFormState: InvitationRegistrationFormState = InvitationRegistrationFormState.Default,
    invitationAction: AsyncData<VerifiedOnboardingInvitation> = AsyncData.Uninitialized,
    usernameSuggestion: AsyncData<OnboardingUsernameSuggestion> = AsyncData.Uninitialized,
    eventSink: (LoginPasswordEvents) -> Unit = {},
) = LoginPasswordState(
    accountProvider = accountProvider,
    formState = formState,
    loginAction = loginAction,
    mode = mode,
    invitationFormState = invitationFormState,
    invitationAction = invitationAction,
    usernameSuggestion = usernameSuggestion,
    eventSink = eventSink,
)

fun aLoginFormState(
    login: String = "",
    password: String = "",
) = LoginFormState(
    login = login,
    password = password,
)
