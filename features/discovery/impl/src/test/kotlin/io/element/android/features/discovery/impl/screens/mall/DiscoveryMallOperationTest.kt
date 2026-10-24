/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import io.element.android.features.discovery.impl.actions.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMallOperationTest {
    private val op = "11111111-1111-4111-8111-111111111111"
    private val locator = DiscoveryMallResultLocator(op, DiscoveryMallAction.CREATE, 7, paymentId = 8, skuId = 5, quantity = 1)
    private fun order() = MallOrderDetail(MallOrder(7, "ORDER-7", MallOrderStatus.PENDING_PAYMENT, "unpaid", 1200,
        listOf(MallOrderItem(9, 4, 5, "Book", "Paper", 1200, 1, 1200)), "2026-10-24T12:00:00"), 1200, 0,
        "Receiver", "13800000000", "Address", null, 8, "app", "unavailable", MallPaymentChannel.UNAVAILABLE,
        null, null, null, null, null, null, emptyList(), setOf(MallAction.CANCEL))

    @Test fun `saved order read failure cannot submit another mutation and retries only the confirmed order`() = runTest {
        val port = mockk<DiscoveryMallPort>()
        coEvery { port.recover() } returns MallMutation.Confirmed(locator)
        coEvery { port.refresh(locator) } throws IllegalStateException("unavailable")
        val controller = DiscoveryMallOperationController(port)
        controller.recover(); controller.refreshResult()
        assertEquals(MallOperationPhase.FAILED, controller.state.value.phase)
        var submitted = false
        controller.submit { submitted = true; MallMutation.Pending }
        assertFalse(submitted)
        coEvery { port.refresh(locator) } returns MallConfirmation(locator, order())
        controller.refreshResult()
        assertEquals(MallOperationPhase.RESULT, controller.state.value.phase)
        assertEquals(7L, controller.state.value.result!!.order.order.id)
        coVerify(exactly = 2) { port.refresh(locator) }
        coVerify(exactly = 1) { port.recover() }
    }

    @Test fun `leaving confirmed result keeps locator and recreated controller recovers same order`() = runTest {
        val port = mockk<DiscoveryMallPort>()
        coEvery { port.recover() } returns MallMutation.Confirmed(locator)
        val first = DiscoveryMallOperationController(port); first.recover(); first.close()
        val second = DiscoveryMallOperationController(port); second.recover()
        assertEquals(locator, second.state.value.locator)
        coVerify(exactly = 0) { port.acknowledge(any()) }
    }

    @Test fun `failed result acknowledgement preserves result and does not navigate`() = runTest {
        val port = mockk<DiscoveryMallPort>()
        coEvery { port.recover() } returns MallMutation.Confirmed(locator)
        coEvery { port.refresh(locator) } returns MallConfirmation(locator, order())
        coEvery { port.acknowledge(locator) } throws IllegalStateException("storage unavailable")
        val controller = DiscoveryMallOperationController(port); controller.recover(); controller.refreshResult()
        var delivered = false
        controller.finish { delivered = true }
        assertFalse(delivered)
        assertEquals(MallOperationPhase.RESULT, controller.state.value.phase)
        assertEquals(locator, controller.state.value.locator)
    }

    @Test fun `receipt must match original order and exact applied branch`() {
        val command = DiscoveryMallCommand(DiscoveryMallAction.RECEIVE, buildJsonObject { put("operation_id", op); put("mall_order_id", 7) })
        val receipt = buildJsonObject { put("operation_id", op); put("outcome", "APPLIED"); put("mall_order_id", 7) }
        assertEquals(7L, (parseMallMutation(command, receipt) as MallMutation.Confirmed).locator.orderId)
        assertThrows(IllegalArgumentException::class.java) { parseMallMutation(command, JsonObject(receipt + ("mall_order_id" to JsonPrimitive(9)))) }
        assertThrows(IllegalArgumentException::class.java) { parseMallMutation(command, JsonObject(receipt + ("reason" to JsonNull))) }
    }

    @Test fun `authoritative checkout change is a known rejection without a fabricated order`() {
        val command = DiscoveryMallCommand(DiscoveryMallAction.CREATE, buildJsonObject {
            put("operation_id", op); put("sku_id", 5); put("quantity", 1); put("address_id", 2)
            put("checkout_revision", "a".repeat(64)); put("buyer_note", JsonNull)
        })
        val result = parseMallMutation(command, buildJsonObject { put("operation_id", op); put("outcome", "REJECTED"); put("reason", "CHECKOUT_CHANGED") })
        assertEquals(MallMutation.Rejected("CHECKOUT_CHANGED"), result)
    }
}
