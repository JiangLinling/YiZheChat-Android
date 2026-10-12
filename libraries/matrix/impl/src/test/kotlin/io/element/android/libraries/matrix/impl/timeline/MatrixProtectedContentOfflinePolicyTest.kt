/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.dataprotection.api.OfflineAccessMode
import org.junit.Test

class MatrixProtectedContentOfflinePolicyTest {
    @Test
    fun `online required management policy overrides an offline capable room`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.ONLINE_REQUIRED,
                policyMaximumOfflineMillis = 0,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 60_000,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `bounded offline is selected only when both policy and room permit it`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                roomMaximumOfflineMillis = 60_000,
                policyMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 60_000,
            )
        ).isEqualTo(OfflineLeaseMode.BOUNDED_OFFLINE)
    }

    @Test
    fun `burn after read always requires online authorization`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.BURN_AFTER_READ,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                policyMaximumOfflineMillis = 60_000,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 60_000,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `managed compatibility device is forced online under an offline capable policy`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                policyMaximumOfflineMillis = 60_000,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 60_000,
                deviceAllowsBoundedOffline = false,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `one online-only recipient forces the entire protected message online`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                policyMaximumOfflineMillis = 60_000,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 60_000,
                recipientsAllowBoundedOffline = false,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `client ceiling broader than signed policy falls back to online authorization`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                policyMaximumOfflineMillis = 60_000,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 86_400_000,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }

    @Test
    fun `client ceiling broader than room policy falls back to online authorization`() {
        assertThat(
            resolveOfflineLeaseMode(
                lifecycleMode = MessageLifecycleMode.FOLLOW_ROOM,
                policyMode = OfflineAccessMode.BOUNDED_OFFLINE,
                policyMaximumOfflineMillis = 86_400_000,
                roomMaximumOfflineMillis = 60_000,
                clientMaximumOfflineMillis = 86_400_000,
            )
        ).isEqualTo(OfflineLeaseMode.ONLINE_REQUIRED)
    }
}
