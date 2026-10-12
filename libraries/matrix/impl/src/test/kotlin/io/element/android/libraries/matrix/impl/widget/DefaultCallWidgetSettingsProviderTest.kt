/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import uniffi.matrix_sdk.EncryptionSystem

class DefaultCallWidgetSettingsProviderTest {
    @Test
    fun `encrypted MatrixRTC uses per-participant keys`() {
        assertThat(matrixRtcEncryptionSystem(encrypted = true)).isEqualTo(EncryptionSystem.PerParticipantKeys)
    }

    @Test(expected = SecurityException::class)
    fun `unencrypted MatrixRTC is rejected without fallback`() {
        matrixRtcEncryptionSystem(encrypted = false)
    }
}
