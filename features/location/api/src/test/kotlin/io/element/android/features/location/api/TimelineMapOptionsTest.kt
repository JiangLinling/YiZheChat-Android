/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.location.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions

class TimelineMapOptionsTest {
    @Test
    fun `thumbnail uses composited rendering without intercepting timeline gestures`() {
        assertThat(timelineMapOptions.renderOptions.renderMode).isEqualTo(RenderOptions.RenderMode.TextureView)
        assertThat(timelineMapOptions.gestureOptions).isEqualTo(GestureOptions.AllDisabled)
    }
}
