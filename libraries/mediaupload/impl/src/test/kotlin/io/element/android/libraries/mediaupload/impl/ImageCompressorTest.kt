/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageCompressorTest {
    @Test
    fun `transformAndRecycleSource recycles a replaced source bitmap`() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val replacement = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val result = source.transformAndRecycleSource { replacement }

        assertThat(result).isSameInstanceAs(replacement)
        assertThat(source.isRecycled).isTrue()
        assertThat(replacement.isRecycled).isFalse()
        replacement.recycle()
    }

    @Test
    fun `transformAndRecycleSource keeps an unchanged source bitmap alive`() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val result = source.transformAndRecycleSource { this }

        assertThat(result).isSameInstanceAs(source)
        assertThat(source.isRecycled).isFalse()
        source.recycle()
    }

    @Test
    fun `transformAndRecycleSource recycles its source when transformation fails`() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        assertThrows(IllegalStateException::class.java) {
            source.transformAndRecycleSource { error("failed transform") }
        }

        assertThat(source.isRecycled).isTrue()
    }
}
