/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.AuthenticationConfig
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.features.login.impl.accountprovider.AccountProviderDataSource
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_PASSWORD
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.A_USER_NAME_2
import io.element.android.libraries.matrix.test.auth.FakeMatrixAuthenticationService
import io.element.android.libraries.matrix.test.auth.aMatrixHomeServerDetails
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSecurityRefreshService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LoginPasswordPresenterTest {
    @Test
    fun `definite credential rejection records only the original service failure intent`() = runTest {
        listOf("M_FORBIDDEN", "M_USER_DEACTIVATED", "M_USER_LOCKED").forEach { code ->
            val coordinator = FakeAppOperationalStateCoordinator()
            val authentication = FakeMatrixAuthenticationService(setHomeserverResult = { Result.success(aMatrixHomeServerDetails()) })
            val rejection = AuthenticationException.Generic(code)
            authentication.givenLoginError(rejection)
            createLoginPasswordPresenter(authenticationService = authentication, appOperationalStateCoordinator = coordinator).test {
                val state = awaitItem()
                state.eventSink(LoginPasswordEvents.SetLogin(A_USER_NAME))
                state.eventSink(LoginPasswordEvents.SetPassword(A_PASSWORD))
                skipItems(1)
                awaitItem().eventSink(LoginPasswordEvents.Submit)
                assertThat(awaitItem().loginAction).isInstanceOf(AsyncData.Loading::class.java)
                val error = (awaitItem().loginAction as AsyncData.Failure<SessionId>).error
                assertThat(error).isInstanceOf(AuthenticationException.Generic::class.java)
                assertThat(error).hasMessageThat().isEqualTo(code)
            }
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.clientReceipts).hasSize(1)
            val receipt = coordinator.clientReceipts.single()
            assertThat(receipt.operationId).isEqualTo(coordinator.startedRequests.single().operationId)
            assertThat(receipt.component).isEqualTo(LifecycleComponent.ANDROID_SERVICE)
            assertThat(receipt.resultCode).isEqualTo("MATRIX_AUTHENTICATION_REJECTED")
        }
    }

    @Test
    fun `unknown network and cancellation failures never become credential rejection receipts`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createLoginPasswordPresenter(appOperationalStateCoordinator = coordinator)
        listOf(
            AuthenticationException.Generic("M_UNKNOWN"),
            AuthenticationException.ServerUnreachable("Unavailable"),
            java.io.IOException("Connection lost"),
            kotlinx.coroutines.CancellationException("Cancelled"),
        ).forEach { presenter.recordDefiniteAuthenticationRejection("original-login-attempt", it) }
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `durable enqueue failure preserves authentication error without declaring completion`() = runTest {
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val storageFailure = java.io.IOException("Private receipt journal unavailable")
        coEvery { coordinator.enqueueClientReceipts(any()) } throws storageFailure
        val rejection = AuthenticationException.Generic("M_FORBIDDEN")
        createLoginPasswordPresenter(appOperationalStateCoordinator = coordinator)
            .recordDefiniteAuthenticationRejection("original-login-attempt", rejection)
        assertThat(rejection.suppressed.toList()).containsExactly(storageFailure)
        coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
    }

    @Test
    fun `process saved state keeps the login intent but strips authentication secrets`() {
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val login = LoginFormState(A_USER_NAME, A_PASSWORD)
        val savedLogin = with(LoginFormState.savedStateSaver) { scope.save(login) }
        assertThat(savedLogin).isEqualTo(login.copy(password = ""))
        assertThat(LoginFormState.savedStateSaver.restore(login)?.password).isEmpty()
        val registration = InvitationRegistrationFormState.Default.copy(
            displayName = "Test",
            idempotencyKey = "registration-original-intent",
            password = A_PASSWORD,
            passwordConfirmation = A_PASSWORD
        )
        val savedRegistration = with(InvitationRegistrationFormState.savedStateSaver) { scope.save(registration) }
        assertThat(savedRegistration).isEqualTo(registration.copy(password = "", passwordConfirmation = ""))
        assertThat(InvitationRegistrationFormState.savedStateSaver.restore(registration)?.passwordConfirmation).isEmpty()
    }

    @Test
    fun `fresh verified online readiness avoids a duplicate management round trip before password authentication`() = runTest {
        val refreshService = mockk<ManagementSecurityRefreshService>()
        every { refreshService.verifiedOnlineReadiness() } returns io.element.android.security.dataprotection.api.StartupSecurityReadiness(
            state = io.element.android.security.dataprotection.api.StartupSecurityReadinessState.READY_FOR_LC01,
            installationId = "installation-login-test-0001",
            observedAtEpochMillis = System.currentTimeMillis(),
            expiresAtEpochMillis = System.currentTimeMillis() + 30_000,
            policyVersion = 1,
        )
        createLoginPasswordPresenter(
            managementSecurityRefreshService = refreshService,
            authenticationService = FakeMatrixAuthenticationService(
                setHomeserverResult = { Result.success(aMatrixHomeServerDetails()) },
            ),
        ).test {
            val initial = awaitItem()
            initial.eventSink(LoginPasswordEvents.SetLogin(A_USER_NAME))
            initial.eventSink(LoginPasswordEvents.SetPassword(A_PASSWORD))
            skipItems(1)
            awaitItem().eventSink(LoginPasswordEvents.Submit)
            assertThat(awaitItem().loginAction).isInstanceOf(AsyncData.Loading::class.java)
            assertThat(awaitItem().loginAction).isEqualTo(AsyncData.Success(A_SESSION_ID))
        }
        coVerify(exactly = 0) { refreshService.refreshSecurityAccess() }
    }

    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        createLoginPasswordPresenter().test {
            val initialState = awaitItem()
            assertThat(initialState.accountProvider.url).isEqualTo(AuthenticationConfig.MATRIX_ORG_URL)
            assertThat(initialState.formState).isEqualTo(LoginFormState.Default)
            assertThat(initialState.loginAction).isEqualTo(AsyncData.Uninitialized)
            assertThat(initialState.submitEnabled).isFalse()
        }
    }

    @Test
    fun `present - initial login is in the first state and can be modified`() = runTest {
        createLoginPasswordPresenter(
            initialLogin = A_USER_NAME,
        ).test {
            val initialState = awaitItem()
            assertThat(initialState.formState.login).isEqualTo(A_USER_NAME)
            // Login can be changed
            initialState.eventSink.invoke(LoginPasswordEvents.SetLogin(A_USER_NAME_2))
            val loginChangedState = awaitItem()
            assertThat(loginChangedState.formState.login).isEqualTo(A_USER_NAME_2)
        }
    }

    @Test
    fun `present - enter login and password`() = runTest {
        val authenticationService = FakeMatrixAuthenticationService(
            setHomeserverResult = {
                Result.success(aMatrixHomeServerDetails())
            },
        )
        createLoginPasswordPresenter(
            authenticationService = authenticationService,
        ).test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LoginPasswordEvents.SetLogin(A_USER_NAME))
            val loginState = awaitItem()
            assertThat(loginState.formState).isEqualTo(LoginFormState(login = A_USER_NAME, password = ""))
            assertThat(loginState.submitEnabled).isFalse()
            initialState.eventSink.invoke(LoginPasswordEvents.SetPassword(A_PASSWORD))
            val loginAndPasswordState = awaitItem()
            assertThat(loginAndPasswordState.formState).isEqualTo(LoginFormState(login = A_USER_NAME, password = A_PASSWORD))
            assertThat(loginAndPasswordState.submitEnabled).isTrue()
        }
    }

    @Test
    fun `present - submit`() = runTest {
        val authenticationService = FakeMatrixAuthenticationService(
            setHomeserverResult = {
                Result.success(aMatrixHomeServerDetails())
            },
        )
        createLoginPasswordPresenter(
            authenticationService = authenticationService,
        ).test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LoginPasswordEvents.SetLogin(A_USER_NAME))
            initialState.eventSink.invoke(LoginPasswordEvents.SetPassword(A_PASSWORD))
            skipItems(1)
            val loginAndPasswordState = awaitItem()
            loginAndPasswordState.eventSink.invoke(LoginPasswordEvents.Submit)
            val submitState = awaitItem()
            assertThat(submitState.loginAction).isInstanceOf(AsyncData.Loading::class.java)
            val loggedInState = awaitItem()
            assertThat(loggedInState.loginAction).isEqualTo(AsyncData.Success(A_SESSION_ID))
        }
    }

    @Test
    fun `present - submit with error`() = runTest {
        val authenticationService = FakeMatrixAuthenticationService(
            setHomeserverResult = {
                Result.success(aMatrixHomeServerDetails())
            },
        )
        createLoginPasswordPresenter(
            authenticationService = authenticationService,
        ).test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LoginPasswordEvents.SetLogin(A_USER_NAME))
            initialState.eventSink.invoke(LoginPasswordEvents.SetPassword(A_PASSWORD))
            skipItems(1)
            val loginAndPasswordState = awaitItem()
            authenticationService.givenLoginError(AN_EXCEPTION)
            loginAndPasswordState.eventSink.invoke(LoginPasswordEvents.Submit)
            val submitState = awaitItem()
            assertThat(submitState.loginAction).isInstanceOf(AsyncData.Loading::class.java)
            val loggedInState = awaitItem()
            val failure = loggedInState.loginAction as AsyncData.Failure<SessionId>
            assertThat(failure.error).hasMessageThat().isEqualTo(AN_EXCEPTION.message)
        }
    }

    @Test
    fun `resolve login - full Matrix user id is preserved`() {
        val presenter = createLoginPasswordPresenter()

        assertThat(
            presenter.resolveMatrixLoginAccount(
                value = " @next01:matrix-next.yizhesy.cn ",
                userPrefix = "yizhe",
            )
        ).isEqualTo("@next01:matrix-next.yizhesy.cn")
    }

    @Test
    fun `resolve login - registered localpart with prefix is preserved`() {
        val presenter = createLoginPasswordPresenter()

        assertThat(
            presenter.resolveMatrixLoginAccount(
                value = " yizhenext01 ",
                userPrefix = "yizhe",
            )
        ).isEqualTo("yizhenext01")
    }

    @Test
    fun `resolve login - invitation stem receives prefix once`() {
        val presenter = createLoginPasswordPresenter()

        assertThat(
            presenter.resolveMatrixLoginAccount(
                value = " next01 ",
                userPrefix = "yizhe",
            )
        ).isEqualTo("yizhenext01")
    }

    @Test
    fun `Next account prefix matches control-plane onboarding localpart contract`() {
        assertThat(AuthenticationConfig.YIZHECHAT_USER_PREFIX).isEqualTo("yizhe")
    }

    @Test
    fun `present - submit stops before Matrix login when device security synchronization is rejected`() = runTest {
        val refreshService = mockk<ManagementSecurityRefreshService>()
        coEvery { refreshService.refreshSecurityAccess() } returns ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "CONTROL_SYNC_TRUST_DECISION_REJECTED",
        )
        createLoginPasswordPresenter(
            managementSecurityRefreshService = refreshService,
        ).test {
            val initialState = awaitItem()
            initialState.eventSink(LoginPasswordEvents.SetLogin(A_USER_NAME))
            initialState.eventSink(LoginPasswordEvents.SetPassword(A_PASSWORD))
            skipItems(1)
            val loginAndPasswordState = awaitItem()
            loginAndPasswordState.eventSink(LoginPasswordEvents.Submit)
            val failure = awaitItem().loginAction as AsyncData.Failure<SessionId>
            assertThat(failure.error).isInstanceOf(LoginSecuritySynchronizationException::class.java)
            assertThat((failure.error as LoginSecuritySynchronizationException).errorCode)
                .isEqualTo("CONTROL_SYNC_TRUST_DECISION_REJECTED")
        }
    }

    @Test
    fun `present - clear error`() = runTest {
        val authenticationService = FakeMatrixAuthenticationService(
            setHomeserverResult = {
                Result.success(aMatrixHomeServerDetails())
            },
        )
        createLoginPasswordPresenter(
            authenticationService = authenticationService,
        ).test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LoginPasswordEvents.SetLogin(A_USER_NAME))
            initialState.eventSink.invoke(LoginPasswordEvents.SetPassword(A_PASSWORD))
            skipItems(1)
            val loginAndPasswordState = awaitItem()
            authenticationService.givenLoginError(AN_EXCEPTION)
            loginAndPasswordState.eventSink.invoke(LoginPasswordEvents.Submit)
            val submitState = awaitItem()
            assertThat(submitState.loginAction).isInstanceOf(AsyncData.Loading::class.java)
            val loggedInState = awaitItem()
            // Check an error was returned
            val failure = loggedInState.loginAction as AsyncData.Failure<SessionId>
            assertThat(failure.error).hasMessageThat().isEqualTo(AN_EXCEPTION.message)
            // Assert the error is then cleared
            loggedInState.eventSink(LoginPasswordEvents.ClearError)
            val clearedState = awaitItem()
            assertThat(clearedState.loginAction).isEqualTo(AsyncData.Uninitialized)
        }
    }

    @Test
    fun `only explicit expiry refreshes original invitation once with unchanged input consent and attempt`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = registrationInvitation()
        val refreshed = original.copy(activationSession = "refreshed-original-session")
        val form = registrationForm()
        val activation = mockk<io.element.android.security.dataprotection.api.OnboardingActivation>()
        val sessions = mutableListOf<String>()
        coEvery { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) } coAnswers {
            sessions += firstArg<String>()
            assertThat(arg<String>(1)).isEqualTo(form.displayName)
            assertThat(arg<String>(2)).isEqualTo(form.preferredStem)
            assertThat(arg<String>(3)).isEqualTo(form.password)
            assertThat(arg<String>(4)).isEqualTo(form.idempotencyKey)
            assertThat(arg<io.element.android.security.dataprotection.api.RegistrationConsent>(5)).isEqualTo(
                io.element.android.security.dataprotection.api.RegistrationConsent(
                    io.element.android.security.dataprotection.api.RegistrationAgreement.VERSION,
                    io.element.android.security.dataprotection.api.RegistrationAgreement.SHA256,
                )
            )
            if (sessions.size == 1) throw io.element.android.security.dataprotection.api.RegistrationActivationExpired()
            activation
        }
        coEvery { service.verifyOnboardingInvitation(null, form.inviteCode) } returns refreshed
        val presenter = createLoginPasswordPresenter(managementControlService = service)
        assertThat(presenter.activateInvitationRegistration(form, original)).isSameInstanceAs(activation)
        assertThat(sessions).containsExactly(original.activationSession, refreshed.activationSession).inOrder()
        io.mockk.coVerify(exactly = 1) { service.verifyOnboardingInvitation(null, form.inviteCode) }
    }

    @Test
    fun `network generic unauthorized agreement change and cancellation never refresh registration`() = runTest {
        listOf(java.io.IOException("network unavailable"), IllegalStateException("HTTP 401"),
            io.element.android.security.dataprotection.api.RegistrationAgreementChanged(),
            kotlinx.coroutines.CancellationException("cancelled"),
        ).forEach { failure ->
            val service = mockk<ManagementControlService>()
            coEvery { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) } throws failure
            val presenter = createLoginPasswordPresenter(managementControlService = service)
            assertThat(runCatching { presenter.activateInvitationRegistration(registrationForm(), registrationInvitation()) }
                .exceptionOrNull()).isSameInstanceAs(failure)
            io.mockk.coVerify(exactly = 1) { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) }
            io.mockk.coVerify(exactly = 0) { service.verifyOnboardingInvitation(any(), any()) }
        }
    }

    @Test
    fun `refreshed installation invitation or inviter mismatch never submits another activation`() = runTest {
        val original = registrationInvitation()
        listOf(original.copy(installationId = "installation-another-0001"), original.copy(inviteCode = "654321"),
            original.copy(inviterUserId = "@another:yizhesy.cn"),
        ).forEach { refreshed ->
            val service = mockk<ManagementControlService>()
            coEvery { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) } throws
                io.element.android.security.dataprotection.api.RegistrationActivationExpired()
            coEvery { service.verifyOnboardingInvitation(any(), any()) } returns refreshed
            val presenter = createLoginPasswordPresenter(managementControlService = service)
            assertThat(runCatching { presenter.activateInvitationRegistration(registrationForm(), original) }.isFailure).isTrue()
            io.mockk.coVerify(exactly = 1) { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `a second explicit expiry is returned without another refresh loop`() = runTest {
        val service = mockk<ManagementControlService>()
        coEvery { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) } throws
            io.element.android.security.dataprotection.api.RegistrationActivationExpired()
        coEvery { service.verifyOnboardingInvitation(any(), any()) } returns registrationInvitation().copy(activationSession = "fresh-session")
        val presenter = createLoginPasswordPresenter(managementControlService = service)
        assertThat(runCatching { presenter.activateInvitationRegistration(registrationForm(), registrationInvitation()) }.isFailure).isTrue()
        io.mockk.coVerify(exactly = 2) { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 1) { service.verifyOnboardingInvitation(any(), any()) }
    }

    @Test
    fun `registration without agreement never starts activation or refresh`() = runTest {
        val service = mockk<ManagementControlService>()
        val presenter = createLoginPasswordPresenter(managementControlService = service)
        assertThat(runCatching {
            presenter.activateInvitationRegistration(registrationForm().copy(agreementAccepted = false), registrationInvitation())
        }.isFailure).isTrue()
        io.mockk.coVerify(exactly = 0) { service.activateOnboardingInvitation(any(), any(), any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { service.verifyOnboardingInvitation(any(), any()) }
    }

    private fun registrationInvitation() = io.element.android.security.dataprotection.api.VerifiedOnboardingInvitation(
        activationSession = "original-session", inviteCode = "123456", inviterUserId = "@qa:yizhesy.cn",
        inviterDisplayName = "QA", expiresAtEpochMillis = Long.MAX_VALUE, installationId = "installation-original-0001",
    )

    private fun registrationForm() = InvitationRegistrationFormState.Default.copy(
        inviteCode = "123456", displayName = "QA", preferredStem = "qa", password = "Test-password-2026",
        passwordConfirmation = "Test-password-2026", idempotencyKey = "original-registration-attempt-0001", agreementAccepted = true,
    )

    private fun createLoginPasswordPresenter(
        initialLogin: String = "",
        authenticationService: FakeMatrixAuthenticationService = FakeMatrixAuthenticationService(),
        accountProviderDataSource: AccountProviderDataSource = AccountProviderDataSource(FakeEnterpriseService()),
        managementControlService: ManagementControlService = mockk(relaxed = true),
        managementSecurityRefreshService: ManagementSecurityRefreshService = object : ManagementSecurityRefreshService {
            override suspend fun refreshSecurityAccess() = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED)
        },
        appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    ): LoginPasswordPresenter = LoginPasswordPresenter(
        initialLogin = initialLogin,
        authenticationService = authenticationService,
        accountProviderDataSource = accountProviderDataSource,
        managementControlService = managementControlService,
        managementSecurityRefreshService = managementSecurityRefreshService,
        appOperationalStateCoordinator = appOperationalStateCoordinator,
    )
}
