/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.actions

import io.element.android.features.discovery.impl.screens.mall.*
import io.element.android.features.discovery.impl.util.discoveryTextFits
import java.util.UUID
import kotlinx.serialization.json.*

internal enum class DiscoveryMallAction(val endpoint: String) {
    CREATE("mall-orders"), CANCEL("mall-order-cancellations"), RECEIVE("mall-order-receipts"), AFTER_SALE("mall-after-sales")
}
internal data class DiscoveryMallCommand(val action: DiscoveryMallAction, val body: JsonObject) : DiscoveryBusinessCommand {
    override val operationId get() = body.text("operation_id", 36)
    override val path get() = "/v1/discovery/business/${action.endpoint}"
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        if (action == DiscoveryMallAction.CREATE) {
            body.exact("operation_id", "sku_id", "quantity", "address_id", "checkout_revision", "buyer_note")
            body.id("sku_id"); body.id("address_id")
            require(body.number("quantity") in 1..99 && body.text("checkout_revision", 64).matches(Regex("[a-f0-9]{64}")))
            validateMallNote(body.optionalText("buyer_note", 500), 500)
        } else {
            body.id("mall_order_id")
            if (action == DiscoveryMallAction.AFTER_SALE) {
                body.exact("operation_id", "mall_order_id", "reason", "description")
                validateMallNote(body.text("reason", 160), 160, required = true)
                validateMallNote(body.optionalText("description", 500), 500)
            } else body.exact("operation_id", "mall_order_id")
        }
    }
    override fun bytes() = body.toString().encodeToByteArray()
    // Cancel and receive have identical HTTP body shapes. Preserve their exact original path in the encrypted journal only.
    override fun journalBytes() = buildJsonObject { put("mall_action", action.name); put("body", body) }.toString().encodeToByteArray()
    override fun toString() = "DiscoveryMallCommand(<redacted>)"
    companion object {
        fun parse(value: JsonObject): DiscoveryMallCommand {
            value.exact("mall_action", "body")
            return DiscoveryMallCommand(DiscoveryMallAction.valueOf(value.text("mall_action", 20)), value.getValue("body").jsonObject)
        }
    }
}
internal fun validateMallNote(value: String?, limit: Int, required: Boolean = false) {
    require(!required || !value.isNullOrBlank())
    require(value == null || value == value.trim() && value.discoveryTextFits(limit) && value.none { it.isISOControl() })
}
internal data class DiscoveryMallResultLocator(val operationId: String, val action: DiscoveryMallAction, val orderId: Long,
    val paymentId: Long? = null, val afterSaleId: Long? = null, val skuId: Long? = null, val quantity: Int? = null) {
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(listOfNotNull(orderId, paymentId, afterSaleId, skuId).all { it in 1..Int.MAX_VALUE.toLong() })
        require(if (action == DiscoveryMallAction.CREATE) paymentId != null && skuId != null && quantity != null && quantity in 1..99
            else paymentId == null && skuId == null && quantity == null)
        require((action == DiscoveryMallAction.AFTER_SALE) == (afterSaleId != null))
    }
    fun matches(command: DiscoveryMallCommand): Boolean = operationId == command.operationId && action == command.action &&
        if (action == DiscoveryMallAction.CREATE) skuId == command.body.id("sku_id") && quantity?.toLong() == command.body.number("quantity")
        else orderId == command.body.id("mall_order_id")
    override fun toString() = "DiscoveryMallResultLocator(<redacted>)"
    fun json() = buildJsonObject {
        put("operation_id", operationId); put("action", action.name); put("mall_order_id", orderId)
        put("payment_order_id", paymentId?.let(::JsonPrimitive) ?: JsonNull); put("after_sale_id", afterSaleId?.let(::JsonPrimitive) ?: JsonNull)
        put("sku_id", skuId?.let(::JsonPrimitive) ?: JsonNull); put("quantity", quantity?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object {
        fun parse(value: JsonObject): DiscoveryMallResultLocator {
            value.exact("operation_id", "action", "mall_order_id", "payment_order_id", "after_sale_id", "sku_id", "quantity")
            val quantity = value.optionalId("quantity")?.also { require(it in 1..99) }?.toInt()
            return DiscoveryMallResultLocator(value.text("operation_id", 36), DiscoveryMallAction.valueOf(value.text("action", 20)), value.id("mall_order_id"),
                value.optionalId("payment_order_id"), value.optionalId("after_sale_id"), value.optionalId("sku_id"), quantity)
        }
    }
}
