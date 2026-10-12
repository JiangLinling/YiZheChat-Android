/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.emotions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class EmotionChoice(val key: String, val emoji: String? = null, val emotion: ChatEmotion? = null)

/** Keep plain insertion and animated sending as distinct, adjacent choices; retain every effect. */
internal fun unifiedEmotionChoices(emojis: List<String>): List<EmotionChoice> = buildList {
    val remaining = ChatEmotions.all.toMutableList()
    emojis.distinct().forEach { emoji ->
        add(EmotionChoice("emoji_$emoji", emoji = emoji))
        val emotion = remaining.firstOrNull { it.token.replace("\uFE0F", "") == emoji.replace("\uFE0F", "") }
        if (emotion != null) {
            add(EmotionChoice(emotion.id, emotion = emotion))
            remaining.remove(emotion)
        }
    }
    remaining.forEach { add(EmotionChoice(it.id, emotion = it)) }
}

@Composable
internal fun EmotionPicker(
    canSend: Boolean,
    onSend: (ChatEmotion) -> Unit,
    emojis: List<String> = emptyList(),
    onEmojiSelected: (String) -> Unit = {},
    onBackspace: () -> Unit = {},
    onOpenCommon: (() -> Unit)? = null,
    commonLabel: String = "",
) {
    var preview by remember { mutableStateOf<EmotionChoice?>(null) }
    var previewHeld by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(onSend)
    val insert by rememberUpdatedState(onEmojiSelected)
    val choices = remember(emojis) { unifiedEmotionChoices(emojis) }
    val options = EmotionOptions()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { previewHeld = false; preview = null }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(preview, previewHeld) {
        if (preview != null && preview?.emotion == null && !previewHeld) { delay(3000); preview = null }
    }
    Box(Modifier.fillMaxSize().testTag("emotion_picker")) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (onOpenCommon != null) 108.dp else 58.dp),
            modifier = Modifier.fillMaxSize().testTag("emotion_choices"),
        ) {
            items(choices, key = { it.key }) { choice ->
                val emotion = choice.emotion
                val name = emotion?.let { stringResource(it.label) } ?: choice.emoji.orEmpty()
                val description = if (emotion != null) stringResource(R.string.emotion_animated_named, name) else name
                val previewLabel = stringResource(R.string.emotion_preview_named, name)
                val clickLabel = if (emotion != null && canSend) stringResource(R.string.emotion_send_named, name) else null
                fun select() {
                    if (emotion == null) insert(choice.emoji.orEmpty())
                    else if (canSend) send(emotion)
                    else { previewHeld = false; preview = choice }
                }
                Box(
                    Modifier.fillMaxWidth().height(56.dp)
                        .pointerInput(choice.key, canSend) {
                            detectTapGestures(
                                onPress = {
                                    try { tryAwaitRelease() } finally {
                                        if (previewHeld) { previewHeld = false; preview = null }
                                    }
                                },
                                onTap = { select() },
                                onLongPress = { previewHeld = true; preview = choice },
                            )
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = description
                            onClick(clickLabel) { select(); true }
                            onLongClick(previewLabel) { previewHeld = false; preview = choice; true }
                        }
                        .testTag(if (emotion != null) "emotion_choice_${emotion.id}" else choice.key),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (emotion != null) {
                            EmotionArtwork(emotion, 0f, Modifier.fillMaxSize())
                            val ink = ElementTheme.colors.iconSecondary
                            Canvas(
                                Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(16.dp)
                                    .testTag("emotion_badge_${emotion.id}")
                            ) {
                                // Motion-photo mark: a solid inner ring surrounded by separate dots.
                                drawCircle(ink, radius = size.minDimension * .245f, style = Stroke(size.minDimension * .085f))
                                repeat(18) { index ->
                                    val angle = index * 2 * PI / 18
                                    val radius = size.minDimension * .43f
                                    drawCircle(
                                        ink,
                                        radius = size.minDimension * .045f,
                                        center = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius),
                                    )
                                }
                            }
                        } else Text(choice.emoji.orEmpty(),
                            style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 34.sp, lineHeight = 40.sp))
                    }
                }
            }
        }
        if (onOpenCommon != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 54.dp).size(56.dp, 44.dp)
                    .clip(CircleShape).background(ElementTheme.colors.bgCanvasDefault)
                    .clickable(role = Role.Button, onClick = onOpenCommon).testTag("emoji_common"),
                contentAlignment = Alignment.Center,
            ) {
                Text(commonLabel, style = ElementTheme.typography.fontBodyMdMedium, color = ElementTheme.colors.textPrimary)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 8.dp).size(56.dp, 42.dp)
                .clip(CircleShape).background(ElementTheme.colors.bgCanvasDefault)
                .clickable(role = Role.Button, onClick = onBackspace).testTag("emoji_backspace"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CompoundIcons.Backspace(), stringResource(CommonStrings.action_remove), tint = ElementTheme.colors.iconPrimary)
        }
        preview?.let { choice ->
            val offset = with(LocalDensity.current) { 212.dp.roundToPx() }
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -offset),
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
                key(choice.key) {
                    val emotion = choice.emotion
                    if (emotion != null) EmotionPlayback(emotion, options, Modifier.size(212.dp).testTag("emotion_preview"), scene = false,
                        onFinished = { if (!previewHeld) preview = null })
                    else Box(Modifier.size(212.dp).testTag("emoji_preview"), contentAlignment = Alignment.Center) {
                        Text(choice.emoji.orEmpty(),
                            style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 112.sp, lineHeight = 130.sp))
                    }
                }
            }
        }
    }

}

@PreviewsDayNight
@Composable
private fun EmotionPickerPreview() = ElementPreview {
    Box(Modifier.height(280.dp)) { EmotionPicker(true, {}, emojis = listOf("😀", "😂", "❤️", "👍")) }
}
