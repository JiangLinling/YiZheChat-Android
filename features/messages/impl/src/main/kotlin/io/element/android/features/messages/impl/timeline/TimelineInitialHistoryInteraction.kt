/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect

/** Reuses real list drag interactions. Layout, keyboard and programmatic scroll completion are not browse intent. */
@Composable
internal fun TimelineInitialHistoryInteraction(
    viewportScopeId: String,
    control: InitialHistoryPresentationControl?,
    lazyListState: LazyListState,
    userVisible: Boolean,
    isLive: Boolean,
    programmaticScrollInProgress: Boolean,
    focusNavigationInProgress: Boolean,
    eventSink: (TimelineEvent) -> Unit,
) {
    var wasVisible by remember(viewportScopeId) { mutableStateOf(false) }
    val latestSink by rememberUpdatedState(eventSink)
    val canBrowse by rememberUpdatedState(userVisible && isLive && !programmaticScrollInProgress && !focusNavigationInProgress)
    LaunchedEffect(viewportScopeId, control, userVisible, isLive, focusNavigationInProgress) {
        if (userVisible) wasVisible = true
        if (control != null && (wasVisible && !userVisible || !isLive || focusNavigationInProgress)) {
            latestSink(TimelineEvent.RetireInitialHistoryPresentation(control))
        }
    }
    LaunchedEffect(viewportScopeId, control, lazyListState) {
        if (control == null) return@LaunchedEffect
        lazyListState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && canBrowse) {
                latestSink(TimelineEvent.RetireInitialHistoryPresentation(control))
            }
        }
    }
}
