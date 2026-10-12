/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.emotions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Only public, bundled artwork enters this bounded cache; never message images or account data. */
private object EmotionPosters : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
private fun EmotionPoster(emotion: ChatEmotion, modifier: Modifier, fullResolution: Boolean = false) {
    val context = LocalContext.current
    val cacheKey = if (fullResolution) "${emotion.id}:full" else emotion.id
    val bitmap by produceState(EmotionPosters.get(cacheKey) ?: EmotionPosters.get(emotion.id), cacheKey) {
        if (EmotionPosters.get(cacheKey) == null) value = withContext(Dispatchers.IO) {
            val filename = if (fullResolution) "${emotion.id}.webp" else "${emotion.id}_thumb.webp"
            context.assets.open("emotions/$filename").use { BitmapFactory.decodeStream(it) }
                ?.also { EmotionPosters.put(cacheKey, it) }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
}

/** ImageDecoder keeps authored timing and alpha. The view owns callbacks and releases playback on removal. */
@Composable
private fun EmotionAnimatedAsset(emotion: ChatEmotion, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, emotion.id) {
        if (Build.VERSION.SDK_INT >= 28) value = withContext(Dispatchers.IO) {
            val data = context.assets.open("emotions/${emotion.id}.webp").use { it.readBytes() }
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))).also {
                if (it is AnimatedImageDrawable) it.repeatCount = 0
            }
        }
    }
    Box(modifier) {
        if (drawable == null) EmotionPoster(emotion, Modifier.fillMaxSize())
        else AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = 2 } },
            modifier = Modifier.fillMaxSize(),
            update = { view -> if (view.drawable !== drawable) view.setImageDrawable(drawable) },
            onRelease = { view ->
                if (Build.VERSION.SDK_INT >= 28) (view.drawable as? AnimatedImageDrawable)?.stop()
                view.setImageDrawable(null)
            },
        )
    }
    DisposableEffect(drawable) {
        if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.start()
        onDispose { if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.stop() }
    }
}

@Composable
internal fun EmotionArtwork(
    emotion: ChatEmotion,
    progress: Float,
    modifier: Modifier = Modifier,
    scene: Boolean = false,
    playing: Boolean = false,
) {
    val gift = emotion.id.startsWith("gift_")
    val t = progress.coerceIn(0f, 1f)
    val entrance = easeOut((t / .15f).coerceIn(0f, 1f))
    val exit = ((1f - t) / .14f).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (scene && playing && !gift) Canvas(Modifier.fillMaxSize()) { atmosphere(emotion, t) }
        val hero = if (scene) Modifier.fillMaxWidth(if (gift) .98f else .72f).sizeIn(maxWidth = 420.dp).aspectRatio(1f)
        else Modifier.fillMaxSize()
        val animation = hero.graphicsLayer {
            if (playing && scene && !gift) {
                scaleX = .76f + .24f * entrance
                scaleY = scaleX
                alpha = entrance * exit
                translationY = (1f - entrance) * 35.dp.toPx()
            }
            if (playing && !emotion.animated) {
                rotationZ = sin(t * 2f * PI.toFloat()) * if (emotion.id == "lantern") 7f else 3f
                translationY += sin(t * 2f * PI.toFloat()) * 5.dp.toPx()
            }
        }
        if (playing && emotion.animated) EmotionAnimatedAsset(emotion, animation)
        else EmotionPoster(emotion, animation, fullResolution = playing && !emotion.animated)
    }
}

private fun easeOut(t: Float): Float = 1f - (1f - t).pow(3)

/** Gift scenes retain their own particles. Native ambience uses fixed seeds and a bounded draw budget. */
private fun DrawScope.atmosphere(emotion: ChatEmotion, progress: Float) {
    val festive = emotion.tier == 2 || emotion.id in setOf("fortune", "lantern", "dragonface", "tiger")
    val colors = if (festive) listOf(Color(0xFFFFD978), Color(0xFFFF8D53), Color(0xFFFF5B77))
        else listOf(Color(0xFFFFA8CD), Color(0xFFFF618D), Color(0xFFFFDFEC))
    val fade = (progress / .12f).coerceIn(0f, 1f) * ((1f - progress) / .22f).coerceIn(0f, 1f)
    drawCircle(Brush.radialGradient(listOf(colors[0].copy(alpha = .12f * fade), Color.Transparent), center,
        size.minDimension * .62f), size.minDimension * .62f, center)
    if (emotion.id == "fireworks" || emotion.id == "firecracker") {
        repeat(4) { burst ->
            val phase = ((progress - burst * .14f) / .48f).coerceIn(0f, 1f)
            if (phase > 0f && phase < 1f) {
                val origin = Offset(size.width * (.25f + (burst % 2) * .5f), size.height * (.25f + (burst / 2) * .35f))
                repeat(26) { ray ->
                    val angle = ray * (2 * PI.toFloat() / 26) + burst
                    val length = size.minDimension * .36f * easeOut(phase)
                    val direction = Offset(cos(angle), sin(angle))
                    val tip = origin + direction * length + Offset(0f, phase * phase * 35.dp.toPx())
                    val tail = tip - direction * ((1f - phase) * 30.dp.toPx())
                    val color = colors[(ray + burst) % colors.size].copy(alpha = (1f - phase).pow(1.3f))
                    drawLine(color.copy(alpha = color.alpha * .18f), tail, tip, 6.dp.toPx(), StrokeCap.Round)
                    drawLine(color, tail, tip, 1.4.dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = color.alpha), 1.dp.toPx(), tip)
                }
            }
        }
        return
    }
    repeat(if (festive) 60 else 24) { i ->
        val seed = ((i * 73 + 17) % 101) / 101f
        val phase = ((progress - seed * .18f) / .82f).coerceIn(0f, 1f)
        val angle = i * 2.399963f
        val radial = size.minDimension * (.12f + easeOut(phase) * (.35f + seed * .3f))
        val origin = center + Offset(cos(angle) * radial, sin(angle) * radial * .8f - phase * 30.dp.toPx())
        val alpha = fade * (1f - phase * .7f)
        val radius = (2f + seed * 4f).dp.toPx()
        val color = colors[i % colors.size].copy(alpha = alpha)
        if (!festive && i % 3 == 0) {
            val path = Path().apply {
                moveTo(origin.x, origin.y + radius)
                cubicTo(origin.x - radius * 2, origin.y, origin.x - radius, origin.y - radius * 2, origin.x, origin.y - radius * .6f)
                cubicTo(origin.x + radius, origin.y - radius * 2, origin.x + radius * 2, origin.y, origin.x, origin.y + radius)
                close()
            }
            drawPath(path, color)
        } else if (festive && i % 3 != 0) {
            rotate(i * 37f + phase * 170f, origin) {
                drawRoundRect(color, origin, androidx.compose.ui.geometry.Size(radius * .65f, radius * 1.8f),
                    androidx.compose.ui.geometry.CornerRadius(radius * .2f))
            }
        } else {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * .3f), Color.Transparent), origin, radius * 3), radius * 3, origin)
            drawLine(color, origin - Offset(radius, 0f), origin + Offset(radius, 0f), 1.dp.toPx(), StrokeCap.Round)
            drawLine(color, origin - Offset(0f, radius), origin + Offset(0f, radius), 1.dp.toPx(), StrokeCap.Round)
        }
    }
}
