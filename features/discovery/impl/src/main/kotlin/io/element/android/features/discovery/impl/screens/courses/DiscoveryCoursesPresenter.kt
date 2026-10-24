/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.compose.runtime.*
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Inject
class DiscoveryCoursesPresenter internal constructor(private val repository: DiscoveryCoursesRepository) {
    @Composable
    fun present(active: Boolean, resumeRevision: Long): DiscoveryCoursesState {
        var revision by remember(active, resumeRevision) { mutableStateOf(0) }
        var state by remember(active, resumeRevision) { mutableStateOf(DiscoveryCoursesState(eventSink = {})) }
        LaunchedEffect(active, resumeRevision, revision) {
            if (!active) return@LaunchedEffect
            state = state.copy(loading = true, failed = false, catalog = null)
            state = try {
                val catalog = repository.load()
                currentCoroutineContext().ensureActive()
                state.copy(loading = false, catalog = catalog, courseId = state.courseId?.takeIf { id -> catalog.courses.any { it.id == id } })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                state.copy(loading = false, failed = true, catalog = null)
            }
        }
        return state.copy(eventSink = { event ->
            if (active) when (event) {
                DiscoveryCoursesEvent.Reload -> if (!state.loading) revision++
                is DiscoveryCoursesEvent.Filter -> state = state.copy(filter = event.value)
                is DiscoveryCoursesEvent.Search -> state = state.copy(query = event.query.take(80))
                is DiscoveryCoursesEvent.Course -> if (event.id == null || state.catalog?.courses?.any { it.id == event.id } == true) {
                    state = state.copy(courseId = event.id)
                }
            }
        })
    }
}
