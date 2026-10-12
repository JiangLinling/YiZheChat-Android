/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountVaultLifecycleCoordinatorTest {
    @Test
    fun `ready transition requests authorization recovery while visible`() {
        assertThat(
            shouldRequestAuthorizationRecovery(
                authorizationReady = true,
                appVisible = true,
                recoveryPending = true,
            )
        ).isTrue()
    }

    @Test
    fun `foreground transition delivers a recovery that became ready in background`() {
        assertThat(
            shouldRequestAuthorizationRecovery(
                authorizationReady = true,
                appVisible = true,
                recoveryPending = true,
            )
        ).isTrue()
    }

    @Test
    fun `completed recovery does not create refresh loop`() {
        assertThat(
            shouldRequestAuthorizationRecovery(
                authorizationReady = true,
                appVisible = true,
                recoveryPending = false,
            )
        ).isFalse()
    }

    @Test
    fun `locked or background state never requests recovery`() {
        assertThat(
            shouldRequestAuthorizationRecovery(
                authorizationReady = false,
                appVisible = true,
                recoveryPending = true,
            )
        ).isFalse()
        assertThat(
            shouldRequestAuthorizationRecovery(
                authorizationReady = true,
                appVisible = false,
                recoveryPending = true,
            )
        ).isFalse()
    }
}
