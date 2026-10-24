/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.*
import java.util.UUID
import kotlinx.serialization.json.*

internal sealed interface MallMutation {
    data object Clear : MallMutation
    data object Pending : MallMutation
    data class Rejected(val reason: String) : MallMutation
    data class Confirmed(val locator: DiscoveryMallResultLocator) : MallMutation
}

internal data class MallConfirmation(val locator: DiscoveryMallResultLocator, val order: MallOrderDetail,
    val afterSale: MallAfterSaleDetail? = null)

/** All mutations share the existing account Vault journal and the original ACTION operation. */
@Inject
internal class DiscoveryMallPort(private val reader: DiscoveryMallReader, private val actions: DiscoveryBusinessActions) {
    suspend fun create(checkout: MallCheckout, note: String): MallMutation = execute(DiscoveryMallCommand(DiscoveryMallAction.CREATE, buildJsonObject {
        put("operation_id", UUID.randomUUID().toString()); put("sku_id", checkout.skuId); put("quantity", checkout.quantity)
        put("address_id", checkout.address.id); put("checkout_revision", checkout.revision)
        put("buyer_note", note.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    }))

    suspend fun cancel(id: Long) = change(DiscoveryMallAction.CANCEL, id)
    suspend fun receive(id: Long) = change(DiscoveryMallAction.RECEIVE, id)
    suspend fun afterSale(id: Long, reason: String, description: String): MallMutation = execute(
        DiscoveryMallCommand(DiscoveryMallAction.AFTER_SALE, buildJsonObject {
            put("operation_id", UUID.randomUUID().toString()); put("mall_order_id", id); put("reason", reason.trim())
            put("description", description.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }))

    private suspend fun change(action: DiscoveryMallAction, id: Long) = execute(DiscoveryMallCommand(action, buildJsonObject {
        put("operation_id", UUID.randomUUID().toString()); put("mall_order_id", id)
    }))

    suspend fun recover(): MallMutation {
        val pending = actions.pendingMall()
        val saved = actions.mallResult()
        if (saved != null && pending?.operationId != saved.operationId) return MallMutation.Confirmed(saved)
        if (pending == null) return MallMutation.Clear
        return execute(null)
    }

    private suspend fun execute(command: DiscoveryMallCommand?): MallMutation = actions.execute<MallMutation>(command,
        accepts = { it is DiscoveryMallCommand }, confirmedMall = { (it as? MallMutation.Confirmed)?.locator }) { original, receipt ->
        require(original is DiscoveryMallCommand)
        parseMallMutation(original, receipt)
    } ?: MallMutation.Pending

    suspend fun refresh(locator: DiscoveryMallResultLocator): MallConfirmation {
        require(actions.mallResult() == locator)
        val order = reader.order(locator.orderId)
        if (locator.action == DiscoveryMallAction.CREATE) {
            require(order.paymentId == locator.paymentId && order.order.items.size == 1)
            val item = order.order.items.single()
            require(item.skuId == locator.skuId && item.quantity == locator.quantity)
        }
        val afterSale = locator.afterSaleId?.let { reader.afterSale(locator.orderId, it) }
        return MallConfirmation(locator, order, afterSale)
    }

    suspend fun acknowledge(locator: DiscoveryMallResultLocator) = actions.dismissMallResult(locator)
}

internal fun parseMallMutation(command: DiscoveryMallCommand, receipt: JsonObject): MallMutation {
    require(receipt["operation_id"] == JsonPrimitive(command.operationId))
    if (receipt["outcome"] == JsonPrimitive("REJECTED")) {
        receipt.exact("operation_id", "outcome", "reason")
        val reason = receipt.text("reason", 64).also { require(it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) }
        return MallMutation.Rejected(reason)
    }
    require(receipt["outcome"] == JsonPrimitive("APPLIED"))
    val fields = setOf("operation_id", "outcome", "mall_order_id") + when (command.action) {
        DiscoveryMallAction.CREATE -> setOf("payment_order_id")
        DiscoveryMallAction.AFTER_SALE -> setOf("after_sale_id")
        else -> emptySet()
    }
    receipt.exact(*fields.toTypedArray())
    val locator = DiscoveryMallResultLocator(command.operationId, command.action, receipt.id("mall_order_id"),
        paymentId = if (command.action == DiscoveryMallAction.CREATE) receipt.id("payment_order_id") else null,
        afterSaleId = if (command.action == DiscoveryMallAction.AFTER_SALE) receipt.id("after_sale_id") else null,
        skuId = if (command.action == DiscoveryMallAction.CREATE) command.body.id("sku_id") else null,
        quantity = if (command.action == DiscoveryMallAction.CREATE) command.body.number("quantity").toInt() else null)
    require(locator.matches(command))
    return MallMutation.Confirmed(locator)
}
