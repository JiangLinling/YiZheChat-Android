/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.components.media

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs

private const val DEFAULT_GRAPHICS_LAYER_ALPHA: Float = 0.99F

/**
 * A view that displays a waveform and a cursor to indicate the current playback progress.
 *
 * @param playbackProgress The current playback progress, between 0 and 1.
 * @param showCursor Whether to show the cursor or not.
 * @param waveform The waveform to display.
 * @param onSeek Callback when the user seeks the waveform. Called with a value between 0 and 1.
 * @param onSeekPreview Callback while the user drags the waveform. Called with null when the gesture ends.
 * @param onTap Optional callback for a tap that does not cross the drag threshold. When null, taps keep seeking.
 * @param modifier The modifier to be applied to the view.
 * @param seekEnabled Whether the user can seek the waveform or not.
 * @param brush The brush to use to draw the waveform.
 * @param progressBrush The brush to use to draw the progress.
 * @param cursorBrush The brush to use to draw the cursor.
 * @param lineWidth The width of the waveform lines.
 * @param linePadding The padding between waveform lines.
 */
@Composable
fun WaveformPlaybackView(
    playbackProgress: Float,
    showCursor: Boolean,
    waveform: ImmutableList<Float>,
    onSeek: (progress: Float) -> Unit,
    onSeekPreview: (progress: Float?) -> Unit = {},
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
    brush: Brush = SolidColor(ElementTheme.colors.iconQuaternary),
    progressBrush: Brush = SolidColor(ElementTheme.colors.iconSecondary),
    cursorBrush: Brush = SolidColor(ElementTheme.colors.iconAccentTertiary),
    lineWidth: Dp = 2.dp,
    linePadding: Dp = 2.dp,
) {
    val seekProgress = remember { mutableStateOf<Float?>(null) }
    var canvasSize by remember { mutableStateOf(DpSize(0.dp, 0.dp)) }
    var canvasSizePx by remember { mutableStateOf(Size(0f, 0f)) }
    val progress by remember(playbackProgress, seekProgress.value) {
        derivedStateOf {
            seekProgress.value ?: playbackProgress
        }
    }
    val progressAnimated = animateFloatAsState(targetValue = progress, label = "progressAnimation")
    val amplitudeDisplayCount by remember(canvasSize, lineWidth, linePadding) {
        derivedStateOf {
            (canvasSize.width.value / (lineWidth.value + linePadding.value)).toInt()
        }
    }
    val normalizedWaveformData by remember(amplitudeDisplayCount) {
        derivedStateOf {
            waveform.normalisedData(amplitudeDisplayCount)
        }
    }

    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val waveformWidthPx by remember {
        derivedStateOf { with(density) { normalizedWaveformData.size * (lineWidth + linePadding).roundToPx().toFloat() } }
    }

    val requestDisallowInterceptTouchEvent = remember { RequestDisallowInterceptTouchEvent() }
    var pointerDownX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = DEFAULT_GRAPHICS_LAYER_ALPHA)
            .let {
                if (!seekEnabled) return@let it
                it.pointerInteropFilter(requestDisallowInterceptTouchEvent = requestDisallowInterceptTouchEvent) { e ->
                    return@pointerInteropFilter when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            if (e.x in 0F..waveformWidthPx) {
                                requestDisallowInterceptTouchEvent.invoke(true)
                                pointerDownX = e.x
                                isDragging = false
                                if (onTap == null) {
                                    val progress = (e.x / waveformWidthPx).coerceIn(0f, 1f)
                                    seekProgress.value = progress
                                    onSeekPreview(progress)
                                }
                                true
                            } else {
                                false
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isDragging && abs(e.x - pointerDownX) >= touchSlop) {
                                isDragging = true
                            }
                            if (isDragging || onTap == null) {
                                val progress = (e.x / waveformWidthPx).coerceIn(0f, 1f)
                                seekProgress.value = progress
                                onSeekPreview(progress)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            requestDisallowInterceptTouchEvent.invoke(false)
                            if (onTap != null && !isDragging) {
                                onTap()
                            } else {
                                seekProgress.value?.let(onSeek)
                            }
                            seekProgress.value = null
                            onSeekPreview(null)
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            requestDisallowInterceptTouchEvent.invoke(false)
                            seekProgress.value = null
                            onSeekPreview(null)
                            isDragging = false
                            true
                        }
                        else -> false
                    }
                }
            }
            .then(modifier)
    ) {
        canvasSize = size.toDpSize()
        canvasSizePx = size
        val cornerRadius = lineWidth / 2
        // Calculate the size of the waveform by summing the width of all the lines and paddings
        drawWaveform(
            waveformData = normalizedWaveformData,
            canvasSizePx = canvasSizePx,
            brush = brush,
            lineWidth = lineWidth,
            linePadding = linePadding
        )
        drawRect(
            brush = progressBrush,
            size = Size(
                width = progressAnimated.value * waveformWidthPx,
                height = canvasSizePx.height
            ),
            blendMode = BlendMode.SrcAtop
        )
        if (showCursor || seekProgress.value != null) {
            drawRoundRect(
                brush = cursorBrush,
                topLeft = Offset(
                    x = progressAnimated.value * waveformWidthPx,
                    y = 1f
                ),
                size = Size(
                    width = lineWidth.toPx(),
                    height = canvasSizePx.height - 2
                ),
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                style = Fill
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun WaveformPlaybackViewPreview() = ElementPreview {
    Column {
        WaveformPlaybackView(
            modifier = Modifier.height(34.dp),
            showCursor = false,
            playbackProgress = 0.5f,
            onSeek = {},
            waveform = persistentListOf(),
        )
        WaveformPlaybackView(
            modifier = Modifier.height(34.dp),
            showCursor = false,
            playbackProgress = 0.5f,
            onSeek = {},
            waveform = WaveFormSamples.realisticWaveForm,
        )
        WaveformPlaybackView(
            modifier = Modifier.height(34.dp),
            showCursor = true,
            playbackProgress = 0.5f,
            onSeek = {},
            waveform = WaveFormSamples.allRangeWaveForm,
        )
    }
}

internal fun ImmutableList<Float>.normalisedData(maxSamplesCount: Int): ImmutableList<Float> {
    if (maxSamplesCount <= 0 || isEmpty()) {
        return persistentListOf()
    }
    if (size == maxSamplesCount) {
        return this
    }
    if (maxSamplesCount == 1) {
        return persistentListOf(first())
    }

    // Stretch or condense the samples to the available width so short recordings do not leave a large blank tail.
    return List(maxSamplesCount) { index ->
        val sourcePosition = index.toFloat() * lastIndex / (maxSamplesCount - 1)
        val lowerIndex = sourcePosition.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(lastIndex)
        val fraction = sourcePosition - lowerIndex
        this[lowerIndex] + (this[upperIndex] - this[lowerIndex]) * fraction
    }.toImmutableList()
}
