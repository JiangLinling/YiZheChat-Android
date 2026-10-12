/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview.imageeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAnnotationTest {
    @Test
    fun `mosaic replaces detail in export pixels and preserves outside pixels`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0..99) for (x in 0..99) source.setPixel(x, y, if ((x + y) % 2 == 0) Color.RED else Color.BLUE)
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        renderAnnotations(Canvas(result), source, listOf(ImageAnnotation.Mosaic(listOf(ImagePoint(.5f, .5f)), .1f)))
        assertThat(result.getPixel(0, 0)).isEqualTo(source.getPixel(0, 0))
        assertThat(result.getPixel(48, 48)).isEqualTo(result.getPixel(49, 48))
        assertThat(result.getPixel(49, 48)).isNotEqualTo(source.getPixel(49, 48))
        source.recycle()
        result.recycle()
    }

    @Test
    fun `four rotations restore annotations and crop to the original coordinates`() {
        val original = AttachmentImageEdits(
            cropRect = NormalizedCropRect(.1f, .2f, .8f, .9f),
            annotations = listOf(ImageAnnotation.Label(ImagePoint(.25f, .5f), "label", Color.WHITE, .04f))
        )
        var rotated = original
        repeat(4) { rotated = rotated.rotateAntiClockwise() }
        val point = (rotated.annotations.single() as ImageAnnotation.Label).point
        assertThat(point.x).isWithin(.00001f).of(.25f)
        assertThat(point.y).isWithin(.00001f).of(.5f)
        assertThat(rotated.cropRect.left).isWithin(.00001f).of(original.cropRect.left)
        assertThat(rotated.rotationDegrees).isEqualTo(0)
    }

    @Test
    fun `annotations alone require a rendered export`() {
        assertThat(AttachmentImageEdits(annotations = listOf(ImageAnnotation.Stroke(listOf(ImagePoint(.1f, .1f)), Color.RED, .02f))).hasChanges).isTrue()
    }
}
