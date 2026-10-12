/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.chatsecurity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.di.SessionScope

@ContributesNode(SessionScope::class)
@AssistedInject
class ChatSecurityNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: ChatSecurityPresenter,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun navigateToEnterRecoveryKey()
        fun navigateToSecureBackup()
    }

    private val callback: Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        ChatSecurityView(
            state = presenter.present(),
            onBackClick = ::navigateUp,
            onRestoreHistoryClick = callback::navigateToEnterRecoveryKey,
            onEncryptionClick = callback::navigateToSecureBackup,
            modifier = modifier,
        )
    }
}
