/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.login.impl.R
import io.element.android.features.login.impl.error.loginError
import io.element.android.libraries.androidutils.clipboard.ClipboardIngress
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.components.form.textFieldState
import io.element.android.libraries.designsystem.modifiers.onTabOrEnterKeyFocusNext
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TextFieldValidity
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.testtags.testTag
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.dataprotection.api.OnboardingPasswordPolicy
import io.element.android.security.dataprotection.api.OnboardingPasswordRequirement
import java.util.Date

private val LoginSecurityActiveGreen = Color(0xFF24B15F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPasswordView(
    state: LoginPasswordState,
    modifier: Modifier = Modifier,
) {
    val autofillManager = LocalAutofillManager.current

    val isLoading by remember(state.loginAction) {
        derivedStateOf {
            state.loginAction is AsyncData.Loading
        }
    }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    fun submit() {
        // Clear focus to prevent keyboard issues with textfields
        focusManager.clearFocus(force = true)

        autofillManager?.commit()

        state.eventSink(
            if (state.mode == LoginPasswordMode.LOGIN) {
                LoginPasswordEvents.Submit
            } else {
                LoginPasswordEvents.SubmitInvitationRegistration
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {},
    ) { padding ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(state = scrollState)
                .padding(horizontal = 24.dp),
        ) {
            LoginHeader()
            Spacer(Modifier.height(44.dp))
            if (state.mode == LoginPasswordMode.LOGIN) {
                LoginForm(
                    state = state,
                    isLoading = isLoading,
                    onSubmit = ::submit,
                    onUseInvitation = {
                        state.eventSink(
                            LoginPasswordEvents.SwitchToInvitationRegistration(
                                ClipboardIngress.getPlainText(context)
                            )
                        )
                    },
                )
            } else {
                InvitationRegistrationForm(
                    state = state,
                    isLoading = isLoading,
                    onSubmit = ::submit,
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                text = stringResource(
                    if (state.mode == LoginPasswordMode.LOGIN) {
                        R.string.screen_login_action
                    } else {
                        R.string.screen_login_register_action
                    }
                ),
                showProgress = isLoading,
                onClick = ::submit,
                enabled = state.submitEnabled || isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(TestTags.loginContinue),
            )
            Spacer(modifier = Modifier.weight(1f))
            LoginSecurityNotice()
            Spacer(modifier = Modifier.height(28.dp))

            if (state.loginAction is AsyncData.Failure) {
                LoginErrorDialog(error = state.loginAction.error, onDismiss = {
                    state.eventSink(LoginPasswordEvents.ClearError)
                })
            }
        }
    }
}

@Composable
private fun LoginHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.yizhe_login_logo),
            contentDescription = stringResource(R.string.screen_login_brand_title),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.screen_login_brand_title),
            style = ElementTheme.typography.fontHeadingLgBold,
            color = ElementTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun LoginSecurityNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = CompoundIcons.Shield(),
            contentDescription = null,
            tint = LoginSecurityActiveGreen,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.screen_login_security_connection),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.screen_login_security_encryption),
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoginForm(
    state: LoginPasswordState,
    isLoading: Boolean,
    onSubmit: () -> Unit,
    onUseInvitation: () -> Unit,
) {
    var loginFieldState by textFieldState(stateValue = state.formState.login)
    var passwordFieldState by textFieldState(stateValue = state.formState.password)

    val focusManager = LocalFocusManager.current
    val eventSink = state.eventSink

    Column {
        TextField(
            label = stringResource(R.string.screen_login_account_label),
            value = loginFieldState,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .onTabOrEnterKeyFocusNext(focusManager)
                .testTag(TestTags.loginEmailUsername)
                .semantics {
                    contentType = ContentType.Username
                }
                .appendClipboardIngressPasteAction { text ->
                    val sanitized = text.sanitize()
                    loginFieldState = sanitized
                    eventSink(LoginPasswordEvents.SetLogin(sanitized))
                },
            placeholder = stringResource(R.string.screen_login_account_placeholder),
            onValueChange = {
                val sanitized = it.sanitize()
                loginFieldState = sanitized
                eventSink(LoginPasswordEvents.SetLogin(sanitized))
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = {
                focusManager.moveFocus(FocusDirection.Down)
            }),
            singleLine = true,
            trailingIcon = if (loginFieldState.isNotEmpty()) {
                {
                    Box(Modifier.clickable {
                        loginFieldState = ""
                        eventSink(LoginPasswordEvents.SetLogin(""))
                    }) {
                        Icon(
                            imageVector = CompoundIcons.Close(),
                            contentDescription = stringResource(CommonStrings.action_clear),
                            tint = ElementTheme.colors.iconSecondary
                        )
                    }
                }
            } else {
                null
            },
        )
        var passwordVisible by remember { mutableStateOf(false) }
        if (state.loginAction is AsyncData.Loading) {
            // Ensure password is hidden when user submits the form
            passwordVisible = false
        }
        Spacer(Modifier.height(20.dp))
        TextField(
            label = stringResource(R.string.screen_login_password_label),
            value = passwordFieldState,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .onTabOrEnterKeyFocusNext(focusManager)
                .testTag(TestTags.loginPassword)
                .semantics {
                    contentType = ContentType.Password
                }
                .appendClipboardIngressPasteAction { text ->
                    val sanitized = text.sanitize()
                    passwordFieldState = sanitized
                    eventSink(LoginPasswordEvents.SetPassword(sanitized))
                },
            onValueChange = {
                val sanitized = it.sanitize()
                passwordFieldState = sanitized
                eventSink(LoginPasswordEvents.SetPassword(sanitized))
            },
            placeholder = stringResource(R.string.screen_login_password_placeholder),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image =
                    if (passwordVisible) CompoundIcons.VisibilityOn() else CompoundIcons.VisibilityOff()
                val description =
                    if (passwordVisible) stringResource(CommonStrings.a11y_hide_password) else stringResource(CommonStrings.a11y_show_password)
                Box(Modifier.clickable { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = image,
                        contentDescription = description,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit() }
            ),
            singleLine = true,
        )
        Text(
            text = stringResource(R.string.screen_login_use_invitation),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textActionAccent,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 12.dp)
                .clickable(enabled = !isLoading, onClick = onUseInvitation),
        )
    }
}

@Composable
private fun InvitationRegistrationForm(
    state: LoginPasswordState,
    isLoading: Boolean,
    onSubmit: () -> Unit,
) {
    val eventSink = state.eventSink
    val form = state.invitationFormState
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var codeFieldState by textFieldState(stateValue = form.inviteCode)
    var displayNameFieldState by textFieldState(stateValue = form.displayName)
    var passwordFieldState by textFieldState(stateValue = form.password)
    var confirmationFieldState by textFieldState(stateValue = form.passwordConfirmation)
    var passwordVisible by remember { mutableStateOf(false) }
    if (isLoading) passwordVisible = false

    val unmetPasswordRequirements = OnboardingPasswordPolicy.unmetRequirements(form.password)
    val unmetPasswordRequirementLabels = mutableListOf<String>()
    for (requirement in unmetPasswordRequirements) {
        unmetPasswordRequirementLabels += passwordRequirementLabel(requirement)
    }
    val passwordValidity = when {
        form.password.isEmpty() -> TextFieldValidity.None
        unmetPasswordRequirements.isEmpty() -> TextFieldValidity.Valid
        else -> TextFieldValidity.Invalid
    }
    val passwordSupportingText = when {
        form.password.isEmpty() -> stringResource(R.string.screen_login_registration_password_requirements)
        unmetPasswordRequirements.isEmpty() -> stringResource(R.string.screen_login_registration_password_requirements_met)
        else -> stringResource(
            R.string.screen_login_registration_password_requirements_missing,
            unmetPasswordRequirementLabels.joinToString(
                separator = stringResource(R.string.screen_login_registration_password_requirement_separator)
            ),
        )
    }
    val passwordConfirmationMismatch = form.passwordConfirmation.isNotEmpty() &&
        form.password != form.passwordConfirmation

    Column {
        TextField(
            label = stringResource(R.string.screen_login_invitation_code),
            value = codeFieldState,
            enabled = !isLoading && state.invitationAction !is AsyncData.Loading,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.screen_login_invitation_code_hint),
            onValueChange = { value ->
                val code = value.filter(Char::isDigit).take(6)
                codeFieldState = code
                eventSink(LoginPasswordEvents.SetInvitationCode(code))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        when (val invitation = state.invitationAction) {
            is AsyncData.Loading -> Text(
                text = stringResource(R.string.screen_login_invitation_checking),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            is AsyncData.Failure -> Text(
                text = stringResource(R.string.screen_login_invitation_invalid),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textCriticalPrimary,
            )
            is AsyncData.Success -> Column {
                Text(
                    text = stringResource(R.string.screen_login_inviter, invitation.data.inviterDisplayName),
                    style = ElementTheme.typography.fontBodySmMedium,
                    color = ElementTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(
                        R.string.screen_login_invitation_expires,
                        android.text.format.DateFormat.getDateFormat(context).format(Date(invitation.data.expiresAtEpochMillis)) + " " +
                            android.text.format.DateFormat.getTimeFormat(context).format(Date(invitation.data.expiresAtEpochMillis)),
                    ),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            AsyncData.Uninitialized -> Unit
        }
        Spacer(Modifier.height(18.dp))
        TextField(
            label = stringResource(R.string.screen_login_display_name),
            value = displayNameFieldState,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .onTabOrEnterKeyFocusNext(focusManager),
            placeholder = stringResource(R.string.screen_login_display_name_hint),
            onValueChange = { value ->
                val displayName = value.sanitize().take(48)
                displayNameFieldState = displayName
                eventSink(LoginPasswordEvents.SetDisplayName(displayName))
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        TextField(
            label = stringResource(R.string.screen_login_generated_account),
            value = state.displayedLoginAccount,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.screen_login_generated_account_waiting),
            onValueChange = {},
            singleLine = true,
        )
        Spacer(Modifier.height(18.dp))
        TextField(
            label = stringResource(R.string.screen_login_registration_password),
            value = passwordFieldState,
            enabled = !isLoading,
            supportingText = passwordSupportingText,
            validity = passwordValidity,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.NewPassword },
            onValueChange = { value ->
                val password = value.sanitize()
                passwordFieldState = password
                eventSink(LoginPasswordEvents.SetRegistrationPassword(password))
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (passwordVisible) CompoundIcons.VisibilityOn() else CompoundIcons.VisibilityOff()
                val description = if (passwordVisible) {
                    stringResource(CommonStrings.a11y_hide_password)
                } else {
                    stringResource(CommonStrings.a11y_show_password)
                }
                Box(Modifier.clickable { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = description)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            singleLine = true,
        )
        Spacer(Modifier.height(18.dp))
        TextField(
            label = stringResource(R.string.screen_login_registration_password_confirm),
            value = confirmationFieldState,
            enabled = !isLoading,
            supportingText = if (passwordConfirmationMismatch) {
                stringResource(R.string.screen_login_registration_password_mismatch)
            } else {
                null
            },
            validity = if (passwordConfirmationMismatch) TextFieldValidity.Invalid else TextFieldValidity.None,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.NewPassword },
            onValueChange = { value ->
                val password = value.sanitize()
                confirmationFieldState = password
                eventSink(LoginPasswordEvents.SetRegistrationPasswordConfirmation(password))
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            singleLine = true,
        )
        RegistrationAgreementSection(
            accepted = form.agreementAccepted,
            enabled = !isLoading,
            onAcceptedChange = { eventSink(LoginPasswordEvents.SetAgreementAccepted(it)) },
        )
        Text(
            text = stringResource(R.string.screen_login_return),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textActionAccent,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 14.dp)
                .clickable(enabled = !isLoading) { eventSink(LoginPasswordEvents.SwitchToLogin) },
        )
    }
}

@Composable
private fun passwordRequirementLabel(requirement: OnboardingPasswordRequirement): String = when (requirement) {
    OnboardingPasswordRequirement.MinimumLength ->
        stringResource(R.string.screen_login_registration_password_requirement_minimum_length)
    OnboardingPasswordRequirement.MaximumLength ->
        stringResource(R.string.screen_login_registration_password_requirement_maximum_length)
    OnboardingPasswordRequirement.UppercaseLetter ->
        stringResource(R.string.screen_login_registration_password_requirement_uppercase)
    OnboardingPasswordRequirement.LowercaseLetter ->
        stringResource(R.string.screen_login_registration_password_requirement_lowercase)
    OnboardingPasswordRequirement.Digit ->
        stringResource(R.string.screen_login_registration_password_requirement_digit)
    OnboardingPasswordRequirement.NoControlCharacters ->
        stringResource(R.string.screen_login_registration_password_requirement_no_control_characters)
}

private data object ClipboardIngressPasteKey

@Composable
private fun Modifier.appendClipboardIngressPasteAction(
    onPaste: (String) -> Unit,
): Modifier {
    val context = LocalContext.current
    val pasteLabel = stringResource(android.R.string.paste)
    val currentOnPaste by rememberUpdatedState(onPaste)
    return appendTextContextMenuComponents {
        item(
            key = ClipboardIngressPasteKey,
            label = pasteLabel,
        ) {
            ClipboardIngress.getPlainText(context)?.let(currentOnPaste)
            close()
        }
    }
}

/**
 * Ensure that the string does not contain any new line characters, which can happen when pasting values.
 */
private fun String.sanitize(): String {
    return replace("\n", "")
}

@Composable
private fun LoginErrorDialog(error: Throwable, onDismiss: () -> Unit) {
    val content = if (error is io.element.android.security.dataprotection.api.RegistrationAgreementRequired) {
        stringResource(R.string.screen_registration_agreement_required)
    } else if (error is io.element.android.security.dataprotection.api.RegistrationAgreementChanged) {
        stringResource(R.string.screen_registration_agreement_changed)
    } else if (error is io.element.android.security.dataprotection.api.RegistrationActivationExpired) {
        stringResource(R.string.screen_registration_verification_expired)
    } else if (error is io.element.android.security.dataprotection.api.RegistrationPending) {
        stringResource(R.string.screen_registration_pending)
    } else if (error is io.element.android.security.dataprotection.api.RegistrationRejected) {
        stringResource(R.string.screen_registration_rejected)
    } else if (error is LoginSecuritySynchronizationException) {
        stringResource(R.string.screen_login_error_security_sync, error.errorCode)
    } else {
        stringResource(loginError(error))
    }
    ErrorDialog(
        title = stringResource(id = CommonStrings.dialog_title_error),
        content = content,
        onSubmit = onDismiss
    )
}

@PreviewsDayNight
@Composable
internal fun LoginPasswordViewPreview(@PreviewParameter(LoginPasswordStateProvider::class) state: LoginPasswordState) = ElementPreview {
    LoginPasswordView(
        state = state,
    )
}
