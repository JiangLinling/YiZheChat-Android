/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.login.impl.screens.loginpassword

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.login.impl.R
import io.element.android.libraries.matrix.test.A_PASSWORD
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.libraries.architecture.AsyncData
import io.element.android.security.dataprotection.api.OnboardingUsernameSuggestion
import io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class LoginPasswordViewTest {
    @Test
    fun `shows concise branding and security reassurance`() = runAndroidComposeUiTest {
        setLoginPasswordView(aLoginPasswordState())

        val resources = activity!!.resources
        val brandTitle = resources.getString(R.string.screen_login_brand_title)
        val securityConnection = resources.getString(R.string.screen_login_security_connection)
        val securityEncryption = resources.getString(R.string.screen_login_security_encryption)
        onNodeWithText(brandTitle).assert(hasText(brandTitle))
        onNodeWithText(securityConnection).assert(hasText(securityConnection))
        onNodeWithText(securityEncryption).assert(hasText(securityEncryption))
        onNodeWithText(resources.getString(R.string.screen_login_subtitle)).assertDoesNotExist()
    }

    @Test
    fun `direct login page does not expose a back navigation control`() = runAndroidComposeUiTest {
        setLoginPasswordView(aLoginPasswordState())
        val back = activity!!.getString(CommonStrings.action_back)
        onNodeWithContentDescription(back).assertDoesNotExist()
    }

    @Test
    fun `changing login invokes the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        setLoginPasswordView(
            aLoginPasswordState(
                eventSink = eventsRecorder,
            ),
        )
        val userNameHint = activity!!.getString(R.string.screen_login_account_placeholder)
        onNodeWithText(userNameHint).performTextInput(A_USER_NAME)
        eventsRecorder.assertSingle(
            LoginPasswordEvents.SetLogin(A_USER_NAME)
        )
    }

    @Test
    fun `changing login removes new lines the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        setLoginPasswordView(
            aLoginPasswordState(
                eventSink = eventsRecorder,
            ),
        )
        val userNameHint = activity!!.getString(R.string.screen_login_account_placeholder)
        onNodeWithText(userNameHint).performTextInput("a\nb")
        eventsRecorder.assertSingle(
            LoginPasswordEvents.SetLogin("ab")
        )
    }

    @Test
    fun `clearing login invokes the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        setLoginPasswordView(
            aLoginPasswordState(
                formState = aLoginFormState(A_USER_NAME),
                eventSink = eventsRecorder,
            ),
        )
        val a11yClear = activity!!.getString(CommonStrings.action_clear)
        onNodeWithContentDescription(a11yClear).performClick()
        eventsRecorder.assertSingle(
            LoginPasswordEvents.SetLogin("")
        )
    }

    @Test
    fun `changing password invokes the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        setLoginPasswordView(
            aLoginPasswordState(
                eventSink = eventsRecorder,
            ),
        )
        val userNameHint = activity!!.getString(R.string.screen_login_password_placeholder)
        onNodeWithText(userNameHint).performTextInput(A_PASSWORD)
        eventsRecorder.assertSingle(
            LoginPasswordEvents.SetPassword(A_PASSWORD)
        )
    }

    @Test
    fun `reveal password makes the password visible`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>(expectEvents = false)
        setLoginPasswordView(
            aLoginPasswordState(
                formState = aLoginFormState(password = A_PASSWORD),
                eventSink = eventsRecorder,
            ),
        )
        onNodeWithTag(TestTags.loginPassword.value).assert(hasText("••••••••"))
        val resources = activity!!.resources
        // Show password
        val a11yShowPassword = resources.getString(CommonStrings.a11y_show_password)
        onNodeWithContentDescription(a11yShowPassword).performClick()
        onNodeWithTag(TestTags.loginPassword.value).assert(hasText(A_PASSWORD))
        // Hide password
        val a11yHidePassword = resources.getString(CommonStrings.a11y_hide_password)
        onNodeWithContentDescription(a11yHidePassword).performClick()
        onNodeWithTag(TestTags.loginPassword.value).assert(hasText("••••••••"))
    }

    @Test
    fun `when login is empty, login button is not enabled`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>(expectEvents = false)
        setLoginPasswordView(
            aLoginPasswordState(
                formState = aLoginFormState(password = A_PASSWORD),
                eventSink = eventsRecorder,
            ),
        )
        val login = activity!!.getString(R.string.screen_login_action)
        onNodeWithText(login).assertIsNotEnabled()
    }

    @Test
    fun `when password is empty, login button is not enabled`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>(expectEvents = false)
        setLoginPasswordView(
            aLoginPasswordState(
                formState = aLoginFormState(login = A_USER_NAME),
                eventSink = eventsRecorder,
            ),
        )
        val login = activity!!.getString(R.string.screen_login_action)
        onNodeWithText(login).assertIsNotEnabled()
    }

    @Test
    fun `invitation registration is rendered in the login screen without navigation`() = runAndroidComposeUiTest {
        setLoginPasswordView(aLoginPasswordState(mode = LoginPasswordMode.INVITATION_REGISTER))
        val resources = activity!!.resources

        onNodeWithText(resources.getString(R.string.screen_login_invitation_code)).assertExists()
        onNodeWithText(resources.getString(R.string.screen_login_display_name)).assertExists()
        onNodeWithText(resources.getString(R.string.screen_login_generated_account)).assertExists()
        onNodeWithText(resources.getString(R.string.screen_login_registration_password)).assertExists()
        onNodeWithText(resources.getString(R.string.screen_login_return)).assertExists()
    }

    @Test
    fun `numeric registration password explains every unmet requirement`() = runAndroidComposeUiTest {
        setLoginPasswordView(
            aLoginPasswordState(
                mode = LoginPasswordMode.INVITATION_REGISTER,
                invitationFormState = InvitationRegistrationFormState.Default.copy(
                    password = "18076776131",
                ),
            )
        )
        val resources = activity!!.resources
        val separator = resources.getString(R.string.screen_login_registration_password_requirement_separator)
        val missingRequirements = listOf(
            resources.getString(R.string.screen_login_registration_password_requirement_minimum_length),
            resources.getString(R.string.screen_login_registration_password_requirement_uppercase),
            resources.getString(R.string.screen_login_registration_password_requirement_lowercase),
        ).joinToString(separator)
        val expectedMessage = resources.getString(
            R.string.screen_login_registration_password_requirements_missing,
            missingRequirements,
        )

        onNodeWithText(expectedMessage).assertExists()
    }

    @Test
    fun `registration password confirmation mismatch is shown on the field`() = runAndroidComposeUiTest {
        setLoginPasswordView(
            aLoginPasswordState(
                mode = LoginPasswordMode.INVITATION_REGISTER,
                invitationFormState = InvitationRegistrationFormState.Default.copy(
                    password = "StrongPassword2026",
                    passwordConfirmation = "StrongPassword2027",
                ),
            )
        )

        onNodeWithText(activity!!.getString(R.string.screen_login_registration_password_mismatch)).assertExists()
    }

    @Test
    fun `generated account is shown from the name before invitation verification succeeds`() = runAndroidComposeUiTest {
        setLoginPasswordView(
            aLoginPasswordState(
                mode = LoginPasswordMode.INVITATION_REGISTER,
                invitationFormState = InvitationRegistrationFormState.Default.copy(
                    displayName = "天军",
                    preferredStem = "tianjun",
                ),
                invitationAction = AsyncData.Failure(IllegalStateException("expired invitation")),
            )
        )

        onNodeWithText("yizhetianjun").assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_login_register_action)).assertIsNotEnabled()
    }

    @Config(qualifiers = "h1280dp")
    @Test
    fun `complete verified invitation form submits registration`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        val password = "Strong-passphrase-2026"
        setLoginPasswordView(
            aLoginPasswordState(
                mode = LoginPasswordMode.INVITATION_REGISTER,
                invitationFormState = InvitationRegistrationFormState(
                    inviteCode = "381204",
                    publicId = null,
                    displayName = "三合",
                    preferredStem = "sanhe",
                    password = password,
                    passwordConfirmation = password,
                    idempotencyKey = "test-idempotency-key-0001",
                ),
                invitationAction = AsyncData.Success(
                    VerifiedOnboardingInvitation(
                        activationSession = "test-activation-session",
                        inviteCode = "381204",
                        inviterUserId = "@inviter:yizhesy.cn",
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
                eventSink = eventsRecorder,
            )
        )

        onNodeWithText(activity!!.getString(R.string.screen_login_register_action))
            .assertIsEnabled()
            .performClick()
        eventsRecorder.assertSingle(LoginPasswordEvents.SubmitInvitationRegistration)
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `clicking on login sends expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<LoginPasswordEvents>()
        setLoginPasswordView(
            aLoginPasswordState(
                formState = aLoginFormState(login = A_USER_NAME, password = A_PASSWORD),
                eventSink = eventsRecorder,
            ),
        )
        val login = activity!!.getString(R.string.screen_login_action)
        onNodeWithText(login).assertIsEnabled().performClick()
        eventsRecorder.assertSingle(
            LoginPasswordEvents.Submit
        )
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setLoginPasswordView(
    state: LoginPasswordState,
) {
    setContent {
        LoginPasswordView(
            state = state,
        )
    }
}
