/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.appconfig.AuthenticationConfig
import io.element.android.features.login.impl.accountprovider.AccountProviderDataSource
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.libraries.matrix.api.auth.AuthErrorCode
import io.element.android.libraries.matrix.api.auth.errorCode
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.OnboardingPasswordPolicy
import io.element.android.security.dataprotection.api.OnboardingUsernameSuggestion
import io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@AssistedInject
class LoginPasswordPresenter(
    @Assisted
    private val initialLogin: String,
    private val authenticationService: MatrixAuthenticationService,
    private val accountProviderDataSource: AccountProviderDataSource,
    private val managementControlService: ManagementControlService,
    private val managementSecurityRefreshService: ManagementSecurityRefreshService,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) : Presenter<LoginPasswordState> {
    @AssistedFactory
    interface Factory {
        fun create(initialLogin: String): LoginPasswordPresenter
    }

    @Composable
    override fun present(): LoginPasswordState {
        val localCoroutineScope = rememberCoroutineScope()
        val loginAction: MutableState<AsyncData<SessionId>> = remember {
            mutableStateOf(AsyncData.Uninitialized)
        }
        val mode = rememberSaveable { mutableStateOf(LoginPasswordMode.LOGIN) }
        val invitationAction: MutableState<AsyncData<VerifiedOnboardingInvitation>> = remember {
            mutableStateOf(AsyncData.Uninitialized)
        }
        val usernameSuggestion: MutableState<AsyncData<OnboardingUsernameSuggestion>> = remember {
            mutableStateOf(AsyncData.Uninitialized)
        }
        val suggestionJob = remember { mutableStateOf<Job?>(null) }

        val formState = rememberSaveable(stateSaver = LoginFormState.savedStateSaver) {
            mutableStateOf(
                LoginFormState(
                    login = initialLogin,
                    password = "",
                )
            )
        }
        val invitationFormState = rememberSaveable(stateSaver = InvitationRegistrationFormState.savedStateSaver) {
            mutableStateOf(InvitationRegistrationFormState.Default)
        }
        val accountProvider by accountProviderDataSource.flow.collectAsState()

        fun scheduleUsernameSuggestion() {
            suggestionJob.value?.cancel()
            val verified = invitationAction.value.dataOrNull()
            val form = invitationFormState.value
            if (verified == null || form.displayName.isBlank() || form.preferredStem.length < 2) {
                usernameSuggestion.value = AsyncData.Uninitialized
                return
            }
            suggestionJob.value = localCoroutineScope.launch {
                delay(USERNAME_SUGGESTION_DEBOUNCE_MILLIS)
                usernameSuggestion.value = AsyncData.Loading(usernameSuggestion.value.dataOrNull())
                runCatching {
                    managementControlService.suggestOnboardingUsername(
                        activationSession = verified.activationSession,
                        displayName = form.displayName,
                        preferredStem = form.preferredStem,
                    )
                }.onSuccess { suggestion ->
                    usernameSuggestion.value = AsyncData.Success(suggestion)
                }.onFailure { failure ->
                    usernameSuggestion.value = AsyncData.Failure(failure)
                }
            }
        }

        fun verifyInvitation(publicId: String? = null, code: String? = null) {
            suggestionJob.value?.cancel()
            usernameSuggestion.value = AsyncData.Uninitialized
            localCoroutineScope.launch {
                invitationAction.value = AsyncData.Loading()
                runCatching {
                    managementControlService.verifyOnboardingInvitation(publicId = publicId, code = code)
                }.onSuccess { verified ->
                    invitationFormState.value = invitationFormState.value.copy(
                        inviteCode = verified.inviteCode,
                        publicId = publicId,
                    )
                    invitationAction.value = AsyncData.Success(verified)
                    scheduleUsernameSuggestion()
                }.onFailure { failure ->
                    invitationAction.value = AsyncData.Failure(failure)
                }
            }
        }

        fun handleEvent(event: LoginPasswordEvents) {
            when (event) {
                is LoginPasswordEvents.SetLogin -> updateFormState(formState) {
                    copy(login = event.login)
                }
                is LoginPasswordEvents.SetPassword -> updateFormState(formState) {
                    copy(password = event.password)
                }
                LoginPasswordEvents.Submit -> {
                    localCoroutineScope.submit(formState.value, loginAction)
                }
                is LoginPasswordEvents.SwitchToInvitationRegistration -> {
                    mode.value = LoginPasswordMode.INVITATION_REGISTER
                    loginAction.value = AsyncData.Uninitialized
                    val publicId = OfficialInvitationLinkParser.publicIdOrNull(event.clipboardText)
                    invitationFormState.value = InvitationRegistrationFormState.Default.copy(
                        publicId = publicId,
                        idempotencyKey = UUID.randomUUID().toString(),
                    )
                    invitationAction.value = AsyncData.Uninitialized
                    usernameSuggestion.value = AsyncData.Uninitialized
                    if (publicId != null) verifyInvitation(publicId = publicId)
                }
                LoginPasswordEvents.SwitchToLogin -> {
                    suggestionJob.value?.cancel()
                    mode.value = LoginPasswordMode.LOGIN
                    invitationFormState.value = InvitationRegistrationFormState.Default
                    invitationAction.value = AsyncData.Uninitialized
                    usernameSuggestion.value = AsyncData.Uninitialized
                    loginAction.value = AsyncData.Uninitialized
                }
                is LoginPasswordEvents.SetInvitationCode -> {
                    val code = event.code.filter(Char::isDigit).take(6)
                    invitationFormState.value = invitationFormState.value.copy(inviteCode = code, publicId = null)
                    invitationAction.value = AsyncData.Uninitialized
                    usernameSuggestion.value = AsyncData.Uninitialized
                    if (code.length == 6) verifyInvitation(code = code)
                }
                is LoginPasswordEvents.SetDisplayName -> {
                    val displayName = event.displayName.replace("\n", "").take(48)
                    invitationFormState.value = invitationFormState.value.copy(
                        displayName = displayName,
                        preferredStem = OnboardingUsernameStemFactory.fromDisplayName(displayName),
                    )
                    usernameSuggestion.value = AsyncData.Uninitialized
                    scheduleUsernameSuggestion()
                }
                is LoginPasswordEvents.SetRegistrationPassword -> {
                    invitationFormState.value = invitationFormState.value.copy(password = event.password.replace("\n", ""))
                }
                is LoginPasswordEvents.SetRegistrationPasswordConfirmation -> {
                    invitationFormState.value = invitationFormState.value.copy(passwordConfirmation = event.password.replace("\n", ""))
                }
                is LoginPasswordEvents.SetAgreementAccepted -> {
                    invitationFormState.value = invitationFormState.value.copy(agreementAccepted = event.accepted)
                }
                LoginPasswordEvents.SubmitInvitationRegistration -> {
                    localCoroutineScope.submitInvitationRegistration(
                        formState = invitationFormState.value,
                        invitation = invitationAction.value.dataOrNull(),
                        suggestion = usernameSuggestion.value.dataOrNull(),
                        loginAction = loginAction,
                        usernameSuggestion = usernameSuggestion,
                    )
                }
                LoginPasswordEvents.ClearError -> loginAction.value = AsyncData.Uninitialized
            }
        }

        return LoginPasswordState(
            accountProvider = accountProvider,
            formState = formState.value,
            loginAction = loginAction.value,
            mode = mode.value,
            invitationFormState = invitationFormState.value,
            invitationAction = invitationAction.value,
            usernameSuggestion = usernameSuggestion.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.submitInvitationRegistration(
        formState: InvitationRegistrationFormState,
        invitation: VerifiedOnboardingInvitation?,
        suggestion: OnboardingUsernameSuggestion?,
        loginAction: MutableState<AsyncData<SessionId>>,
        usernameSuggestion: MutableState<AsyncData<OnboardingUsernameSuggestion>>,
    ) {
        if (!formState.agreementAccepted) {
            loginAction.value = AsyncData.Failure(io.element.android.security.dataprotection.api.RegistrationAgreementRequired())
            return
        }
        if (
            invitation == null || suggestion == null ||
            !OnboardingPasswordPolicy.isSatisfiedBy(formState.password) ||
            formState.password != formState.passwordConfirmation ||
            loginAction.value is AsyncData.Loading
        ) {
            return
        }
        loginAction.value = AsyncData.Loading()
        launch {
            runCatching {
                appOperationalStateCoordinator.perform(
                    lifecycleOperationRequest(FullAppLifecycleId.LC_05, "registration_submit")
                ) {
                    val activation = activateInvitationRegistration(formState, invitation)
                    usernameSuggestion.value = AsyncData.Success(
                        OnboardingUsernameSuggestion(
                            displayName = activation.displayName,
                            loginAccount = activation.loginAccount,
                            matrixUserId = activation.matrixUserId,
                        )
                    )
                    requireLoginSecuritySynchronization()
                    authenticationService.setHomeserver(AuthenticationConfig.YIZHECHAT_HOMESERVER_URL).getOrThrow()
                    authenticationService.login(activation.loginAccount, formState.password).getOrThrow()
                }
            }.onSuccess { sessionId ->
                loginAction.value = AsyncData.Success(sessionId)
            }.onFailure { failure ->
                loginAction.value = AsyncData.Failure(failure)
            }
        }
    }

    internal suspend fun activateInvitationRegistration(
        formState: InvitationRegistrationFormState,
        invitation: VerifiedOnboardingInvitation,
    ): io.element.android.security.dataprotection.api.OnboardingActivation {
        if (!formState.agreementAccepted) throw io.element.android.security.dataprotection.api.RegistrationAgreementRequired()
        val consent = io.element.android.security.dataprotection.api.RegistrationConsent(
            io.element.android.security.dataprotection.api.RegistrationAgreement.VERSION,
            io.element.android.security.dataprotection.api.RegistrationAgreement.SHA256,
        )
        suspend fun activate(session: String) = managementControlService.activateOnboardingInvitation(
            activationSession = session, displayName = formState.displayName, preferredStem = formState.preferredStem,
            password = formState.password, idempotencyKey = formState.idempotencyKey, consent = consent,
        )
        return try {
            activate(invitation.activationSession)
        } catch (expired: io.element.android.security.dataprotection.api.RegistrationActivationExpired) {
            // Only this exact server rejection permits one refresh of the original invitation.
            // No retry on generic 401, transport failure, agreement change or cancellation.
            if (invitation.installationId == null) throw expired
            val refreshed = managementControlService.verifyOnboardingInvitation(
                publicId = formState.publicId, code = if (formState.publicId == null) formState.inviteCode else null,
            )
            if (refreshed.installationId != invitation.installationId || refreshed.inviteCode != invitation.inviteCode ||
                refreshed.inviterUserId != invitation.inviterUserId
            ) throw expired
            activate(refreshed.activationSession)
        }
    }

    private fun CoroutineScope.submit(formState: LoginFormState, loggedInState: MutableState<AsyncData<SessionId>>) = launch {
        loggedInState.value = AsyncData.Loading()
        val login = resolveMatrixLoginAccount(
            value = formState.login,
            userPrefix = AuthenticationConfig.YIZHECHAT_USER_PREFIX,
        )
        runCatching {
            appOperationalStateCoordinator.perform(
                lifecycleOperationRequest(FullAppLifecycleId.LC_05, "matrix_login")
            ) { operationId ->
                requireLoginSecuritySynchronization()
                authenticationService.setHomeserver(AuthenticationConfig.YIZHECHAT_HOMESERVER_URL).getOrThrow()
                authenticationService.login(login, formState.password).onFailure { failure ->
                    recordDefiniteAuthenticationRejection(operationId, failure)
                }.getOrThrow()
            }
        }
            .onSuccess { sessionId ->
                loggedInState.value = AsyncData.Success(sessionId)
            }
            .onFailure { failure ->
                loggedInState.value = AsyncData.Failure(failure)
            }
    }

    internal fun resolveMatrixLoginAccount(value: String, userPrefix: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("@") || trimmed.contains(':') -> trimmed
            trimmed.startsWith(userPrefix) -> trimmed
            else -> userPrefix + trimmed
        }
    }

    internal suspend fun recordDefiniteAuthenticationRejection(operationId: String, failure: Throwable) {
        val code = (failure as? AuthenticationException)?.errorCode
        if (code !in setOf(AuthErrorCode.FORBIDDEN, AuthErrorCode.USER_DEACTIVATED, AuthErrorCode.USER_LOCKED)) return
        // Persist only the original operation and a bounded rejection code. A transport
        // error or cancellation is not evidence that Matrix rejected the credentials.
        // Queue failure separately from APPLIED receipts so old authorities fail closed.
        withContext(NonCancellable) {
            try {
                appOperationalStateCoordinator.enqueueClientReceipts(listOf(LifecycleClientReceipt(
                    operationId = operationId,
                    component = LifecycleComponent.ANDROID_SERVICE,
                    phase = LifecyclePhase.CLIENT_APPLIED,
                    resultCode = "MATRIX_AUTHENTICATION_REJECTED",
                    receiptReference = "android-login-rejected:$operationId",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                )))
            } catch (receiptFailure: Throwable) {
                // Preserve the product error; a failed durable enqueue is not success.
                if (receiptFailure !== failure) failure.addSuppressed(receiptFailure)
            }
        }
    }

    private suspend fun requireLoginSecuritySynchronization() {
        val fresh = runCatching { managementSecurityRefreshService.verifiedOnlineReadiness() }.getOrNull()
        if (fresh?.canStartLc01() == true) return
        val report = managementSecurityRefreshService.refreshSecurityAccess()
        if (report.status !in setOf(ManagementSyncStatus.SYNCHRONIZED, ManagementSyncStatus.DISABLED)) {
            throw LoginSecuritySynchronizationException(
                errorCode = report.errorCode ?: "CONTROL_SYNC_${report.status.name}",
            )
        }
    }

    private fun updateFormState(formState: MutableState<LoginFormState>, updateLambda: LoginFormState.() -> LoginFormState) {
        formState.value = updateLambda(formState.value)
    }

    private companion object {
        const val USERNAME_SUGGESTION_DEBOUNCE_MILLIS = 350L
    }
}

internal class LoginSecuritySynchronizationException(
    val errorCode: String,
) : IllegalStateException("Login security synchronization failed.")
