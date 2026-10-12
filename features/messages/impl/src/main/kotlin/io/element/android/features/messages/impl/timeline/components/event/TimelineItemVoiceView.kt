/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.LocalTimelineIsTalkbackActive
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContentProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageStateProvider
import kotlinx.coroutines.delay
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TimelineItemVoiceView(
    state: VoiceMessageState,
    content: TimelineItemVoiceContent,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    isMine: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val currentState by rememberUpdatedState(state)
    val currentLongClick by rememberUpdatedState(onLongClick)
    val isTalkbackActive = LocalTimelineIsTalkbackActive.current
    var seekPreview by remember(content.eventId) { mutableStateOf<Float?>(null) }
    val playing = state.buttonType == VoiceMessageState.ButtonType.Pause
    val enabled = state.buttonType != VoiceMessageState.ButtonType.Disabled && state.buttonType != VoiceMessageState.ButtonType.Downloading
    val canSeek = state.showCursor && state.buttonType in listOf(VoiceMessageState.ButtonType.Play, VoiceMessageState.ButtonType.Pause)
    var animationFrame by remember { mutableIntStateOf(2) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) { animationFrame = (animationFrame + 1) % 3; delay(320) }
        } else animationFrame = 2
    }
    LaunchedEffect(canSeek) { if (!canSeek) seekPreview = null }
    fun playPause() {
        if (currentState.buttonType !in listOf(VoiceMessageState.ButtonType.Disabled, VoiceMessageState.ButtonType.Downloading)) {
            currentState.eventSink(VoiceMessageEvent.PlayPause)
        }
    }
    fun changeSpeed() {
        if (currentState.buttonType !in listOf(VoiceMessageState.ButtonType.Disabled, VoiceMessageState.ButtonType.Downloading)) {
            currentState.eventSink(VoiceMessageEvent.ChangePlaybackSpeed)
        }
    }
    val progress = (seekPreview ?: state.progress).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val accent = if (state.playbackSpeed > 1f) {
        if (ElementTheme.isLightTheme) Color(0xFF9A6900) else Color(0xFFFFD366)
    } else ElementTheme.colors.iconAccentPrimary
    val fill = if (state.playbackSpeed > 1f) Color(0xFFF5C94A).copy(alpha = 0.34f) else accent.copy(alpha = 0.20f)
    val durationLabel = "${content.duration.inWholeSeconds.coerceAtLeast(1)}″"
    val seekingTime = (content.duration.inWholeMilliseconds * progress).roundToLong().milliseconds.formatVoiceTimestamp()
    val a11y = stringResource(CommonStrings.common_voice_message)
    val actionLabel = stringResource(if (playing) CommonStrings.a11y_pause else CommonStrings.a11y_play)
    val speedLabel = stringResource(R.string.yizhe_voice_change_speed, state.playbackSpeed.toString())
    val forwardLabel = stringResource(R.string.yizhe_voice_forward)
    val backwardLabel = stringResource(R.string.yizhe_voice_backward)
    Box(
        modifier = modifier
            .width((148 + content.duration.inWholeSeconds.coerceIn(0, 60).toInt() * 2).dp)
            .height(48.dp)
            .drawBehind {
                if (state.showCursor || seekPreview != null || progress > 0f) {
                    drawRect(fill, size = Size(size.width * progress, size.height))
                }
            }
            .pointerInput(content.eventId, enabled, isTalkbackActive) {
                if (!isTalkbackActive) detectTapGestures(
                    onTap = { playPause() },
                    onDoubleTap = { changeSpeed() },
                    onLongPress = onLongClick?.let { { _: Offset -> currentLongClick?.invoke() } },
                )
            }
            .pointerInput(content.eventId, canSeek, isTalkbackActive) {
                if (canSeek && !isTalkbackActive) detectHorizontalDragGestures(
                    onDragStart = { seekPreview = (it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        seekPreview = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        seekPreview?.let { currentState.eventSink(VoiceMessageEvent.Seek(it)) }
                        seekPreview = null
                    },
                    onDragCancel = { seekPreview = null },
                )
            }
            .clearAndSetSemantics {
                contentDescription = "$a11y $durationLabel"
                stateDescription = "${state.playbackSpeed}×" + if (seekPreview != null) " $seekingTime" else ""
                if (!enabled) disabled() else onClick(actionLabel) { playPause(); true }
                if (onLongClick != null) onLongClick { currentLongClick?.invoke(); true }
                customActions = if (enabled) listOf(
                    CustomAccessibilityAction(speedLabel) { changeSpeed(); true },
                ) + if (canSeek) listOf(
                    CustomAccessibilityAction(forwardLabel) {
                        val step = 5000f / content.duration.inWholeMilliseconds.coerceAtLeast(1)
                        currentState.eventSink(VoiceMessageEvent.Seek((progress + step).coerceIn(0f, 1f))); true
                    },
                    CustomAccessibilityAction(backwardLabel) {
                        val step = 5000f / content.duration.inWholeMilliseconds.coerceAtLeast(1)
                        currentState.eventSink(VoiceMessageEvent.Seek((progress - step).coerceIn(0f, 1f))); true
                    },
                ) else emptyList() else emptyList()
            }
            .onSizeChanged { onContentLayoutChange(ContentAvoidingLayoutData(contentWidth = it.width, contentHeight = it.height)) },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        ) {
            if (!isMine && seekPreview == null) VoiceStatusIcon(state.buttonType, accent, animationFrame, false)
            if (!isMine && seekPreview == null) Spacer(Modifier.width(10.dp))
            Text(
                text = if (seekPreview != null) "$seekingTime / ${content.duration.formatVoiceTimestamp()}" else durationLabel,
                color = ElementTheme.colors.textPrimary,
                style = ElementTheme.typography.fontBodyLgRegular,
                maxLines = 1,
                modifier = if (seekPreview != null) Modifier.fillMaxWidth() else Modifier,
                textAlign = if (seekPreview != null) TextAlign.Center else TextAlign.Unspecified,
            )
            if (seekPreview == null) Spacer(Modifier.weight(1f))
            if (state.playbackSpeed != 1f && seekPreview == null) {
                Text("${state.playbackSpeed.toString().removeSuffix(".0")}×", color = accent, style = ElementTheme.typography.fontBodySmMedium)
            }
            if (isMine && seekPreview == null) { Spacer(Modifier.width(10.dp)); VoiceStatusIcon(state.buttonType, accent, animationFrame, true) }
        }
        if (state.hasBeenPlayed == false && !playing && !isMine) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(ElementTheme.colors.bgCriticalPrimary, CircleShape))
    }
}

@Composable
private fun VoiceStatusIcon(button: VoiceMessageState.ButtonType, color: Color, frame: Int, isMine: Boolean) {
    when (button) {
        VoiceMessageState.ButtonType.Downloading -> CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        VoiceMessageState.ButtonType.Retry -> Icon(CompoundIcons.Restart(), contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        else -> Canvas(Modifier.size(22.dp).graphicsLayer { scaleX = if (isMine) -1f else 1f }) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(size.width * 0.17f, size.height / 2))
            for (index in 0..frame) {
                val radius = size.width * (0.30f + index * 0.23f)
                drawArc(color, -42f, 84f, false, Offset(size.width * 0.17f - radius, size.height / 2 - radius), Size(radius * 2, radius * 2), style = stroke)
            }
        }
    }
}

internal fun Duration.formatVoiceTimestamp(): String {
    val seconds = inWholeSeconds.coerceAtLeast(0)
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

open class TimelineItemVoiceViewParametersProvider : PreviewParameterProvider<TimelineItemVoiceViewParameters> {
    private val voiceMessageStateProvider = VoiceMessageStateProvider()
    private val timelineItemVoiceContentProvider = TimelineItemVoiceContentProvider()
    override val values: Sequence<TimelineItemVoiceViewParameters>
        get() = timelineItemVoiceContentProvider.values.flatMap { content ->
            voiceMessageStateProvider.values.map { state ->
                TimelineItemVoiceViewParameters(
                    state = state,
                    content = content,
                )
            }
        }
}

data class TimelineItemVoiceViewParameters(
    val state: VoiceMessageState,
    val content: TimelineItemVoiceContent,
)

@PreviewsDayNight
@Composable
internal fun TimelineItemVoiceViewPreview(
    @PreviewParameter(TimelineItemVoiceViewParametersProvider::class) timelineItemVoiceViewParameters: TimelineItemVoiceViewParameters,
) = ElementPreview {
    TimelineItemVoiceView(
        state = timelineItemVoiceViewParameters.state,
        content = timelineItemVoiceViewParameters.content,
        onContentLayoutChange = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemVoiceViewUnifiedPreview() = ElementPreview {
    val timelineItemVoiceViewParametersProvider = TimelineItemVoiceViewParametersProvider()
    Column {
        timelineItemVoiceViewParametersProvider.values.forEach {
            TimelineItemVoiceView(
                state = it.state,
                content = it.content,
                onContentLayoutChange = {},
            )
        }
    }
}

