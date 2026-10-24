/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services.feedback

import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Real service entry must supply the authorised enrollment target and production port together. */
internal class DiscoveryFeedbackNode(
    buildContext: BuildContext,
    private val target: FeedbackTarget,
    private val port: DiscoveryFeedbackPort,
    private val accountRevision: StateFlow<Long>,
    private val resumed: StateFlow<Boolean>,
    private val onBack: () -> Unit,
    private val onSaved: (FeedbackSaved) -> Unit,
) : Node(buildContext) {
    @Composable override fun View(modifier: Modifier) {
        val account by accountRevision.collectAsState()
        val foreground by resumed.collectAsState()
        val visible = LocalNodeTargetVisibility.current
        if (!foreground || !visible) return
        val controller = remember(account) { DiscoveryFeedbackController(target, port) }
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        var delivered by remember(controller) { mutableStateOf<FeedbackSaved?>(null) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller, state.receipt) {
            if (foreground && visible && state.phase in setOf(FeedbackPhase.LOADING, FeedbackPhase.SAVED)) controller.load()
        }
        BackHandler(enabled = state.receipt != null) { scope.launch { controller.finish(onBack) } }
        LaunchedEffect(controller, state.receipt) {
            val receipt = state.receipt
            if (foreground && visible && receipt != null && receipt != delivered) { delivered = receipt; onSaved(receipt) }
        }
        if (foreground && visible) DiscoveryFeedbackView(state, controller::edit,
            onSubmit = { scope.launch { controller.submit() } }, onRecover = { scope.launch { controller.recover() } },
            onReload = { scope.launch { controller.load() } },
            onBack = { if (state.receipt == null) onBack() else scope.launch { controller.finish(onBack) } }, modifier = modifier,
            onFinish = { scope.launch { controller.finish(onBack) } }, onContinueEditing = { scope.launch { controller.continueEditing() } },
            onReloadConflict = { scope.launch { controller.reloadConflict() } }, onResolveConflict = controller::resolveConflict)
    }
}
