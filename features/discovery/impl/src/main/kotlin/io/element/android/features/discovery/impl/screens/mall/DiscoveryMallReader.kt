/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import io.element.android.features.discovery.impl.util.discoveryTextFits

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Inject
internal class DiscoveryMallReader(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    suspend fun categories(): List<MallCategory> = query(buildJsonObject { put("kind", "categories") }) { value ->
        value.exact("items")
        value.rows("items", 200).map { row ->
            row.exact("category_id", "name", "sort_order")
            MallCategory(row.id("category_id"), row.text("name", 80), row.number("sort_order"))
        }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
    }
    suspend fun products(category: Long?, keyword: String, page: Int): MallPage<MallProduct> {
        require(category == null || category in 1..Int.MAX_VALUE.toLong())
        require(keyword.trim().discoveryTextFits(80) && keyword.none { it.isISOControl() } && page in 1..100000)
        return query(buildJsonObject {
            put("kind", "products"); put("category_id", category?.let(::JsonPrimitive) ?: JsonNull)
            put("keyword", keyword.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            put("page", page); put("page_size", 20)
        }) { value -> value.exact("items", "total", "page", "page_size"); parseMallPage(value, page, 20, ::parseMallProduct) }
    }
    suspend fun product(id: Long): MallProductDetail = query(idQuery("product", "product_id", id)) { value ->
        value.exact(*(productFields + setOf("skus", "detail_text", "delivery_note", "after_sale_note", "image_media")).toTypedArray())
        require(value.getValue("image_media") == JsonNull)
        val product = parseMallProduct(JsonObject(value.filterKeys { it in productFields }))
        require(product.id == id)
        val skus = value.rows("skus", 200).map { row ->
            row.exact("sku_id", "spec_name", "price", "market_price", "available_stock", "image_media")
            require(row.getValue("image_media") == JsonNull)
            MallSku(row.id("sku_id"), row.text("spec_name", 160), row.money("price"), row.optionalMoney("market_price"), row.money("available_stock"))
        }
        require(skus.map { it.id }.distinct().size == skus.size)
        MallProductDetail(product, skus, value.optionalText("detail_text", 20000), value.optionalText("delivery_note", 500), value.optionalText("after_sale_note", 500))
    }
    suspend fun checkout(sku: Long, quantity: Int, address: Long): MallCheckout {
        require(sku in 1..Int.MAX_VALUE.toLong() && address in 1..Int.MAX_VALUE.toLong() && quantity in 1..99)
        return query(buildJsonObject { put("kind", "checkout"); put("sku_id", sku); put("quantity", quantity); put("address_id", address) }) { value ->
            parseMallCheckout(value).also { require(it.skuId == sku && it.quantity == quantity && it.address.id == address) }
        }
    }
    suspend fun orders(status: MallOrderStatus?, page: Int): MallPage<MallOrder> {
        require(page in 1..100000)
        return query(buildJsonObject {
            put("kind", "orders"); put("status", status?.wire?.let(::JsonPrimitive) ?: JsonNull); put("page", page); put("page_size", 5)
        }) { value ->
            value.exact("items", "total", "page", "page_size", "status_counts")
            val result = parseMallPage(value, page, 5, ::parseMallOrder)
            val counts = value.getValue("status_counts").jsonObject
            require(counts.keys == MallOrderStatus.entries.map { it.wire }.toSet())
            val parsed = MallOrderStatus.entries.associateWith { counts.money(it.wire) }
            require(result.total == if (status == null) parsed.values.sum() else parsed.getValue(status))
            require(result.rows.all { status == null || it.status == status })
            require(result.rows.map { it.id } == result.rows.map { it.id }.distinct().sortedDescending())
            result.copy(counts = parsed)
        }
    }
    suspend fun order(id: Long) = query(idQuery("order", "mall_order_id", id)) { parseMallOrderDetail(it).also { value -> require(value.order.id == id) } }
    suspend fun afterSale(orderId: Long, id: Long): MallAfterSaleDetail {
        require(orderId in 1..Int.MAX_VALUE.toLong() && id in 1..Int.MAX_VALUE.toLong())
        return query(buildJsonObject { put("kind", "after_sale"); put("mall_order_id", orderId); put("after_sale_id", id) }) {
            parseMallAfterSaleDetail(it).also { value -> require(value.orderId == orderId && value.summary.id == id) }
        }
    }
    private suspend fun <T> query(request: JsonObject, parse: (JsonObject) -> T): T = access.withSession { session ->
        val body = request.toString().encodeToByteArray()
        try {
            val response = transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/mall/query", body,
                mapOf("Authorization" to "Bearer ${session.secret.value}"))
            try { withContext(Dispatchers.Default) { parse(Json.parseToJsonElement(response.decodeToString(throwOnInvalidSequence = true)).jsonObject) } }
            finally { response.fill(0) }
        } finally { body.fill(0) }
    }
}

private fun idQuery(kind: String, key: String, id: Long): JsonObject {
    require(id in 1..Int.MAX_VALUE.toLong())
    return buildJsonObject { put("kind", kind); put(key, id) }
}
private val productFields = setOf("product_id", "category_id", "category_name", "name", "subtitle", "price", "market_price", "available_stock", "freight_amount", "cover_media")
internal fun parseMallProduct(value: JsonObject): MallProduct {
    value.exact(*productFields.toTypedArray()); require(value.getValue("cover_media") == JsonNull)
    return MallProduct(value.id("product_id"), value.optionalId("category_id"), value.optionalText("category_name", 80), value.text("name", 160),
        value.optionalText("subtitle", 300), value.money("price"), value.optionalMoney("market_price"), value.money("available_stock"), value.money("freight_amount"))
}
internal fun parseMallCheckout(value: JsonObject): MallCheckout {
    value.exact("product_id", "product_name", "sku_id", "sku_name", "quantity", "available_stock", "unit_price", "product_amount", "freight_amount",
        "payable_amount", "address", "checkout_revision", "payment_channel_status")
    val address = value.getValue("address").jsonObject
    address.exact("address_id", "receiver_name", "receiver_phone", "province", "city", "district", "detail")
    val result = MallCheckout(value.id("product_id"), value.text("product_name", 160), value.id("sku_id"), value.text("sku_name", 160),
        value.quantity(), value.money("available_stock"), value.money("unit_price"), value.money("product_amount"), value.money("freight_amount"), value.money("payable_amount"),
        MallAddress(address.id("address_id"), address.text("receiver_name", 80), address.text("receiver_phone", 30), address.text("province", 80),
            address.text("city", 80), address.text("district", 80), address.text("detail", 300)), value.text("checkout_revision", 64), value.channel())
    require(result.revision.matches(Regex("[a-f0-9]{64}")) && result.channel != MallPaymentChannel.NONE)
    require(Math.multiplyExact(result.unitPrice, result.quantity.toLong()) == result.productAmount)
    require(Math.addExact(result.productAmount, result.freight) == result.payable && result.stock >= result.quantity)
    return result
}
private val orderFields = setOf("business_domain", "mall_order_id", "order_no", "status", "payment_status", "payable_amount", "items", "created_at")
internal fun parseMallOrder(value: JsonObject): MallOrder {
    value.exact(*orderFields.toTypedArray()); require(value["business_domain"] == JsonPrimitive("mall_order"))
    val items = value.rows("items", 100).map { row ->
        row.exact("item_id", "product_id", "sku_id", "product_name", "sku_name", "unit_price", "quantity", "total_amount", "cover_media")
        require(row.getValue("cover_media") == JsonNull)
        MallOrderItem(row.id("item_id"), row.id("product_id"), row.id("sku_id"), row.text("product_name", 160), row.text("sku_name", 160),
            row.money("unit_price"), row.quantity(), row.money("total_amount")).also { require(Math.multiplyExact(it.unitPrice, it.quantity.toLong()) == it.total) }
    }
    require(items.isNotEmpty() && items.map { it.id }.distinct().size == items.size)
    return MallOrder(value.id("mall_order_id"), value.text("order_no", 64), MallOrderStatus.parse(value.text("status", 30)), value.text("payment_status", 20),
        value.money("payable_amount"), items, value.text("created_at", 40))
}
internal fun parseMallOrderDetail(value: JsonObject): MallOrderDetail {
    value.exact(*(orderFields + setOf("product_amount", "freight_amount", "receiver_name", "receiver_phone", "receiver_address", "buyer_note", "payment_order_id",
        "pay_channel", "pay_mode", "payment_channel_status", "shipping_company", "shipping_no", "paid_at", "shipped_at", "received_at", "cancelled_at",
        "after_sales", "available_actions")).toTypedArray())
    val order = parseMallOrder(JsonObject(value.filterKeys { it in orderFields }))
    val afterSales = value.rows("after_sales", 100).map(::parseMallAfterSale)
    require(afterSales.map { it.id }.distinct().size == afterSales.size)
    val actions = value.getValue("available_actions").jsonArray.also { require(it.size <= 3) }.map { element ->
        val name = element.jsonPrimitive.also { require(it.isString) }.content
        MallAction.entries.single { it.wire == name }
    }
    require(actions.distinct().size == actions.size)
    val result = MallOrderDetail(order, value.money("product_amount"), value.money("freight_amount"), value.text("receiver_name", 80), value.text("receiver_phone", 30),
        value.text("receiver_address", 500), value.optionalText("buyer_note", 500), value.optionalId("payment_order_id"), value.optionalText("pay_channel", 20),
        value.optionalText("pay_mode", 20), value.channel(), value.optionalText("shipping_company", 80), value.optionalText("shipping_no", 120),
        value.optionalText("paid_at", 40), value.optionalText("shipped_at", 40), value.optionalText("received_at", 40), value.optionalText("cancelled_at", 40), afterSales, actions.toSet())
    require(order.items.sumOf { it.total } == result.productAmount && Math.addExact(result.productAmount, result.freight) == order.payable)
    require(MallAction.CANCEL !in result.actions || order.status == MallOrderStatus.PENDING_PAYMENT && order.paymentStatus == "unpaid")
    require(MallAction.RECEIVE !in result.actions || order.status == MallOrderStatus.SHIPPED)
    require(MallAction.AFTER_SALE !in result.actions || order.status in setOf(MallOrderStatus.PENDING_SHIPMENT, MallOrderStatus.SHIPPED, MallOrderStatus.COMPLETED))
    return result
}
private val afterSaleFields = setOf("after_sale_id", "status", "after_sale_type", "refund_amount")
private fun parseMallAfterSale(value: JsonObject): MallAfterSale {
    value.exact(*afterSaleFields.toTypedArray())
    val type = value.text("after_sale_type", 30).also { require(it in setOf("refund_only", "return_refund")) }
    return MallAfterSale(value.id("after_sale_id"), value.text("status", 30), type, value.money("refund_amount"))
}
internal fun parseMallAfterSaleDetail(value: JsonObject): MallAfterSaleDetail {
    value.exact(*(afterSaleFields + setOf("mall_order_id", "after_sale_no", "reason", "description", "admin_reply", "origin_order_status", "created_at")).toTypedArray())
    return MallAfterSaleDetail(parseMallAfterSale(JsonObject(value.filterKeys { it in afterSaleFields })), value.id("mall_order_id"), value.text("after_sale_no", 64),
        value.text("reason", 160), value.optionalText("description", 500), value.optionalText("admin_reply", 500),
        value.optionalText("origin_order_status", 30)?.let(MallOrderStatus::parse), value.text("created_at", 40))
}
private fun <T> parseMallPage(value: JsonObject, page: Int, size: Int, parse: (JsonObject) -> T): MallPage<T> {
    require(value.number("page") == page.toLong() && value.number("page_size") == size.toLong())
    val total = value.money("total")
    val rows = value.rows("items", size)
    require(rows.size.toLong() == minOf(size.toLong(), (total - (page - 1L) * size).coerceAtLeast(0)))
    return MallPage(rows.map(parse), total, page, size)
}
internal fun JsonObject.exact(vararg fields: String) = require(keys == fields.toSet())
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
internal fun JsonObject.id(key: String): Long = number(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
internal fun JsonObject.money(key: String): Long = number(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }
internal fun JsonObject.optionalId(key: String): Long? = if (getValue(key) == JsonNull) null else id(key)
private fun JsonObject.optionalMoney(key: String): Long? = if (getValue(key) == JsonNull) null else money(key)
internal fun JsonObject.text(key: String, limit: Int): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content.also { require(it.discoveryTextFits(limit, 1)) }
internal fun JsonObject.optionalText(key: String, limit: Int): String? = if (getValue(key) == JsonNull) null else getValue(key).jsonPrimitive
    .also { require(it.isString && it.content.discoveryTextFits(limit)) }.content
private fun JsonObject.rows(key: String, limit: Int) = getValue(key).jsonArray.also { require(it.size <= limit) }.map { it.jsonObject }
private fun JsonObject.quantity(): Int = number("quantity").also { require(it in 1..99) }.toInt()
private fun JsonObject.channel() = MallPaymentChannel.valueOf(text("payment_channel_status", 20))
