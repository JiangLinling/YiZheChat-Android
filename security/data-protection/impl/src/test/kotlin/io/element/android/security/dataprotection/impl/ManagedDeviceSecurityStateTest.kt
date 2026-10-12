/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import org.junit.Test

class ManagedDeviceSecurityStateTest {
    @Test
    fun `pending managed license is exposed as managed approval pending after location is accepted`() {
        assertThat(
            managedDeviceSecurityState(
                trustState = "PENDING",
                locationEvidenceState = "ACCEPTED",
                managedLicenseState = "PENDING",
            )
        ).isEqualTo(ManagedDeviceSecurityState.MANAGED_APPROVAL_PENDING)
    }

    @Test
    fun `location collection remains required before managed approval is shown`() {
        assertThat(
            managedDeviceSecurityState(
                trustState = "PENDING",
                locationEvidenceState = "REQUIRED",
                managedLicenseState = "PENDING",
            )
        ).isEqualTo(ManagedDeviceSecurityState.LOCATION_REQUIRED)
    }

    @Test
    fun `ordinary pending trust remains validation pending`() {
        assertThat(
            managedDeviceSecurityState(
                trustState = "PENDING",
                locationEvidenceState = "ACCEPTED",
                managedLicenseState = null,
            )
        ).isEqualTo(ManagedDeviceSecurityState.VALIDATION_PENDING)
    }
}
