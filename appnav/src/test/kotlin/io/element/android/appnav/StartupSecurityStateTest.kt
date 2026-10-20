/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import com.google.common.truth.Truth.assertThat
import io.element.android.features.ftue.api.state.FtueState
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import org.junit.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class StartupSecurityStateTest {
    @Test
    fun `verification completion keeps the recovery child until its callback but revocation changes the root`() = runTest {
        val recovering = StartupSecurityState(securityReady = false, setupComplete = false,
            ftueState = FtueState.Unknown, verificationStatus = SessionVerifiedStatus.Unknown,
            coreReady = false, sessionInitialized = false)
        val verified = recovering.copy(verificationStatus = SessionVerifiedStatus.Verified)
        val revoked = verified.copy(failureReasonCode = "DEVICE_REVOKED")
        val denied = revoked.copy(failureReasonCode = "POLICY_REJECTED")
        val events = flowOf(recovering, verified, verified.copy(ftueState = FtueState.Complete), revoked, denied)
            .distinctStartupNavigation().toList()
        assertThat(events).containsExactly(recovering, revoked, denied).inOrder()
    }

    @Test fun `retained original home permits background receipts but never bypasses known security gates`() {
        val state = StartupSecurityState(securityReady = true, setupComplete = true,
            ftueState = FtueState.Complete, verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = false, retainedHomeReady = true,
            coreReadyEnforcement = CoreReadyEnforcement.REQUIRED)
        assertThat(state.destination()).isEqualTo(StartupDestination.HOME)
        assertThat(state.copy(retainedHomeReady = false).destination()).isEqualTo(StartupDestination.WAIT)
        assertThat(state.copy(securityReady = false).destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
        assertThat(state.copy(setupComplete = false).destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
        assertThat(state.copy(recoveryRequired = true).destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
        assertThat(state.copy(verificationStatus = SessionVerifiedStatus.Unknown).destination()).isEqualTo(StartupDestination.WAIT)
        assertThat(state.copy(failureReasonCode = "POLICY_REVOKED").destination()).isEqualTo(StartupDestination.ERROR)
        assertThat(state.copy(deletionRecoveryRequired = true).destination()).isEqualTo(StartupDestination.DELETION_RECOVERY)
    }

    @Test fun `signed CORE alone cannot expose home before session services and receipts complete`() {
        val state = StartupSecurityState(securityReady = true, setupComplete = true,
            ftueState = FtueState.Complete, verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = false, coreReadyEnforcement = CoreReadyEnforcement.REQUIRED)
        assertThat(state.destination()).isEqualTo(StartupDestination.WAIT)
        assertThat(state.copy(sessionInitialized = true).destination()).isEqualTo(StartupDestination.HOME)
        assertThat(state.copy(failureReasonCode = "POST_LOGIN_STARTUP_FAILED").destination()).isEqualTo(StartupDestination.ERROR)
    }
    @Test fun `cached offline CORE also requires real local session services before home`() {
        val state = StartupSecurityState(securityReady = true, setupComplete = true,
            ftueState = FtueState.Unknown, verificationStatus = SessionVerifiedStatus.Unknown,
            coreReady = true, sessionInitialized = false, offlineCoreRestored = true,
            coreReadyEnforcement = CoreReadyEnforcement.REQUIRED)
        assertThat(state.destination()).isEqualTo(StartupDestination.WAIT)
        assertThat(state.copy(sessionInitialized = true).destination()).isEqualTo(StartupDestination.HOME)
        assertThat(state.copy(deletionRecoveryRequired = true).destination()).isEqualTo(StartupDestination.DELETION_RECOVERY)
    }

    @Test
    fun `durable owned recovery blocks SDK verified and cached offline home but preserves deletion precedence`() {
        val state = StartupSecurityState(securityReady = true, setupComplete = true,
            ftueState = FtueState.Unknown, verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = true, offlineCoreRestored = true, recoveryRequired = true)
        assertThat(state.destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
        assertThat(state.copy(deletionRecoveryRequired = true).destination()).isEqualTo(StartupDestination.DELETION_RECOVERY)
        assertThat(state.copy(failureReasonCode = "SECURITY_REJECTED").destination()).isEqualTo(StartupDestination.ERROR)
        assertThat(state.copy(recoveryRequired = false).destination()).isEqualTo(StartupDestination.HOME)
    }

    @Test
    fun `authenticated offline CORE restoration does not wait for online SDK verification state`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Unknown,
            verificationStatus = SessionVerifiedStatus.Unknown,
            coreReady = true, sessionInitialized = true,
            coreReadyEnforcement = CoreReadyEnforcement.REQUIRED,
            offlineCoreRestored = true
        )
        assertThat(state.destination()).isEqualTo(StartupDestination.HOME)
        assertThat(state.copy(coreReady = false).destination()).isEqualTo(StartupDestination.WAIT)
        assertThat(state.copy(failureReasonCode = "SECURITY_POLICY_REJECTED").destination()).isEqualTo(StartupDestination.ERROR)
        assertThat(state.copy(securityReady = false).destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
    }

    @Test
    fun `pending permanent deletion opens deletion recovery ahead of ordinary startup failures`() {
        val state = StartupSecurityState(
            securityReady = false,
            setupComplete = false,
            ftueState = FtueState.Unknown,
            verificationStatus = SessionVerifiedStatus.Unknown,
            coreReady = false, sessionInitialized = true,
            failureReasonCode = "POST_LOGIN_STARTUP_FAILED",
            deletionRecoveryRequired = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.DELETION_RECOVERY)
    }

    @Test
    fun `administrator approval cannot bypass unfinished unified setup`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = false,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
    }

    @Test
    fun `unified setup cannot bypass administrator approval`() {
        val state = StartupSecurityState(
            securityReady = false,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
    }

    @Test
    fun `verified session still waits for all onboarding requirements`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Incomplete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.SECURITY_SETUP)
    }

    @Test
    fun `home requires every security gate`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = true, sessionInitialized = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.HOME)
    }

    @Test
    fun `shadow core activation preserves the established home path`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = true,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.HOME)
    }

    @Test
    fun `scoped enforcement waits for signed account core activation`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = true,
            coreReadyEnforcement = CoreReadyEnforcement.REQUIRED,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.WAIT)
    }

    @Test
    fun `reactivation classification completes before opening an otherwise ready session`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = true,
            coreReadyEnforcement = CoreReadyEnforcement.CHECKING,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.WAIT)
    }

    @Test
    fun `fresh login keeps established home path after local classification`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = true,
            coreReadyEnforcement = CoreReadyEnforcement.DISABLED,
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.HOME)
    }

    @Test
    fun `startup failure is visible instead of remaining behind the waiting destination`() {
        val state = StartupSecurityState(
            securityReady = true,
            setupComplete = true,
            ftueState = FtueState.Complete,
            verificationStatus = SessionVerifiedStatus.Verified,
            coreReady = false, sessionInitialized = true,
            coreReadyEnforcement = CoreReadyEnforcement.REQUIRED,
            failureReasonCode = "CORE_READY_STARTUP_TIMEOUT",
        )

        assertThat(state.destination()).isEqualTo(StartupDestination.ERROR)
    }
}
