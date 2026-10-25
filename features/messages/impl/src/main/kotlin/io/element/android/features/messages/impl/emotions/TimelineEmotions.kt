/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.emotions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.features.messages.impl.timeline.TimelineState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import kotlinx.coroutines.delay

internal val LocalEmotionRequest = staticCompositionLocalOf<Pair<String, Long>?> { null }
internal val LocalEmotionEventId = staticCompositionLocalOf<String?> { null }

@Composable
internal fun EmotionBubble(emotion: ChatEmotion, modifier: Modifier) {
    val request = LocalEmotionRequest.current
    if (emotion.tier == 0 && request?.first != null && request.first == LocalEmotionEventId.current) {
        key(request) { EmotionPlayback(emotion, EmotionOptions(sound = false, vibration = false), modifier, scene = false, onFinished = {}) }
    } else EmotionArtwork(emotion, 0f, modifier)
}

internal fun TimelineItem.Event.chatEmotion(): ChatEmotion? {
    val text = content as? TimelineItemTextContent ?: return null
    if (text.isBurnAfterRead || text.isProtectedContentShell || text.isTerminalProtectedContentShell || text.isEdited || failedToSend) return null
    if (text.formattedBody.toString() != text.body) return null
    return ChatEmotions.fromBody(text.body)
}

internal class TimelineEmotionState {
    var request by mutableStateOf<Pair<String, Long>?>(null)
    fun replay(event: TimelineItem.Event): Boolean {
        if (event.chatEmotion() == null) return false
        request = event.displayId.value to System.nanoTime()
        return true
    }
}

@Composable
internal fun TimelineEmotions(state: TimelineState, playback: TimelineEmotionState, scopeId: String, liveEdge: Boolean, blocked: Boolean,
    protection: TimelineProtectionState, measurementCurrent: Boolean = true) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val options = EmotionOptions()
    val gate = remember(scopeId) { EmotionArrivalGate(System.currentTimeMillis()) }
    DisposableEffect(lifecycleOwner, gate) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) playback.request = null
            if (event == Lifecycle.Event.ON_RESUME) gate.resume(System.currentTimeMillis())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); playback.request = null }
    }
    val events = remember(state.timelineItems) { state.timelineItems.filterIsInstance<TimelineItem.Event>() }
    fun eligible(event: TimelineItem.Event) = event.chatEmotion() != null &&
        !protection.hideMediaContent(event.eventId, event.isMine) &&
        event.eventId !in state.destroyingBurnAfterReadEventIds && event.eventId !in state.destroyingMessageRemovalEventIds &&
        event.eventId !in state.pendingMessageRemovalEventIds
    LaunchedEffect(state.timelineItems, resumed, options.autoplay, liveEdge, blocked, protection, measurementCurrent) {
        if (!resumed || blocked) playback.request = null
        val autoplayAllowed = resumed && !blocked && options.autoplay && state.isLive
        // Never consume a fresh arrival using the previous list's geometry. An empty room still
        // initializes the baseline, and background/blocked/history observations still retire arrivals.
        if (autoplayAllowed && !measurementCurrent && events.isNotEmpty()) return@LaunchedEffect
        val id = gate.observe(events.map { event -> EmotionArrival(event.displayId.value, event.sentTimeMillis,
            eligible = eligible(event) && (event.origin == TimelineItemEventOrigin.LOCAL || event.origin == TimelineItemEventOrigin.SYNC),
            awaitingLiveDecryption = event.origin == TimelineItemEventOrigin.SYNC &&
                !event.content.isBurnAfterRead && !event.content.isTerminalProtectedContentShell &&
                event.content.isProtectedContentShell,
        ) },
            // A new eligible live message replaces the current effect instead of being consumed while it is busy.
            System.currentTimeMillis(), autoplayAllowed && liveEdge)
        if (id != null) playback.request = id to System.nanoTime()
    }
    val request = playback.request
    val source = events.firstOrNull { it.displayId.value == request?.first && eligible(it) }
    if (source == null || !resumed || blocked) {
        if (request != null) LaunchedEffect(request) {
            if (playback.request == request) playback.request = null
        }
        return
    }
    val emotion = source.chatEmotion() ?: return
    if (emotion.tier == 0) {
        LaunchedEffect(request) {
            delay(emotion.durationMillis.toLong())
            if (playback.request == request) playback.request = null
        }
        return
    }
    key(request) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmotionPlayback(emotion, options, Modifier.fillMaxSize(), scene = true, onFinished = {
                if (playback.request == request) playback.request = null
            })
        }
    }
}
