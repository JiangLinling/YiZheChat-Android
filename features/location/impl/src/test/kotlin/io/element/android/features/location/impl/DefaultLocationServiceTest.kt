/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultLocationServiceTest {
    @Test
    fun `isServiceAvailable returns true because a keyless map fallback is available`() {
        val locationService = DefaultLocationService()
        assertThat(locationService.isServiceAvailable()).isTrue()
    }
}
