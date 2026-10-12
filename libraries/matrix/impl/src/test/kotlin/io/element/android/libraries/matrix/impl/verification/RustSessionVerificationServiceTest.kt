/*
 * Copyright (c) 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.verification

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiEncryption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.VerificationState

@OptIn(ExperimentalCoroutinesApi::class)
class RustSessionVerificationServiceTest {
    @Test
    fun `publishes current verification status after E2EE initialization`() = runTest {
        val serviceScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = RustSessionVerificationService(
            client = FakeFfiClient(
                encryption = FakeFfiEncryption(verificationState = VerificationState.UNVERIFIED),
            ),
            isSyncServiceReady = MutableStateFlow(true),
            sessionCoroutineScope = serviceScope,
        )

        try {
            assertThat(service.sessionVerifiedStatus.value).isEqualTo(SessionVerifiedStatus.Unknown)

            advanceUntilIdle()

            assertThat(service.sessionVerifiedStatus.value).isEqualTo(SessionVerifiedStatus.NotVerified)
        } finally {
            service.destroy()
            serviceScope.cancel()
        }
    }
}
