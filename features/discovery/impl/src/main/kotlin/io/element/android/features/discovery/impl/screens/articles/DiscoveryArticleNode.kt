/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import kotlinx.coroutines.flow.StateFlow

internal class DiscoveryArticleNode(
    buildContext: BuildContext,
    private val articleId: Long,
    private val presenter: DiscoveryArticlesPresenter,
    readingPresenter: DiscoveryReadingPresenter,
    private val commentsPresenter: DiscoveryCommentsPresenter,
    private val resumed: StateFlow<Boolean>,
    private val resumeRevision: StateFlow<Long>,
    private val openComments: Boolean,
    private val onBack: () -> Unit,
    private val onAuthor: (Long) -> Unit,
    private val onTag: (Long) -> Unit,
) : Node(buildContext) {
    private val reading = readingPresenter.create(articleId)

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(onDestroy = { reading.close() })
    }

    @Composable
    override fun View(modifier: Modifier) {
        val foreground by resumed.collectAsState()
        val visible = LocalNodeTargetVisibility.current && foreground
        val revision by resumeRevision.collectAsState()
        val detail = presenter.detail(articleId, visible, revision)
        val comments = commentsPresenter.present(articleId, visible && detail.article != null, revision)
        LaunchedEffect(detail.contentRevision) {
            if (detail.contentRevision > 0) comments.eventSink(DiscoveryPersonalEvent.Refresh)
        }
        val pending by reading.pending.collectAsState()
        DisposableEffect(visible, detail.article != null) {
            reading.visible(visible && detail.article != null)
            onDispose { reading.visible(false) }
        }
        DiscoveryArticleDetailView(detail.copy(readingPending = pending, retryReading = reading::retry), onBack, modifier, onAuthor, onTag, comments, openComments)
    }
}
