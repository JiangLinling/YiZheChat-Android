/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.node
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

@Inject
class DiscoveryMallNodeFactory internal constructor(
    private val reader: DiscoveryMallReader,
    private val port: DiscoveryMallPort,
    gate: AndroidDiscoveryIdentityGate,
    @SessionCoroutineScope scope: CoroutineScope,
) {
    private val accountRevision = gate.scope.runningFold(0L) { revision, _ -> revision + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    private fun page(buildContext: BuildContext, resumed: StateFlow<Boolean>, content: @Composable (Modifier) -> Unit) = node(buildContext) { modifier ->
        val account by accountRevision.collectAsState()
        val foreground by resumed.collectAsState()
        if (foreground && LocalNodeTargetVisibility.current) key(account) { content(modifier) }
    }

    internal fun catalog(buildContext: BuildContext, resumed: StateFlow<Boolean>, onBack: () -> Unit,
        onProduct: (Long) -> Unit, onOrders: () -> Unit, onResult: () -> Unit) = page(buildContext, resumed) {
        DiscoveryMallCatalog(reader, port, onBack, onProduct, onOrders, onResult, it)
    }

    internal fun product(buildContext: BuildContext, resumed: StateFlow<Boolean>, productId: Long, onBack: () -> Unit,
        onCheckout: (Long, Int) -> Unit) = page(buildContext, resumed) {
        DiscoveryMallProduct(reader, productId, onBack, onCheckout, it)
    }

    internal fun checkout(buildContext: BuildContext, resumed: StateFlow<Boolean>, skuId: Long, quantity: Int, addressId: Long?,
        onBack: () -> Unit, onAddress: () -> Unit, onResult: () -> Unit) = page(buildContext, resumed) {
        DiscoveryMallCheckout(reader, port, skuId, quantity, addressId, onBack, onAddress, onResult, it)
    }

    internal fun orders(buildContext: BuildContext, resumed: StateFlow<Boolean>, onBack: () -> Unit,
        onOrder: (Long) -> Unit, onResult: () -> Unit) = page(buildContext, resumed) {
        DiscoveryMallOrders(reader, port, onBack, onOrder, onResult, it)
    }

    internal fun order(buildContext: BuildContext, resumed: StateFlow<Boolean>, orderId: Long, onBack: () -> Unit,
        onAfterSale: (Long?) -> Unit, onResult: () -> Unit) = page(buildContext, resumed) {
        DiscoveryMallOrder(reader, port, orderId, onBack, onAfterSale, onResult, it)
    }

    internal fun afterSale(buildContext: BuildContext, resumed: StateFlow<Boolean>, orderId: Long, afterSaleId: Long?,
        onBack: () -> Unit, onResult: () -> Unit) = page(buildContext, resumed) {
        DiscoveryMallAfterSale(reader, port, orderId, afterSaleId, onBack, onResult, it)
    }

    internal fun result(buildContext: BuildContext, resumed: StateFlow<Boolean>, onBack: () -> Unit,
        onOrder: (Long) -> Unit) = page(buildContext, resumed) {
        DiscoveryMallResult(port, onBack, onOrder, it)
    }
}

@Composable
internal fun rememberMallOperation(port: DiscoveryMallPort): DiscoveryMallOperationController {
    val controller = remember(port) { DiscoveryMallOperationController(port) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.recover() }
    return controller
}
