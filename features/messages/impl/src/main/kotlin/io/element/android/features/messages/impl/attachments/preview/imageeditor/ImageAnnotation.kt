/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview.imageeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

@Immutable
data class ImagePoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
    }
}

@Immutable
sealed interface ImageAnnotation {
    data class Stroke(val points: List<ImagePoint>, val color: Int, val width: Float) : ImageAnnotation
    data class Mosaic(val points: List<ImagePoint>, val width: Float) : ImageAnnotation
    data class Label(val point: ImagePoint, val text: String, val color: Int, val size: Float) : ImageAnnotation
}

internal fun ImageAnnotation.mapPoints(transform: (ImagePoint) -> ImagePoint): ImageAnnotation = when (this) {
    is ImageAnnotation.Stroke -> copy(points = points.map(transform))
    is ImageAnnotation.Mosaic -> copy(points = points.map(transform))
    is ImageAnnotation.Label -> copy(point = transform(point))
}

/** The editor preview and export use the same pixel renderer; mosaic pixels are baked into the result. */
internal fun renderAnnotations(canvas: Canvas, bitmap: Bitmap, annotations: List<ImageAnnotation>) {
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    annotations.forEach { annotation ->
        when (annotation) {
            is ImageAnnotation.Stroke -> {
                if (annotation.points.isEmpty()) return@forEach
                paint.color = annotation.color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, annotation.width * min(w, h))
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val path = Path()
                annotation.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x * w, point.y * h) else path.lineTo(point.x * w, point.y * h)
                }
                if (annotation.points.size == 1) {
                    canvas.drawPoint(annotation.points[0].x * w, annotation.points[0].y * h, paint)
                } else {
                    canvas.drawPath(path, paint)
                }
            }
            is ImageAnnotation.Label -> {
                paint.style = Paint.Style.FILL
                paint.color = annotation.color
                paint.textSize = annotation.size * min(w, h)
                paint.setShadowLayer(max(1f, paint.textSize / 16), 0f, 0f, android.graphics.Color.BLACK)
                canvas.drawText(annotation.text, annotation.point.x * w, annotation.point.y * h, paint)
                paint.clearShadowLayer()
            }
            is ImageAnnotation.Mosaic -> {
                val block = max(4, (min(w, h) * .025f).toInt())
                val radius = max(block, (annotation.width * min(w, h)).toInt())
                val marked = mutableSetOf<Pair<Int, Int>>()
                fun mark(x: Float, y: Float) {
                    val cx = (x * w).toInt()
                    val cy = (y * h).toInt()
                    for (yy in max(0, cy - radius) / block..min(bitmap.height - 1, cy + radius) / block) {
                        for (xx in max(0, cx - radius) / block..min(bitmap.width - 1, cx + radius) / block) marked += xx to yy
                    }
                }
                annotation.points.forEachIndexed { index, point ->
                    mark(point.x, point.y)
                    if (index > 0) {
                        val prev = annotation.points[index - 1]
                        val steps = max(1, (max(kotlin.math.abs(point.x - prev.x) * w, kotlin.math.abs(point.y - prev.y) * h) / block).toInt())
                        for (i in 1..steps) mark(prev.x + (point.x - prev.x) * i / steps, prev.y + (point.y - prev.y) * i / steps)
                    }
                }
                paint.style = Paint.Style.FILL
                marked.forEach { (x, y) ->
                    val left = x * block
                    val top = y * block
                    // Each pixel block is replaced by one opaque colour; no original-detail overlay remains.
                    paint.color = bitmap.getPixel(min(bitmap.width - 1, left + block / 2), min(bitmap.height - 1, top + block / 2)) or 0xff000000.toInt()
                    canvas.drawRect(Rect(left, top, min(bitmap.width, left + block), min(bitmap.height, top + block)), paint)
                }
            }
        }
    }
}
