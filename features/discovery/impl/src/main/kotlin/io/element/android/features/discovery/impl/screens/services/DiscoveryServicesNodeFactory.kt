/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services

import androidx.compose.runtime.*
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.node
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.DiscoveryRootNavigation
import io.element.android.features.discovery.impl.DiscoveryRootTab
import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import io.element.android.features.discovery.impl.screens.services.feedback.DefaultDiscoveryFeedbackPort
import io.element.android.features.discovery.impl.screens.services.feedback.DiscoveryFeedbackNode
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

@Inject
class DiscoveryServicesNodeFactory internal constructor(private val reader: DiscoveryServicesReader,
    private val feedback: DefaultDiscoveryFeedbackPort,
    gate: AndroidDiscoveryIdentityGate, @SessionCoroutineScope scope: CoroutineScope) {
    private val accountRevision = gate.scope.runningFold(0L) { revision, _ -> revision + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun root(buildContext: BuildContext, resumed: StateFlow<Boolean>, onRoot: (DiscoveryRootTab) -> Unit,
        onTeachers: (FeedbackTarget) -> Unit, onFeedback: (FeedbackTarget) -> Unit, onShop: () -> Unit, onOrders: () -> Unit) = node(buildContext) { modifier ->
        val account by accountRevision.collectAsState(); val foreground by resumed.collectAsState()
        if (foreground && LocalNodeTargetVisibility.current) key(account) {
            DiscoveryRootNavigation(DiscoveryRootTab.SERVICES, onRoot, modifier) {
                DiscoveryServicesView(reader, feedback, onTeachers, onFeedback, onShop, onOrders)
            }
        }
    }

    internal fun teachers(buildContext: BuildContext, resumed: StateFlow<Boolean>, target: FeedbackTarget, onBack: () -> Unit) = node(buildContext) { modifier ->
        val account by accountRevision.collectAsState(); val foreground by resumed.collectAsState()
        if (foreground && LocalNodeTargetVisibility.current) key(account) { DiscoveryTeachersView(reader, target, onBack, modifier) }
    }

    internal fun feedback(buildContext: BuildContext, resumed: StateFlow<Boolean>, target: FeedbackTarget, onBack: () -> Unit,
        onSaved: () -> Unit) = DiscoveryFeedbackNode(buildContext, target, feedback, accountRevision, resumed, onBack, { onSaved() })
}
