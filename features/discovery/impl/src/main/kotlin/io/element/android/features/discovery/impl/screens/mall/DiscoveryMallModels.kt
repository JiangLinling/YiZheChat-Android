/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

internal enum class MallOrderStatus(val wire: String) {
    PENDING_PAYMENT("pending_payment"), PENDING_SHIPMENT("pending_shipment"), SHIPPED("shipped"), COMPLETED("completed"),
    CANCELLED("cancelled"), AFTER_SALE("after_sale"), REFUNDED("refunded");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
internal enum class MallAction(val wire: String) { CANCEL("cancel"), RECEIVE("receive"), AFTER_SALE("after_sale") }
internal enum class MallPaymentChannel { UNAVAILABLE, READY, NONE }
internal data class MallCategory(val id: Long, val name: String, val sortOrder: Long)
internal data class MallSku(val id: Long, val name: String, val price: Long, val marketPrice: Long?, val stock: Long)
internal data class MallProduct(val id: Long, val categoryId: Long?, val category: String?, val name: String, val subtitle: String?,
    val price: Long, val marketPrice: Long?, val stock: Long, val freight: Long)
internal data class MallProductDetail(val product: MallProduct, val skus: List<MallSku>, val text: String?, val delivery: String?, val afterSale: String?)
internal data class MallAddress(val id: Long, val name: String, val phone: String, val province: String, val city: String, val district: String, val detail: String) {
    val fullAddress get() = "$province $city $district $detail"
    override fun toString() = "MallAddress(<redacted>)"
}
internal data class MallCheckout(val productId: Long, val productName: String, val skuId: Long, val skuName: String, val quantity: Int,
    val stock: Long, val unitPrice: Long, val productAmount: Long, val freight: Long, val payable: Long,
    val address: MallAddress, val revision: String, val channel: MallPaymentChannel)
internal data class MallOrderItem(val id: Long, val productId: Long, val skuId: Long, val product: String, val sku: String,
    val unitPrice: Long, val quantity: Int, val total: Long)
internal data class MallOrder(val id: Long, val number: String, val status: MallOrderStatus, val paymentStatus: String,
    val payable: Long, val items: List<MallOrderItem>, val createdAt: String)
internal data class MallAfterSale(val id: Long, val status: String, val type: String, val amount: Long)
internal data class MallOrderDetail(val order: MallOrder, val productAmount: Long, val freight: Long, val receiver: String,
    val phone: String, val address: String, val note: String?, val paymentId: Long?, val payChannel: String?, val payMode: String?,
    val channel: MallPaymentChannel, val shippingCompany: String?, val shippingNo: String?, val paidAt: String?, val shippedAt: String?,
    val receivedAt: String?, val cancelledAt: String?, val afterSales: List<MallAfterSale>, val actions: Set<MallAction>) {
    override fun toString() = "MallOrderDetail(<redacted>)"
}
internal data class MallAfterSaleDetail(val summary: MallAfterSale, val orderId: Long, val number: String, val reason: String,
    val description: String?, val reply: String?, val origin: MallOrderStatus?, val createdAt: String) {
    override fun toString() = "MallAfterSaleDetail(<redacted>)"
}
internal data class MallPage<T>(val rows: List<T>, val total: Long, val page: Int, val size: Int,
    val counts: Map<MallOrderStatus, Long> = emptyMap()) {
    val hasMore get() = page.toLong() * size < total
}
