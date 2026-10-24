/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Caller refreshes the originating identity/child list or P16 options after the acknowledged save. */
internal class DiscoveryPersonEditorNode(
    buildContext: BuildContext,
    private val target: PersonEditTarget,
    private val port: DiscoveryPersonEditorPort,
    private val accountRevision: StateFlow<Long>,
    private val resumed: StateFlow<Boolean>,
    private val today: () -> LocalDate,
    private val onBack: () -> Unit,
    private val onSaved: (PersonEditResult, Boolean) -> Unit,
    private val onAvatar: (() -> Unit)?,
    private val allowUse: Boolean = true,
) : Node(buildContext) {
    @Composable override fun View(modifier: Modifier) {
        val account by accountRevision.collectAsState()
        val foreground by resumed.collectAsState()
        val visible = LocalNodeTargetVisibility.current
        // Leaving this composition cancels its load/save jobs and discards only the in-memory form.
        // Any staged operation or confirmed result remains in the existing account Vault journal.
        if (!foreground || !visible) return
        val controller = remember(account) { DiscoveryPersonEditorController(target, port, today) }
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller, visible) {
            if (!visible || !foreground) return@LaunchedEffect
            when (state.phase) {
                PersonEditPhase.LOADING, PersonEditPhase.FAILED -> controller.load()
                PersonEditPhase.UNCERTAIN -> controller.recover()
                else -> Unit
            }
        }
        // Keep the durable result while it is shown, including after process recreation.
        // Back/Done/Use are explicit acknowledgements; merely rendering the result is not.
        BackHandler(enabled = state.phase == PersonEditPhase.SAVED) { scope.launch { controller.finish(false, onSaved) } }
        if (foreground && visible) DiscoveryPersonEditorView(state, today(), controller::edit,
            onSave = { use -> scope.launch { controller.save(use) } },
            onRecover = { scope.launch {
                if (state.phase == PersonEditPhase.SAVED) controller.finish(false, onSaved)
                else controller.recover()
            } }, onRetry = { scope.launch { controller.load() } },
            onBack = { if (state.phase == PersonEditPhase.SAVED) scope.launch { controller.finish(false, onSaved) } else onBack() },
            onAvatar = onAvatar, modifier = modifier, allowUse = allowUse,
            onFinish = { use -> scope.launch { controller.finish(use, onSaved) } })
    }
}
