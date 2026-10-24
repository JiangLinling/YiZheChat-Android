/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Inject
class DiscoveryHomePresenter internal constructor(private val repository: DiscoveryHomeRepository) {
    @Composable
    fun present(active: Boolean = true, resumeRevision: Long = 0): DiscoveryHomeState {
        var revision by remember(active, resumeRevision) { mutableStateOf(0) }
        var state by remember(active, resumeRevision) { mutableStateOf(DiscoveryHomeState(eventSink = {})) }
        LaunchedEffect(active, resumeRevision, revision) {
            if (!active) return@LaunchedEffect
            state = DiscoveryHomeState(eventSink = {})
            state = try {
                val content = repository.load()
                currentCoroutineContext().ensureActive()
                DiscoveryHomeState(loading = false, content = content, eventSink = {})
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                DiscoveryHomeState(loading = false, failed = true, eventSink = {})
            }
        }
        return state.copy(eventSink = { when (it) { DiscoveryHomeEvent.Reload -> if (active) revision++ } })
    }
}
