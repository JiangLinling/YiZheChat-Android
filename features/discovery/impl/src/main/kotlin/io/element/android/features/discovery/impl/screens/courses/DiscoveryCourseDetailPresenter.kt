/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DiscoveryCourseDetailState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val promo: DiscoveryPromo? = null,
    val onRetry: () -> Unit = {},
)

@Inject
class DiscoveryCourseDetailPresenter internal constructor(private val repository: DiscoveryCourseDetailRepository) {
    @Composable
    internal fun present(courseId: Long, sessionId: Long?, active: Boolean, resumeRevision: Long): DiscoveryCourseDetailState {
        var revision by remember(courseId, sessionId, active, resumeRevision) { mutableIntStateOf(0) }
        var state by remember(courseId, sessionId, active, resumeRevision) { mutableStateOf(DiscoveryCourseDetailState()) }
        LaunchedEffect(courseId, sessionId, active, resumeRevision, revision) {
            if (!active) return@LaunchedEffect
            state = DiscoveryCourseDetailState()
            state = try {
                val promo = if (sessionId == null) repository.loadCourse(courseId) else repository.load(courseId, sessionId)
                currentCoroutineContext().ensureActive()
                DiscoveryCourseDetailState(loading = false, promo = promo)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                DiscoveryCourseDetailState(loading = false, failed = true)
            }
        }
        return state.copy(onRetry = { if (active && !state.loading) revision++ })
    }
}
