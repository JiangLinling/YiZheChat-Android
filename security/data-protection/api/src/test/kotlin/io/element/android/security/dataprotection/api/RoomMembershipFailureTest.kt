/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomMembershipFailureTest {
    @Test fun `nested failure retains precise stage without rendering secret cause`() = runTest {
        val secret = IllegalStateException("Bearer secret-token private-user-content")
        val failure = runCatching {
            membershipStage(MembershipStage.NATIVE_LEAVE) {
                membershipStage(MembershipStage.MATRIX_SESSION) { throw secret }
            }
        }.exceptionOrNull() as RoomMembershipFailure
        assertThat(failure.diagnostic).isEqualTo("MATRIX_SESSION:STATE_REJECTED")
        assertThat(failure.message).doesNotContain("secret-token")
        assertThat(failure.cause).isSameInstanceAs(secret)
    }
    @Test fun `real cancellation propagates without a retryable failure`() = runTest {
        val cancelled = CancellationException("scope closed")
        assertThat(runCatching { membershipStage(MembershipStage.LC12_PREPARE) { throw cancelled } }.exceptionOrNull())
            .isSameInstanceAs(cancelled)
    }
}
