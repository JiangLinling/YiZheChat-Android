/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.impl.core.SdkBackgroundTaskError
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.security.securestorage.test.FakeVaultKeyService
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.test.FakeAnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import io.mockk.mockk
import io.mockk.every
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.DeviceId
import kotlinx.coroutines.test.advanceUntilIdle
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason

class RustClientSessionDelegateTest {
    @Test
    fun `old SDK auth error cannot invalidate replacement login`() = runTest {
        val old = aSessionData()
        val replacement = old.copy(deviceId = "NEW", sessionPath = "new-files", cachePath = "new-cache", isTokenValid = true)
        val store = InMemorySessionStore(initialList = listOf(replacement))
        val client = mockk<RustMatrixClient>(relaxed = true)
        every { client.sessionId } returns UserId(old.userId)
        every { client.deviceId } returns DeviceId(old.deviceId)
        val sut = aRustClientSessionDelegate(store)
        sut.bindClient(client)
        sut.didReceiveAuthError(false)
        advanceUntilIdle()
        assertThat(store.getSession(old.userId)).isEqualTo(replacement)
    }

    @Test
    fun `old SDK refresh cannot replace a new Matrix login`() = runTest {
        val old = aSessionData()
        val replacement = old.copy(deviceId = "NEW", sessionPath = "new-files", cachePath = "new-cache", isTokenValid = true)
        val store = InMemorySessionStore(initialList = listOf(replacement))
        aRustClientSessionDelegate(store).saveSessionInKeychain(aRustSession(accessToken = "late", refreshToken = "late-refresh"))
        assertThat(store.getSession(old.userId)).isEqualTo(replacement)
    }

    @Test
    fun `saveSessionInKeychain should update the store`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    deviceId = A_DEVICE_ID.value,
                    isTokenValid = true,
                    accessToken = "anAccessToken",
                    refreshToken = "aRefreshToken",
                )
            )
        )
        val sut = aRustClientSessionDelegate(sessionStore)
        sut.saveSessionInKeychain(
            aRustSession(
                accessToken = "at",
                refreshToken = "rt",
            )
        )
        val result = sessionStore.getLatestSession()
        assertThat(result!!.accessToken).startsWith("yzvault1:")
        assertThat(result.refreshToken).startsWith("yzvault1:")
    }

    @Test
    fun `onBackgroundTaskErrorReport reports the error to analytics if recoverable`() = runTest {
        val analyticsService = FakeAnalyticsService(isEnabled = true)
        val sut = aRustClientSessionDelegate(analyticsService = analyticsService)
        sut.onBackgroundTaskErrorReport("Crasher", BackgroundTaskFailureReason.EarlyTermination)
        sut.onBackgroundTaskErrorReport("Crasher", BackgroundTaskFailureReason.Error("BOOM"))

        assertThat(analyticsService.trackedErrors).hasSize(2)
        assertThat(analyticsService.trackedErrors[0].message).isEqualTo("SDK background task 'Crasher' failure: \nEarly termination")
        assertThat(analyticsService.trackedErrors[1].message).isEqualTo("SDK background task 'Crasher' failure: \nError: BOOM")
    }

    @Test(expected = SdkBackgroundTaskError::class)
    fun `onBackgroundTaskErrorReport reports the error to analytics and throws it if it's a panic`() = runTest {
        val analyticsService = FakeAnalyticsService(isEnabled = true)
        val sut = aRustClientSessionDelegate(analyticsService = analyticsService)
        sut.onBackgroundTaskErrorReport("Crasher", BackgroundTaskFailureReason.Panic("BOOM", "Stacktrace"))

        assertThat(analyticsService.trackedErrors).hasSize(1)
        assertThat(analyticsService.trackedErrors[0].message)
            .isEqualTo("SDK background task 'Crasher' failure: \nPanic (unrecoverable): BOOM\nStacktrace")
    }
}

fun TestScope.aRustClientSessionDelegate(
    sessionStore: SessionStore = InMemorySessionStore(),
    analyticsService: AnalyticsService = FakeAnalyticsService(),
    vaultKeyService: FakeVaultKeyService = FakeVaultKeyService(),
    appCoroutineScope: CoroutineScope = this,
) = RustClientSessionDelegate(
    sessionStore = sessionStore,
    appCoroutineScope = appCoroutineScope,
    analyticsService = analyticsService,
    accountSessionSecretVault = AccountSessionSecretVault(vaultKeyService, FakeFeatureFlagService()),
)
