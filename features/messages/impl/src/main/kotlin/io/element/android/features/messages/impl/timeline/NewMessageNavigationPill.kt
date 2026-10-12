/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

/** Countdown lives in this small overlay, so ticks never recompose the timeline rows. */
@Composable
internal fun NewMessageNavigationPill(
    scopeId: String,
    state: TimelineState,
    showLatest: Boolean,
    blocked: Boolean,
    onLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigation = state.newMessageNavigation
    var remaining by rememberSaveable(scopeId, navigation.firstNewEventId) { mutableStateOf(navigation.hintRemainingMillis) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val showFirst = navigation.firstNewEventId != null && navigation.hintActivated && remaining > 0L
    val latestPressed by rememberUpdatedState(pressed)
    val latestSink by rememberUpdatedState(state.eventSink)
    LaunchedEffect(showFirst, blocked, resumed, state.navigatingToFirstNewMessage) {
        if (!showFirst || blocked || !resumed || state.navigatingToFirstNewMessage) return@LaunchedEffect
        var previous = SystemClock.elapsedRealtime()
        var wasUsable = power?.isInteractive != false && keyguard?.isKeyguardLocked != true
        var used = 0L
        try {
            while (remaining > 0L) {
                delay(100L)
                val now = SystemClock.elapsedRealtime()
                val usable = power?.isInteractive != false && keyguard?.isKeyguardLocked != true && !latestPressed
                if (usable && wasUsable) {
                    val elapsed = (now - previous).coerceAtMost(remaining)
                    remaining -= elapsed
                    used += elapsed
                }
                previous = now
                wasUsable = usable
            }
        } finally {
            if (used > 0L) latestSink(TimelineEvent.NewMessageHintElapsed(used))
        }
    }
    val first = showFirst || state.navigatingToFirstNewMessage
    val baseLabel = stringResource(when {
        state.navigatingToFirstNewMessage -> R.string.screen_messages_locating_new_message
        first -> R.string.screen_messages_view_new_messages
        else -> R.string.screen_messages_latest_messages
    })
    val incomingCount = if (first) 0 else incomingMessagesSinceLastSeen(state.timelineItems, navigation.latestSeenEventId)
    val label = if (incomingCount > 0) stringResource(R.string.screen_messages_latest_messages_with_count,
        if (incomingCount > 99) "99+" else incomingCount.toString()) else baseLabel
    val description = if (incomingCount > 0) label else stringResource(if (first) R.string.a11y_messages_first_new_message else R.string.a11y_messages_latest_messages)
    AnimatedVisibility(
        visible = !blocked && (first || showLatest), modifier = modifier,
        enter = fadeIn(tween(150)), exit = fadeOut(tween(200)),
    ) {
        LightweightTimelinePill(
            text = label, first = first, loading = state.navigatingToFirstNewMessage,
            interaction = interaction,
            modifier = Modifier.testTag(if (first) "view_new_messages" else "view_latest_messages").semantics { contentDescription = description },
            onClick = { if (first) latestSink(TimelineEvent.ViewFirstNewMessage) else onLatest() },
        )
    }
}

@Composable
internal fun LightweightTimelinePill(
    text: String,
    first: Boolean,
    loading: Boolean = false,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.sizeIn(minHeight = 48.dp, minWidth = 48.dp)
            .clickable(enabled = !loading, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.background(ElementTheme.colors.bgCanvasDefault, RoundedCornerShape(18.dp))
                .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = ElementTheme.colors.iconSecondary)
            else Icon(CompoundIcons.ArrowRight(), null, Modifier.size(14.dp).rotate(if (first) -90f else 90f), tint = ElementTheme.colors.iconSecondary)
            Text(text, Modifier.padding(start = 4.dp), color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodySmRegular.copy(fontSize = 13.sp))
        }
    }
}

@io.element.android.libraries.designsystem.preview.PreviewsDayNight
@Composable
internal fun NewMessageNavigationPillPreview() = io.element.android.libraries.designsystem.preview.ElementPreview {
    androidx.compose.foundation.layout.Column {
        LightweightTimelinePill("查看新消息", first = true, onClick = {})
        LightweightTimelinePill("最新消息", first = false, onClick = {})
        LightweightTimelinePill("定位中…", first = true, loading = true, onClick = {})
    }
}
