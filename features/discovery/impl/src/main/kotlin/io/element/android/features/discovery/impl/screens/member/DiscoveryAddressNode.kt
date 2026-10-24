/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class DiscoveryAddressNode(
    buildContext: BuildContext,
    private val port: DiscoveryAddressPort,
    private val accountRevision: StateFlow<Long>,
    private val resumed: StateFlow<Boolean>,
    private val chooseForOrder: Boolean,
    private val onBack: () -> Unit,
    private val onSelected: (Long) -> Unit,
) : Node(buildContext) {
    @Composable override fun View(modifier: Modifier) {
        val account by accountRevision.collectAsState()
        val foreground by resumed.collectAsState()
        val visible = LocalNodeTargetVisibility.current
        if (!foreground || !visible) return
        val controller = remember(account) { DiscoveryAddressController(port) }
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        var returnAfterSave by remember(controller) { mutableStateOf(false) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller, visible) {
            if (!foreground || !visible) return@LaunchedEffect
            when (state.phase) {
                AddressPhase.LOADING, AddressPhase.READY, AddressPhase.SAVED, AddressPhase.FAILED -> controller.load()
                AddressPhase.UNCERTAIN -> controller.recover()
                else -> Unit
            }
        }
        LaunchedEffect(controller, visible, state.savedId) {
            if (foreground && visible && state.phase == AddressPhase.SAVED) controller.load()
        }
        LaunchedEffect(controller, visible, state.savedId, state.phase) {
            val id = state.savedId
            if (foreground && visible && chooseForOrder && returnAfterSave && id != null && controller.canChoose(id)) {
                returnAfterSave = false
                onSelected(id)
            }
        }
        BackHandler(enabled = visible && state.phase == AddressPhase.EDITING) { controller.cancelEdit() }
        if (foreground && visible) DiscoveryAddressView(state, chooseForOrder, controller::edit, controller::begin,
            onSave = { returnAfterSave = chooseForOrder; scope.launch { controller.save() } },
            onRecover = { scope.launch { controller.recover() } }, onReload = { scope.launch { controller.load() } },
            onBack = { if (state.phase == AddressPhase.EDITING) controller.cancelEdit() else onBack() },
            onPaste = controller::paste, onParse = controller::parse, onName = controller::useName,
            onChoose = { id -> if (chooseForOrder && controller.canChoose(id)) onSelected(id) }, modifier = modifier)
    }
}
