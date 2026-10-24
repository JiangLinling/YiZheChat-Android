/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Inject
class DiscoveryPersonalPresenter internal constructor(private val repository: DiscoveryArticlesRepository) {
    private data class Request(val page: Int = 1, val revision: Int = 0)

    @Composable
    fun present(kind: DiscoveryPersonalKind, active: Boolean = true, resumeRevision: Long = 0): DiscoveryPersonalState {
        // Hidden retained routes drop private rows; activation always fetches the first page again.
        var state by remember(kind, active, resumeRevision) { mutableStateOf(DiscoveryPersonalState(kind, eventSink = {})) }
        var request by remember(kind, active, resumeRevision) { mutableStateOf(Request()) }
        val current = request
        LaunchedEffect(kind, active, resumeRevision, current) {
            if (!active) return@LaunchedEffect
            state = state.copy(loading = true, failed = false, items = if (current.page == 1) emptyList() else state.items)
            try {
                val result = repository.personal(kind, current.page)
                currentCoroutineContext().ensureActive()
                state = state.copy(loading = false, page = current.page, total = result.total,
                    items = (if (current.page == 1) result.items else state.items + result.items).distinctBy { it.id })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                val denied = (failure as? DiscoveryFailure)?.status in setOf(401, 403, 409)
                state = state.copy(loading = false, failed = true, items = if (denied) emptyList() else state.items)
            }
        }
        return state.copy(eventSink = { event ->
            if (active) Snapshot.withMutableSnapshot {
                when (event) {
                    DiscoveryPersonalEvent.Refresh -> {
                        state = state.copy(loading = true, failed = false, items = emptyList())
                        request = Request(revision = request.revision + 1)
                    }
                    DiscoveryPersonalEvent.Retry -> if (!state.loading) {
                        state = state.copy(loading = true)
                        request = request.copy(page = if (state.items.isEmpty()) 1 else request.page, revision = request.revision + 1)
                    }
                    DiscoveryPersonalEvent.More -> if (!state.loading && !state.failed && state.items.size < state.total && request.page < 1000) {
                        state = state.copy(loading = true)
                        request = request.copy(page = request.page + 1)
                    }
                }
            }
        })
    }
}
