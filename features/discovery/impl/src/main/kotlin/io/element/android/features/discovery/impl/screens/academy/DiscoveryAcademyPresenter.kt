/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.academy

import androidx.compose.runtime.*
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.DiscoveryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Inject
class DiscoveryAcademyPresenter internal constructor(private val repository: DiscoveryAcademyRepository) {
    @Composable
    fun present(articleId: Long, active: Boolean = true, resumeRevision: Long = 0): DiscoveryAcademyState {
        var state by remember(articleId, active, resumeRevision) { mutableStateOf(DiscoveryAcademyState(reload = {})) }
        var revision by remember(articleId, active, resumeRevision) { mutableIntStateOf(0) }
        LaunchedEffect(articleId, active, resumeRevision, revision) {
            if (!active) return@LaunchedEffect
            state = DiscoveryAcademyState(reload = {})
            try {
                val result = repository.read(articleId)
                currentCoroutineContext().ensureActive()
                state = DiscoveryAcademyState(loading = false, content = result, reload = {})
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                state = DiscoveryAcademyState(loading = false, failed = true, missing = (failure as? DiscoveryFailure)?.status == 404, reload = {})
            }
        }
        return state.copy(reload = { if (active && !state.loading) revision++ })
    }
}
