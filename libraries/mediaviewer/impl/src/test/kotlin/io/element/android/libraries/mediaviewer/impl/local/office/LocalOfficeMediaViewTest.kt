/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import org.junit.Test

class LocalOfficeMediaViewTest {
    @Test
    fun `embedded viewer does not reserve another top bar`() {
        assertThat(
            officeTopContentPadding(
                renderMode = LocalMediaRenderMode.EMBEDDED,
                standaloneTopPadding = 88.dp,
            )
        ).isEqualTo(0.dp)
    }

    @Test
    fun `standalone viewer retains its existing top bar spacing`() {
        assertThat(
            officeTopContentPadding(
                renderMode = LocalMediaRenderMode.STANDALONE,
                standaloneTopPadding = 88.dp,
            )
        ).isEqualTo(88.dp)
    }
}
