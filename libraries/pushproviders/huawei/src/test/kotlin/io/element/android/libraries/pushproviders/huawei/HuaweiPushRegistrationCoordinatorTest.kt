@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HuaweiPushRegistrationCoordinatorTest {
    @Test
    fun `registers at startup and again when first session is created`() = runTest {
        val sessionObserver = FakeSessionObserver()
        val tokenSource = HuaweiPushTokenSource { Result.success("opaque-token") }
        val tokenHandler = mockk<HuaweiPushNewTokenHandler>()
        coEvery { tokenHandler.handle("opaque-token") } returns Result.success(Unit)
        val coordinator = HuaweiPushRegistrationCoordinator(
            tokenSource = tokenSource,
            newTokenHandler = tokenHandler,
            sessionObserver = sessionObserver,
            diagnostics = mockk(relaxed = true),
            coroutineScope = this,
        )

        coordinator.start()
        advanceUntilIdle()
        sessionObserver.onSessionCreated("@user:example.org")
        advanceUntilIdle()

        coVerify(exactly = 2) { tokenHandler.handle("opaque-token") }
    }

    @Test
    fun `start is idempotent and does not install duplicate session listeners`() = runTest {
        val sessionObserver = FakeSessionObserver()
        val tokenSource = HuaweiPushTokenSource { Result.success("opaque-token") }
        val tokenHandler = mockk<HuaweiPushNewTokenHandler>()
        coEvery { tokenHandler.handle(any()) } returns Result.success(Unit)
        val coordinator = HuaweiPushRegistrationCoordinator(
            tokenSource = tokenSource,
            newTokenHandler = tokenHandler,
            sessionObserver = sessionObserver,
            diagnostics = mockk(relaxed = true),
            coroutineScope = this,
        )

        coordinator.start()
        coordinator.start()
        advanceUntilIdle()
        sessionObserver.onSessionCreated("@user:example.org")
        advanceUntilIdle()

        coVerify(exactly = 2) { tokenHandler.handle("opaque-token") }
    }

    @Test
    fun `transient token failure is retried without changing push provider`() = runTest {
        var tokenRequests = 0
        val tokenSource = HuaweiPushTokenSource {
            tokenRequests += 1
            if (tokenRequests == 1) Result.failure(IllegalStateException("temporary"))
            else Result.success("opaque-token")
        }
        val tokenHandler = mockk<HuaweiPushNewTokenHandler>()
        coEvery { tokenHandler.handle(any()) } returns Result.success(Unit)
        val coordinator = HuaweiPushRegistrationCoordinator(
            tokenSource = tokenSource,
            newTokenHandler = tokenHandler,
            sessionObserver = FakeSessionObserver(),
            diagnostics = mockk(relaxed = true),
            coroutineScope = this,
        )

        coordinator.start()
        advanceUntilIdle()

        coVerify(exactly = 1) { tokenHandler.handle("opaque-token") }
        check(tokenRequests == 2)
    }

    @Test
    fun `matrix pusher failure is reported as a dedicated registration error`() = runTest {
        val diagnostics = mockk<HuaweiPushDiagnostics>(relaxed = true)
        val tokenHandler = mockk<HuaweiPushNewTokenHandler>()
        coEvery { tokenHandler.handle(any()) } returns Result.failure(IllegalStateException("server rejected pusher"))
        coEvery { tokenHandler.restoreUniversalFallback() } returns Result.success(Unit)
        val coordinator = HuaweiPushRegistrationCoordinator(
            tokenSource = HuaweiPushTokenSource { Result.success("opaque-token") },
            newTokenHandler = tokenHandler,
            sessionObserver = FakeSessionObserver(),
            diagnostics = diagnostics,
            coroutineScope = this,
        )

        coordinator.start()
        advanceUntilIdle()

        coVerify(exactly = 3) { tokenHandler.handle("opaque-token") }
        coVerify(exactly = 1) { tokenHandler.restoreUniversalFallback() }
        verify(exactly = 1) { diagnostics.recordFailure(ofType<HuaweiPusherRegistrationException>()) }
        verify(exactly = 1) { diagnostics.recordFallbackActive(ofType<HuaweiPusherRegistrationException>()) }
    }
}
