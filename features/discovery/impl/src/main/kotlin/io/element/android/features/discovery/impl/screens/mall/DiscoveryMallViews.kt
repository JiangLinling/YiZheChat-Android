/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.features.discovery.impl.actions.DiscoveryMallAction
import io.element.android.features.discovery.impl.util.discoveryTextFits
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MallLayout(@StringRes title: Int, onBack: () -> Unit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
            item { Text(stringResource(title), style = ElementTheme.typography.fontHeadingLgBold) }
            content()
        }
    }
}

@Composable
internal fun MallCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun MallFailure(retry: () -> Unit) {
    Text(stringResource(R.string.screen_discovery_mall_load_failed))
    TextButton(onClick = retry) { Text(stringResource(R.string.screen_discovery_mall_retry)) }
}

internal fun mallMoney(cents: Long): String = "¥${BigDecimal.valueOf(cents, 2).toPlainString()}"

@Composable
internal fun MallNotice(state: MallOperationState, onRecover: () -> Unit, onResult: () -> Unit) {
    when (state.phase) {
        MallOperationPhase.CHECKING, MallOperationPhase.SUBMITTING -> CircularProgressIndicator(Modifier.size(24.dp))
        MallOperationPhase.UNCERTAIN -> {
            Text(stringResource(R.string.screen_discovery_mall_pending))
            TextButton(onClick = onRecover) { Text(stringResource(R.string.screen_discovery_mall_recover)) }
        }
        MallOperationPhase.CONFIRMED, MallOperationPhase.RESULT, MallOperationPhase.FAILED -> {
            TextButton(onClick = onResult) { Text(stringResource(R.string.screen_discovery_mall_open_result)) }
        }
        MallOperationPhase.READY -> state.reason?.let { reason ->
            Text(if (reason == "CHECKOUT_CHANGED") stringResource(R.string.screen_discovery_mall_checkout_changed)
                else stringResource(R.string.screen_discovery_mall_action_rejected, reason))
        }
    }
}

@Composable
internal fun DiscoveryMallCatalog(reader: DiscoveryMallReader, port: DiscoveryMallPort, onBack: () -> Unit,
    onProduct: (Long) -> Unit, onOrders: () -> Unit, onResult: () -> Unit, modifier: Modifier) {
    var categories by remember { mutableStateOf<List<MallCategory>>(emptyList()) }
    var categoryFailed by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<Long?>(null) }
    var keyword by remember { mutableStateOf("") }
    var committed by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<MallProduct>>(emptyList()) }
    var total by remember { mutableLongStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val operation = rememberMallOperation(port)
    val operationState by operation.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        try { categories = reader.categories(); categoryFailed = false
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { categoryFailed = true }
    }
    LaunchedEffect(category, committed, page, retry) {
        loading = true; failed = false
        if (page == 1) { rows = emptyList(); total = 0 }
        try {
            val loaded = reader.products(category, committed, page)
            if (page > 1) require(loaded.total == total && loaded.rows.none { next -> rows.any { it.id == next.id } })
            rows = if (page == 1) loaded.rows else rows + loaded.rows
            total = loaded.total
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true }
        loading = false
    }
    MallLayout(R.string.screen_discovery_mall_shop, onBack, modifier) {
        item { TextButton(onClick = onOrders) { Text(stringResource(R.string.screen_discovery_mall_orders)) } }
        item { MallNotice(operationState, { scope.launch { operation.recover() } }, onResult) }
        item {
            OutlinedTextField(keyword, { keyword = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = !keyword.trim().discoveryTextFits(80), label = { Text(stringResource(R.string.screen_discovery_mall_search)) })
            TextButton(enabled = keyword.trim().discoveryTextFits(80), onClick = { page = 1; committed = keyword.trim(); retry++ }) {
                Text(stringResource(R.string.screen_discovery_search_title))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == null, { category = null; page = 1 }, label = { Text(stringResource(R.string.screen_discovery_mall_all)) })
                categories.forEach { row -> FilterChip(category == row.id, { category = row.id; page = 1 }, label = { Text(row.name) }) }
            }
            if (categoryFailed) MallFailure { retry++ }
        }
        items(rows, key = { it.id }) { product -> MallCard {
            Text(product.name, style = ElementTheme.typography.fontHeadingMdBold)
            product.subtitle?.let { Text(it, color = ElementTheme.colors.textSecondary) }
            Text(mallMoney(product.price), style = ElementTheme.typography.fontBodyLgMedium)
            Text(stringResource(R.string.screen_discovery_mall_stock, product.stock))
            TextButton(onClick = { onProduct(product.id) }) { Text(stringResource(R.string.screen_discovery_courses_detail)) }
        } }
        item {
            if (loading) CircularProgressIndicator()
            else if (failed) MallFailure { retry++ }
            else if (rows.isEmpty()) Text(stringResource(R.string.screen_discovery_mall_empty))
            else if (rows.size.toLong() < total) TextButton(onClick = { loading = true; page++ }) { Text(stringResource(R.string.screen_discovery_mall_load_more)) }
        }
    }
}

@Composable
internal fun DiscoveryMallProduct(reader: DiscoveryMallReader, productId: Long, onBack: () -> Unit,
    onCheckout: (Long, Int) -> Unit, modifier: Modifier) {
    var detail by remember { mutableStateOf<MallProductDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var skuId by remember { mutableStateOf<Long?>(null) }
    var quantity by remember { mutableStateOf("1") }
    LaunchedEffect(productId, revision) {
        detail = null; failed = false
        try {
            detail = reader.product(productId)
            if (detail!!.skus.size == 1) skuId = detail!!.skus.single().id
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true }
    }
    val selected = detail?.skus?.singleOrNull { it.id == skuId }
    val amount = quantity.takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toIntOrNull()
    MallLayout(R.string.screen_discovery_mall_shop, onBack, modifier) {
        val product = detail
        if (product == null) item { if (failed) MallFailure { revision++ } else CircularProgressIndicator() }
        else {
            item { Text(product.product.name, style = ElementTheme.typography.fontHeadingMdBold); product.product.subtitle?.let { Text(it) } }
            item { Text(stringResource(R.string.screen_discovery_mall_sku)) }
            items(product.skus, key = { it.id }) { sku -> FilterChip(skuId == sku.id, { skuId = sku.id },
                label = { Text("${sku.name} · ${mallMoney(sku.price)}") }, enabled = sku.stock > 0) }
            item {
                selected?.let { Text(stringResource(R.string.screen_discovery_mall_stock, it.stock)) }
                OutlinedTextField(quantity, { quantity = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = amount == null || selected != null && amount > selected.stock,
                    label = { Text(stringResource(R.string.screen_discovery_mall_quantity)) })
                Text(stringResource(R.string.screen_discovery_mall_choose_sku), color = ElementTheme.colors.textSecondary)
                Button(enabled = selected != null && amount != null && amount <= selected.stock,
                    onClick = { onCheckout(requireNotNull(selected).id, requireNotNull(amount)) }) { Text(stringResource(R.string.screen_discovery_mall_checkout)) }
            }
            product.text?.let { item { Text(it) } }
            item { Text(stringResource(R.string.screen_discovery_mall_delivery_notes), style = ElementTheme.typography.fontHeadingSmMedium) }
            product.delivery?.let { item { Text(it) } }; product.afterSale?.let { item { Text(it) } }
        }
    }
}

@Composable
internal fun DiscoveryMallCheckout(reader: DiscoveryMallReader, port: DiscoveryMallPort, skuId: Long, quantity: Int, addressId: Long?,
    onBack: () -> Unit, onAddress: () -> Unit, onResult: () -> Unit, modifier: Modifier) {
    var checkout by remember { mutableStateOf<MallCheckout?>(null) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val controller = rememberMallOperation(port)
    val operation by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(operation.reason) { if (operation.reason == "CHECKOUT_CHANGED") revision++ }
    LaunchedEffect(skuId, quantity, addressId, revision) {
        checkout = null; failed = false
        if (addressId != null) try { checkout = reader.checkout(skuId, quantity, addressId)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true }
    }
    val noteValid = note.trim().discoveryTextFits(500) && note.none { it.isISOControl() }
    MallLayout(R.string.screen_discovery_mall_checkout, onBack, modifier) {
        item { MallNotice(operation, { scope.launch { controller.recover() } }, onResult) }
        item { Button(onClick = onAddress, enabled = operation.phase == MallOperationPhase.READY) { Text(stringResource(R.string.screen_discovery_mall_select_address)) } }
        if (failed) item { Text(stringResource(R.string.screen_discovery_mall_address_invalid)); MallFailure { revision++ } }
        val quote = checkout
        if (quote == null && addressId != null && !failed) item { CircularProgressIndicator() }
        if (quote != null) {
            item { MallCard {
                Text(quote.productName, style = ElementTheme.typography.fontHeadingMdBold)
                Text("${quote.skuName} × ${quote.quantity}")
                Text("${quote.address.name} · ${quote.address.phone}"); Text(quote.address.fullAddress)
                MallAmounts(quote.productAmount, quote.freight, quote.payable)
            } }
            item {
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), enabled = operation.phase == MallOperationPhase.READY,
                    isError = !noteValid, label = { Text(stringResource(R.string.screen_discovery_mall_note)) })
                Text(stringResource(if (quote.channel == MallPaymentChannel.UNAVAILABLE) R.string.screen_discovery_mall_payment_unavailable
                    else R.string.screen_discovery_mall_payment_ready), color = ElementTheme.colors.textSecondary)
                if (!noteValid) Text(stringResource(R.string.screen_discovery_mall_invalid_form))
                Button(enabled = noteValid && operation.phase == MallOperationPhase.READY,
                    onClick = { scope.launch { controller.submit { port.create(quote, note) } } }) { Text(stringResource(R.string.screen_discovery_mall_create)) }
            }
        }
    }
}

@Composable
internal fun MallAmounts(product: Long, freight: Long, total: Long) {
    Text("${stringResource(R.string.screen_discovery_mall_amount)} · ${mallMoney(product)}")
    Text("${stringResource(R.string.screen_discovery_mall_freight)} · ${mallMoney(freight)}")
    Text("${stringResource(R.string.screen_discovery_mall_total)} · ${mallMoney(total)}", style = ElementTheme.typography.fontBodyLgMedium)
}

@Composable
internal fun DiscoveryMallOrders(reader: DiscoveryMallReader, port: DiscoveryMallPort, onBack: () -> Unit,
    onOrder: (Long) -> Unit, onResult: () -> Unit, modifier: Modifier) {
    var status by remember { mutableStateOf<MallOrderStatus?>(null) }
    var rows by remember { mutableStateOf<List<MallOrder>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<MallOrderStatus, Long>>(emptyMap()) }
    var page by remember { mutableIntStateOf(1) }; var retry by remember { mutableIntStateOf(0) }
    var total by remember { mutableLongStateOf(0) }; var loading by remember { mutableStateOf(true) }; var failed by remember { mutableStateOf(false) }
    val controller = rememberMallOperation(port); val operation by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(status, page, retry) {
        loading = true; failed = false
        if (page == 1) { rows = emptyList(); total = 0; counts = emptyMap() }
        try {
            val result = reader.orders(status, page)
            if (page > 1) require(result.total == total && result.rows.none { next -> rows.any { it.id == next.id } } &&
                (result.rows.isEmpty() || rows.isEmpty() || result.rows.first().id < rows.last().id))
            rows = if (page == 1) result.rows else rows + result.rows
            counts = result.counts; total = result.total
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { failed = true }
        loading = false
    }
    MallLayout(R.string.screen_discovery_mall_orders, onBack, modifier) {
        item {
            MallNotice(operation, { scope.launch { controller.recover() } }, onResult)
            TextButton(onClick = { page = 1; retry++ }) { Text(stringResource(R.string.screen_discovery_refresh)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(status == null, { status = null; page = 1 }, label = { Text(stringResource(R.string.screen_discovery_mall_all)) })
                MallOrderStatus.entries.forEach { item -> FilterChip(status == item, { status = item; page = 1 },
                    label = { Text(counts[item]?.let { "${stringResource(item.label())} · $it" } ?: stringResource(item.label())) }) }
            }
        }
        items(rows, key = { it.id }) { order -> MallCard {
            Text(stringResource(R.string.screen_discovery_mall_order_number, order.number))
            Text(stringResource(order.status.label()), style = ElementTheme.typography.fontHeadingSmMedium)
            order.items.forEach { Text("${it.product} · ${it.sku} × ${it.quantity}") }
            Text(mallMoney(order.payable))
            TextButton(onClick = { onOrder(order.id) }) { Text(stringResource(R.string.screen_discovery_courses_detail)) }
        } }
        item {
            if (loading) CircularProgressIndicator() else if (failed) MallFailure { retry++ }
            else if (rows.isEmpty()) Text(stringResource(R.string.screen_discovery_mall_empty))
            else if (rows.size.toLong() < total) TextButton(onClick = { loading = true; page++ }) { Text(stringResource(R.string.screen_discovery_mall_load_more)) }
        }
    }
}

@Composable
internal fun DiscoveryMallOrder(reader: DiscoveryMallReader, port: DiscoveryMallPort, orderId: Long, onBack: () -> Unit,
    onAfterSale: (Long?) -> Unit, onResult: () -> Unit, modifier: Modifier) {
    var detail by remember { mutableStateOf<MallOrderDetail?>(null) }; var failed by remember { mutableStateOf(false) }; var retry by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<MallAction?>(null) }
    val controller = rememberMallOperation(port); val operation by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(orderId, retry, operation.reason) {
        detail = null; failed = false
        try { detail = reader.order(orderId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    MallLayout(R.string.screen_discovery_mall_orders, onBack, modifier) {
        item { MallNotice(operation, { scope.launch { controller.recover() } }, onResult) }
        if (detail == null) item { if (failed) MallFailure { retry++ } else CircularProgressIndicator() }
        detail?.let { order ->
            mallOrderDetails(order)
            item {
                val enabled = operation.phase == MallOperationPhase.READY
                if (MallAction.CANCEL in order.actions) Button(enabled = enabled, onClick = { confirm = MallAction.CANCEL }) { Text(stringResource(R.string.screen_discovery_mall_cancel)) }
                if (MallAction.RECEIVE in order.actions) Button(enabled = enabled, onClick = { confirm = MallAction.RECEIVE }) { Text(stringResource(R.string.screen_discovery_mall_receive)) }
                if (MallAction.AFTER_SALE in order.actions) TextButton(enabled = enabled, onClick = { onAfterSale(null) }) { Text(stringResource(R.string.screen_discovery_mall_after_sale)) }
                order.afterSales.forEach { sale -> TextButton(onClick = { onAfterSale(sale.id) }) {
                    Text("${stringResource(R.string.screen_discovery_mall_after_sale)} #${sale.id} · ${mallStatusText(sale.status)}")
                } }
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.screen_discovery_refresh)) }
            }
        }
    }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null },
        text = { Text(stringResource(if (action == MallAction.CANCEL) R.string.screen_discovery_mall_cancel_confirm else R.string.screen_discovery_mall_receive_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = null; scope.launch { controller.submit {
            if (action == MallAction.CANCEL) port.cancel(orderId) else port.receive(orderId)
        } } }) { Text(stringResource(R.string.screen_discovery_mall_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.screen_discovery_mall_no)) } }) }
}

internal fun LazyListScope.mallOrderDetails(detail: MallOrderDetail) {
    item { MallCard {
        Text(stringResource(R.string.screen_discovery_mall_order_number, detail.order.number))
        Text(stringResource(detail.order.status.label()), style = ElementTheme.typography.fontHeadingMdBold)
        Text(stringResource(R.string.screen_discovery_mall_payment_status, mallStatusText(detail.order.paymentStatus)))
        Text(stringResource(R.string.screen_discovery_mall_created, detail.order.createdAt.replace('T', ' ')))
        detail.order.items.forEach { Text("${it.product} · ${it.sku} × ${it.quantity}"); Text(mallMoney(it.total)) }
        MallAmounts(detail.productAmount, detail.freight, detail.order.payable)
    } }
    item { MallCard { Text("${detail.receiver} · ${detail.phone}"); Text(detail.address); detail.note?.let { Text(it) } } }
    if (detail.order.status == MallOrderStatus.PENDING_PAYMENT) item {
        Text(stringResource(if (detail.channel == MallPaymentChannel.UNAVAILABLE) R.string.screen_discovery_mall_payment_unavailable
            else R.string.screen_discovery_mall_payment_ready))
    }
    if (!detail.shippingCompany.isNullOrBlank() || !detail.shippingNo.isNullOrBlank()) item { MallCard {
        Text(stringResource(R.string.screen_discovery_mall_shipping), style = ElementTheme.typography.fontHeadingSmMedium)
        detail.shippingCompany?.let { Text(it) }; detail.shippingNo?.let { Text(it) }
    } }
}

@Composable
internal fun DiscoveryMallAfterSale(reader: DiscoveryMallReader, port: DiscoveryMallPort, orderId: Long, afterSaleId: Long?,
    onBack: () -> Unit, onResult: () -> Unit, modifier: Modifier) {
    var order by remember { mutableStateOf<MallOrderDetail?>(null) }; var detail by remember { mutableStateOf<MallAfterSaleDetail?>(null) }
    var failed by remember { mutableStateOf(false) }; var retry by remember { mutableIntStateOf(0) }
    var reason by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }; var showIssues by remember { mutableStateOf(false) }
    val controller = rememberMallOperation(port); val operation by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(orderId, afterSaleId, retry, operation.reason) {
        order = null; detail = null; failed = false
        try { order = reader.order(orderId); if (afterSaleId != null) detail = reader.afterSale(orderId, afterSaleId)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    val valid = reason.trim().discoveryTextFits(160, 1) && description.trim().discoveryTextFits(500) &&
        reason.none { it.isISOControl() } && description.none { it.isISOControl() }
    MallLayout(R.string.screen_discovery_mall_after_sale, onBack, modifier) {
        item { MallNotice(operation, { scope.launch { controller.recover() } }, onResult) }
        if (failed) item { MallFailure { retry++ } }
        else if (order == null) item { CircularProgressIndicator() }
        else if (afterSaleId != null) detail?.let { sale -> item { MallAfterSaleDetails(sale) } }
        else {
            item {
                Text(stringResource(R.string.screen_discovery_mall_order_number, requireNotNull(order).order.number))
                Text(stringResource(R.string.screen_discovery_mall_application_only), color = ElementTheme.colors.textSecondary)
                OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), enabled = operation.phase == MallOperationPhase.READY,
                    label = { Text(stringResource(R.string.screen_discovery_mall_reason)) })
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), enabled = operation.phase == MallOperationPhase.READY,
                    label = { Text(stringResource(R.string.screen_discovery_mall_description)) })
                if (showIssues && !valid) Text(stringResource(R.string.screen_discovery_mall_invalid_form))
                Button(enabled = MallAction.AFTER_SALE in requireNotNull(order).actions && operation.phase == MallOperationPhase.READY,
                    onClick = { showIssues = true; if (valid) scope.launch { controller.submit { port.afterSale(orderId, reason, description) } } }) {
                    Text(stringResource(R.string.screen_discovery_mall_submit_after_sale))
                }
            }
        }
    }
}

@Composable
internal fun MallAfterSaleDetails(sale: MallAfterSaleDetail) = MallCard {
    Text(sale.number, style = ElementTheme.typography.fontHeadingMdBold)
    Text(stringResource(R.string.screen_discovery_mall_after_sale_status, mallStatusText(sale.summary.status)))
    Text(sale.reason); sale.description?.let { Text(it) }; sale.reply?.let { Text(it) }
    Text(stringResource(R.string.screen_discovery_mall_refund_requested, mallMoney(sale.summary.amount)))
    Text(stringResource(R.string.screen_discovery_mall_application_only), color = ElementTheme.colors.textSecondary)
}

@Composable
internal fun DiscoveryMallResult(port: DiscoveryMallPort, onBack: () -> Unit, onOrder: (Long) -> Unit, modifier: Modifier) {
    val controller = rememberMallOperation(port); val state by controller.state.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(state.locator) { if (state.locator != null) controller.refreshResult() }
    MallLayout(R.string.screen_discovery_mall_result, onBack, modifier) {
        when (state.phase) {
            MallOperationPhase.CHECKING, MallOperationPhase.SUBMITTING, MallOperationPhase.CONFIRMED -> item { CircularProgressIndicator() }
            MallOperationPhase.UNCERTAIN -> item { MallNotice(state, { scope.launch { controller.recover() } }, {}) }
            MallOperationPhase.FAILED -> item { Text(stringResource(R.string.screen_discovery_mall_saved_read_failed)); MallFailure { scope.launch { controller.refreshResult() } } }
            MallOperationPhase.READY -> item { Text(stringResource(R.string.screen_discovery_mall_empty)) }
            MallOperationPhase.RESULT -> state.result?.let { confirmed ->
                item { Text(stringResource(R.string.screen_discovery_mall_operation_confirmed), style = ElementTheme.typography.fontHeadingMdBold) }
                mallOrderDetails(confirmed.order)
                confirmed.afterSale?.let { sale -> item { MallAfterSaleDetails(sale) } }
                item {
                    if (state.reason == "RESULT_ACK_FAILED") Text(stringResource(R.string.screen_discovery_mall_result_ack_failed))
                    Button(onClick = { scope.launch { controller.finish(onOrder) } }) { Text(stringResource(R.string.screen_discovery_mall_done)) }
                    TextButton(onClick = { scope.launch { controller.refreshResult() } }) { Text(stringResource(R.string.screen_discovery_refresh)) }
                }
            }
        }
    }
}

@StringRes internal fun MallOrderStatus.label(): Int = when (this) {
    MallOrderStatus.PENDING_PAYMENT -> R.string.screen_discovery_mall_pending_payment
    MallOrderStatus.PENDING_SHIPMENT -> R.string.screen_discovery_mall_pending_shipment
    MallOrderStatus.SHIPPED -> R.string.screen_discovery_mall_shipped
    MallOrderStatus.COMPLETED -> R.string.screen_discovery_mall_completed
    MallOrderStatus.CANCELLED -> R.string.screen_discovery_mall_cancelled
    MallOrderStatus.AFTER_SALE -> R.string.screen_discovery_mall_in_after_sale
    MallOrderStatus.REFUNDED -> R.string.screen_discovery_mall_refunded
}

@Composable private fun mallStatusText(value: String): String = when (value) {
    "unpaid" -> stringResource(R.string.screen_discovery_mall_unpaid)
    "paid" -> stringResource(R.string.screen_discovery_mall_paid)
    "closed" -> stringResource(R.string.screen_discovery_mall_closed)
    "refunded" -> stringResource(R.string.screen_discovery_mall_refunded)
    "applied" -> stringResource(R.string.screen_discovery_mall_applied)
    "approved" -> stringResource(R.string.screen_discovery_mall_approved)
    "rejected" -> stringResource(R.string.screen_discovery_mall_rejected)
    "returning" -> stringResource(R.string.screen_discovery_mall_returning)
    "completed" -> stringResource(R.string.screen_discovery_mall_completed)
    "cancelled" -> stringResource(R.string.screen_discovery_mall_cancelled)
    else -> stringResource(R.string.screen_discovery_mall_unknown_status, value)
}
