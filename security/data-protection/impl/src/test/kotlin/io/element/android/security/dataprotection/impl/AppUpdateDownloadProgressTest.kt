/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import org.junit.Test

class AppUpdateDownloadProgressTest {
    @Test
    fun `fraction reports bounded download progress`() {
        assertThat(AppUpdateDownloadProgress(25L, 100L).fraction).isEqualTo(0.25f)
        assertThat(AppUpdateDownloadProgress(125L, 100L).fraction).isEqualTo(1f)
    }

    @Test
    fun `fraction is unknown without a response length`() {
        assertThat(AppUpdateDownloadProgress(25L, -1L).fraction).isNull()
        assertThat(AppUpdateDownloadProgress(25L, 0L).fraction).isNull()
    }
}
