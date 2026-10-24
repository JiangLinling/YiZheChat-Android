/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryAddressTest {
    @Test fun `acknowledged save survives failed refresh and cannot be submitted again`() = runTest {
        val port = FakeAddressPort()
        val controller = DiscoveryAddressController(port)
        controller.load(); controller.begin(10)
        port.saveResult = AddressOutcome.Saved(null, 10)
        controller.save()
        assertEquals(AddressPhase.SAVED, controller.state.value.phase)
        assertEquals(10L, controller.state.value.savedId)
        port.failLoad = true
        controller.load(); controller.save(); controller.begin()
        assertEquals(AddressPhase.FAILED, controller.state.value.phase)
        assertEquals(10L, controller.state.value.savedId)
        assertEquals(1, port.saves)
        port.failLoad = false; controller.load()
        assertTrue(controller.canChoose(10))
        assertEquals(10L, controller.state.value.savedId)
    }
    @Test fun `explicit address paste maps municipality and labels without saving or discarding unrelated fields`() {
        val current = MemberAddressDraft(houseNumber = "302", isDefault = false)
        val result = parseAddressPaste("收件人：张三 手机号：13800000000 所在地区：上海市 浦东新区 详细地址：示例路1号", current)
        assertEquals("张三", result.name)
        assertEquals("13800000000", result.phone)
        assertEquals("上海市", result.province)
        assertEquals("上海市", result.city)
        assertEquals("浦东新区", result.district)
        assertEquals("示例路1号", result.detail)
        assertEquals("302", result.houseNumber)
        assertFalse(result.isDefault)
    }

    @Test fun `ambiguous phone paste and unrecognized text cannot erase existing address`() {
        val original = addressDraft()
        val ambiguous = parseAddressPaste("手机号：13800000000 电话：13900000000", original)
        assertEquals(original.phone, ambiguous.phone)
        assertEquals(original.detail, ambiguous.detail)
        assertEquals(original, parseAddressPaste("unrecognised text", original))
    }

    @Test fun `house number is joined once and total bound follows persisted detail column`() {
        val draft = addressDraft().copy(detail = "Street", houseNumber = "Room 302")
        assertEquals("Street Room 302", draft.savedDetail)
        assertTrue(AddressIssue.LENGTH in draft.copy(detail = "x".repeat(299)).issues())
        val persisted = MemberAddress(10, draft.copy(detail = draft.savedDetail, houseNumber = ""))
        assertEquals("Street Room 302", persisted.draft.savedDetail)
        assertThrows(IllegalArgumentException::class.java) { MemberAddress(10, draft) }
    }

    @Test fun `unknown address save blocks editing selection and new save until original resolves`() = runTest {
        val port = FakeAddressPort().apply { failSave = true }
        val controller = DiscoveryAddressController(port)
        controller.load(); controller.begin(10); controller.save()
        controller.begin(); controller.edit { it.copy(name = "Other") }; controller.save(); controller.load(); controller.recover()
        assertEquals(1, port.saves)
        assertEquals(AddressPhase.UNCERTAIN, controller.state.value.phase)
        assertFalse(controller.canChoose(10))
        port.recovery = AddressOutcome.Saved(port.book, 10)
        controller.recover()
        assertEquals(AddressPhase.READY, controller.state.value.phase)
        assertTrue(controller.canChoose(10))
        assertNull(controller.state.value.draft)
    }

    @Test fun `different owner or edited address result never becomes selectable`() = runTest {
        val port = FakeAddressPort()
        val controller = DiscoveryAddressController(port)
        controller.load(); controller.begin(10)
        port.saveResult = AddressOutcome.Saved(port.book.copy(ownerId = 2), 10)
        controller.save()
        assertEquals(AddressPhase.UNCERTAIN, controller.state.value.phase)
        assertEquals(1L, controller.state.value.book?.ownerId)
        port.recovery = AddressOutcome.Saved(port.book.copy(rows = listOf(MemberAddress(11, addressDraft()))), 11)
        controller.recover()
        assertEquals(AddressPhase.UNCERTAIN, controller.state.value.phase)
        assertNull(controller.state.value.savedId)
    }

    @Test fun `duplicate save and late reply after close cannot restore address or select it`() = runTest {
        val port = FakeAddressPort()
        val wait = CompletableDeferred<Unit>(); port.waitForSave = wait
        val controller = DiscoveryAddressController(port)
        controller.load(); controller.begin(10)
        val job = launch { controller.save() }
        testScheduler.runCurrent(); controller.save()
        assertEquals(1, port.saves)
        controller.close(); wait.complete(Unit); job.join()
        assertNull(controller.state.value.book)
        assertNull(controller.state.value.draft)
        assertNull(controller.state.value.savedId)
    }

    @Test fun `identity suggestion is scoped to returned list and does not replace entered phone`() = runTest {
        val port = FakeAddressPort()
        val controller = DiscoveryAddressController(port)
        controller.load(); controller.begin(10)
        controller.useName(999)
        assertEquals("Recipient", controller.state.value.draft?.name)
        controller.useName(1)
        assertEquals("Authorised", controller.state.value.draft?.name)
        assertEquals("13800000000", controller.state.value.draft?.phone)
    }
}

private fun addressDraft() = MemberAddressDraft("Recipient", "13800000000", "上海市", "上海市", "浦东新区", "Street")
private class FakeAddressPort : DiscoveryAddressPort {
    val book = MemberAddressBook(1, listOf(MemberAddress(10, addressDraft())), listOf(AddressNameSuggestion(1, "Authorised", "13900000000")))
    var saves = 0
    var failSave = false
    var failLoad = false
    var waitForSave: CompletableDeferred<Unit>? = null
    var saveResult: AddressOutcome = AddressOutcome.Saved(book, 10)
    var recovery: AddressOutcome = AddressOutcome.Clear
    override suspend fun load(): MemberAddressBook { if (failLoad) error("read failed"); return book }
    override suspend fun save(addressId: Long?, draft: MemberAddressDraft): AddressOutcome {
        saves++; waitForSave?.await()
        if (failSave) error("response lost")
        return saveResult
    }
    override suspend fun recover() = recovery
}
