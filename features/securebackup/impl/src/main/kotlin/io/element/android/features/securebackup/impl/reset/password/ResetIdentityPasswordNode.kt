/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.reset.password

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.securebackup.impl.reset.ResetIdentityFlowManager
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.encryption.IdentityPasswordResetHandle

@ContributesNode(SessionScope::class)
@AssistedInject
class ResetIdentityPasswordNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    coroutineDispatchers: CoroutineDispatchers,
    resetIdentityFlowManager: ResetIdentityFlowManager,
) : Node(buildContext, plugins = plugins) {
    data class Inputs(val handle: IdentityPasswordResetHandle) : NodeInputs

    interface Callback : Plugin {
        fun onResetSucceeded()
    }

    private val callback: Callback = callback()

    private val presenter = ResetIdentityPasswordPresenter(
        identityPasswordResetHandle = inputs<Inputs>().handle,
        dispatchers = coroutineDispatchers,
        completeReset = resetIdentityFlowManager::completeReset,
        onResetSucceeded = callback::onResetSucceeded,
    )

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        ResetIdentityPasswordView(
            state = state,
            onBack = ::navigateUp
        )
    }
}
