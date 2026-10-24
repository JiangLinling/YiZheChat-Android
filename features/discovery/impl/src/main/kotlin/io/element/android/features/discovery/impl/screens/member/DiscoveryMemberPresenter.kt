/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class DiscoveryMemberPresenter(private val reader: DiscoveryMemberReader) {
    @Composable
    fun present(route: MemberRoute, personId: Long, active: Boolean, resumeRevision: Long): DiscoveryMemberState {
        val scope = rememberCoroutineScope()
        var refresh by remember(route, personId, active, resumeRevision) { mutableIntStateOf(0) }
        var state by remember(route, personId, active, resumeRevision) {
            mutableStateOf(DiscoveryMemberState(route, reload = {}, more = {}))
        }
        // A captured callback from a previous account/route must never publish into the new screen.
        val epoch = remember(route, personId, active, resumeRevision, refresh) { Any() }
        val currentEpoch by rememberUpdatedState(epoch)
        LaunchedEffect(epoch) {
            if (!active) return@LaunchedEffect
            state = DiscoveryMemberState(route, reload = {}, more = {})
            try {
                val page = reader.load(route, personId)
                currentCoroutineContext().ensureActive()
                require(page.matches(route, personId))
                if (epoch === currentEpoch) state = state.copy(loading = false, page = page)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (epoch === currentEpoch) state = state.copy(loading = false, failed = true, page = null)
            }
        }
        return state.copy(
            reload = { if (active && epoch === currentEpoch && !state.loading) refresh++ },
            more = {
                val page = state.page as? MemberPage.Orders
                val cursor = page?.nextCursor
                if (active && epoch === currentEpoch && !state.loading && !state.loadingMore && page != null && cursor != null) {
                    state = state.copy(loadingMore = true, moreFailed = false)
                    scope.launch {
                        try {
                            val next = reader.load(route, personId, cursor)
                            currentCoroutineContext().ensureActive()
                            require(next is MemberPage.Orders && next.matches(route, personId) && next.nextCursor != cursor)
                            if (epoch === currentEpoch) state = state.copy(
                                loadingMore = false,
                                page = next.copy(rows = (page.rows + next.rows).distinctBy { it.key }),
                            )
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            if (epoch === currentEpoch) state = state.copy(loadingMore = false, moreFailed = true)
                        }
                    }
                }
            },
        )
    }
}
