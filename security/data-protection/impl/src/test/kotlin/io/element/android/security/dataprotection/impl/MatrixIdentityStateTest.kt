/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import org.junit.Test

class MatrixIdentityStateTest {
    @Test
    fun `empty session list is unknown`() {
        assertThat(aggregateMatrixIdentityState(emptyList())).isEqualTo("UNKNOWN")
    }

    @Test
    fun `all sessions must be verified`() {
        assertThat(
            aggregateMatrixIdentityState(listOf(SessionVerifiedStatus.Verified, SessionVerifiedStatus.Verified)),
        ).isEqualTo("VERIFIED")
    }

    @Test
    fun `one unverified session blocks the installation identity`() {
        assertThat(
            aggregateMatrixIdentityState(listOf(SessionVerifiedStatus.Verified, SessionVerifiedStatus.NotVerified)),
        ).isEqualTo("UNVERIFIED")
    }

    @Test
    fun `unknown status cannot be promoted`() {
        assertThat(
            aggregateMatrixIdentityState(listOf(SessionVerifiedStatus.Verified, SessionVerifiedStatus.Unknown)),
        ).isEqualTo("UNKNOWN")
    }
}
