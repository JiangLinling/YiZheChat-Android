/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import io.element.android.features.discovery.impl.actions.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMallWireTest {
    private val operation = "11111111-1111-4111-8111-111111111111"
    @Test fun `cancel and receive preserve different original paths despite identical HTTP bodies`() {
        val body = buildJsonObject { put("operation_id", operation); put("mall_order_id", 7) }
        val cancel = DiscoveryMallCommand(DiscoveryMallAction.CANCEL, body)
        val receive = DiscoveryMallCommand(DiscoveryMallAction.RECEIVE, body)
        assertArrayEquals(cancel.bytes(), receive.bytes())
        assertNotEquals(cancel.path, receive.path)
        for (command in listOf(cancel, receive)) {
            val bytes = DiscoveryInteractionRecord(command).bytes()
            try { assertEquals(command, DiscoveryInteractionRecord.parse(Json.parseToJsonElement(bytes.decodeToString()).jsonObject).command) }
            finally { bytes.fill(0) }
        }
    }

    @Test fun `create command rejects client price invalid quantity and noncanonical note`() {
        val body = buildJsonObject {
            put("operation_id", operation); put("sku_id", 2); put("quantity", 1); put("address_id", 3)
            put("checkout_revision", "a".repeat(64)); put("buyer_note", JsonNull)
        }
        DiscoveryMallCommand(DiscoveryMallAction.CREATE, body)
        assertThrows(IllegalArgumentException::class.java) { DiscoveryMallCommand(DiscoveryMallAction.CREATE, JsonObject(body + ("price" to JsonPrimitive(0)))) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryMallCommand(DiscoveryMallAction.CREATE, JsonObject(body + ("quantity" to JsonPrimitive(100)))) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryMallCommand(DiscoveryMallAction.CREATE, JsonObject(body + ("buyer_note" to JsonPrimitive(" hidden ")))) }
    }

    @Test fun `checkout validates exact money and explicit selected address`() {
        val value = buildJsonObject {
            put("product_id", 1); put("product_name", "Product"); put("sku_id", 2); put("sku_name", "Spec")
            put("quantity", 2); put("available_stock", 5); put("unit_price", 100); put("product_amount", 200)
            put("freight_amount", 20); put("payable_amount", 220); put("checkout_revision", "a".repeat(64)); put("payment_channel_status", "UNAVAILABLE")
            put("address", buildJsonObject {
                put("address_id", 3); put("receiver_name", "Recipient"); put("receiver_phone", "13800000000")
                put("province", "Province"); put("city", "City"); put("district", "District"); put("detail", "Detail")
            })
        }
        val result = parseMallCheckout(value)
        assertEquals(3L, result.address.id)
        assertEquals(MallPaymentChannel.UNAVAILABLE, result.channel)
        assertThrows(IllegalArgumentException::class.java) { parseMallCheckout(JsonObject(value + ("payable_amount" to JsonPrimitive(0)))) }
        assertThrows(IllegalArgumentException::class.java) { parseMallCheckout(JsonObject(value + ("quantity" to JsonPrimitive(3)))) }
    }

    @Test fun `confirmed mall locator coexists with original enrollment journal and rejects incompatible outcome`() {
        val saved = DiscoveryMallResultLocator(operation, DiscoveryMallAction.CREATE, 7, paymentId = 8, skuId = 2, quantity = 1)
        val command = DiscoveryArticleInteraction("22222222-2222-4222-8222-222222222222", 1, DiscoveryInteractionKind.LIKE, true)
        val enrollment = DiscoveryEnrollmentResultLocator("33333333-3333-4333-8333-333333333333", 4, 5, listOf(6), null)
        val record = DiscoveryInteractionRecord(command, listOf(enrollment), mall = saved)
        val bytes = record.bytes()
        try { assertEquals(record, DiscoveryInteractionRecord.parse(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)) }
        finally { bytes.fill(0) }
        assertThrows(IllegalArgumentException::class.java) { saved.copy(action = DiscoveryMallAction.CANCEL) }
        assertThrows(IllegalArgumentException::class.java) { saved.copy(afterSaleId = 9) }
    }
}
