/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.ftue.impl

import android.Manifest
import android.os.Build
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.ftue.api.state.FtueState
import io.element.android.features.ftue.impl.state.DefaultFtueService
import io.element.android.features.ftue.impl.state.FtueStep
import io.element.android.features.ftue.impl.state.InternalFtueState
import io.element.android.libraries.matrix.api.verification.SessionVerificationService
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.test.verification.FakeSessionVerificationService
import io.element.android.libraries.permissions.api.PermissionStateProvider
import io.element.android.libraries.permissions.test.FakePermissionStateProvider
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.services.toolbox.test.sdk.FakeBuildVersionSdkIntProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultFtueServiceTest {
    @Test
    fun `given any check being false and session verification state being loaded, FtueState is Incomplete`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService().apply {
            emitVerifiedStatus(SessionVerifiedStatus.Unknown)
        }
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
        )

        service.state.test {
            // Verification state is unknown, we don't display the flow yet
            assertThat(awaitItem()).isEqualTo(FtueState.Unknown)

            // Verification state is known, we should display the flow if any check is false
            sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.NotVerified)
            assertThat(awaitItem()).isEqualTo(FtueState.Incomplete)
        }
    }

    @Test
    fun `given all checks being true, FtueState is Complete`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
        )

        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)
        service.updateFtueStep()
        service.state.test {
            assertThat(awaitItem()).isEqualTo(FtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(FtueState.Complete)
        }
    }

    @Test
    fun `optional preferences do not keep startup incomplete`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
        )

        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)
        service.updateFtueStep()
        service.state.test {
            assertThat(awaitItem()).isEqualTo(FtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(FtueState.Complete)
        }
    }

    @Test
    fun `traverse flow`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService().apply {
            emitVerifiedStatus(SessionVerifiedStatus.NotVerified)
        }
        val permissionStateProvider = FakePermissionStateProvider(permissionGranted = false)
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
            permissionStateProvider = permissionStateProvider,
        )

        service.ftueStepStateFlow.test {
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Unknown)
            // Session verification
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Incomplete(FtueStep.SessionVerification))
            sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)
            // User completes verification
            service.onUserCompletedSessionVerification()
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Complete)
        }
    }

    @Test
    fun `if a check for a step is true, start from the next one`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
        )

        // A verified session is enough for FTUE; policy-required app lock is enforced by startup setup.
        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)

        service.ftueStepStateFlow.test {
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Complete)
        }
    }

    @Test
    fun `undecided notification permission does not block the chat list`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
            permissionStateProvider = FakePermissionStateProvider(permissionGranted = false),
        )

        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)

        service.ftueStepStateFlow.test {
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Complete)
        }
    }

    @Test
    fun `explicit notification denial is remembered and does not trap startup`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val permissionStateProvider = FakePermissionStateProvider(permissionGranted = false)
        permissionStateProvider.setPermissionDenied(Manifest.permission.POST_NOTIFICATIONS, true)
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
            permissionStateProvider = permissionStateProvider,
        )

        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)

        service.ftueStepStateFlow.test {
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Complete)
        }
    }

    @Test
    fun `android 12 does not require notification runtime permission`() = runTest {
        val sessionVerificationService = FakeSessionVerificationService()
        val service = createDefaultFtueService(
            sessionVerificationService = sessionVerificationService,
            permissionStateProvider = FakePermissionStateProvider(permissionGranted = false),
            sdkIntVersion = Build.VERSION_CODES.S_V2,
        )

        sessionVerificationService.emitVerifiedStatus(SessionVerifiedStatus.Verified)

        service.ftueStepStateFlow.test {
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Unknown)
            assertThat(awaitItem()).isEqualTo(InternalFtueState.Complete)
        }
    }
}

internal fun TestScope.createDefaultFtueService(
    sessionVerificationService: SessionVerificationService = FakeSessionVerificationService(),
    permissionStateProvider: PermissionStateProvider = FakePermissionStateProvider(permissionGranted = true),
    sessionPreferencesStore: SessionPreferencesStore = InMemorySessionPreferencesStore(),
    sdkIntVersion: Int = Build.VERSION_CODES.TIRAMISU,
) = DefaultFtueService(
    sdkVersionProvider = FakeBuildVersionSdkIntProvider(sdkIntVersion),
    sessionCoroutineScope = backgroundScope,
    permissionStateProvider = permissionStateProvider,
    sessionVerificationService = sessionVerificationService,
    sessionPreferencesStore = sessionPreferencesStore,
)
