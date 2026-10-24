/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import com.bumble.appyx.core.modality.BuildContext
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

@Inject
class DiscoveryAddressNodeFactory internal constructor(
    private val port: DefaultDiscoveryAddressPort,
    gate: AndroidDiscoveryIdentityGate,
    @SessionCoroutineScope scope: CoroutineScope,
) {
    private val accountRevision = gate.scope.runningFold(0L) { revision, _ -> revision + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun create(buildContext: BuildContext, resumed: StateFlow<Boolean>, onBack: () -> Unit) =
        DiscoveryAddressNode(buildContext, port, accountRevision, resumed, chooseForOrder = false,
            onBack = onBack, onSelected = { onBack() })

    internal fun choose(buildContext: BuildContext, resumed: StateFlow<Boolean>, onBack: () -> Unit, onSelected: (Long) -> Unit) =
        DiscoveryAddressNode(buildContext, port, accountRevision, resumed, chooseForOrder = true,
            onBack = onBack, onSelected = onSelected)
}
