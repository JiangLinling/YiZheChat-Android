/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommonInfoOrderingTest {
    private val now = 40L * 24 * 60 * 60 * 1000

    @Test
    fun `pins override frequency and expired usage does not win`() {
        val pinned = CommonInfoItem(id = "pinned", pinnedAt = now)
        val frequent = CommonInfoItem(id = "frequent", uses = mapOf("one" to now - 20, "two" to now - 10))
        val recent = CommonInfoItem(id = "recent", uses = mapOf("one" to now))
        val expired = CommonInfoItem(id = "expired", uses = (1..10).associate { "$it" to 1L })
        assertEquals(listOf("pinned", "frequent", "recent", "expired"),
            orderCommonInfo(listOf(expired, recent, frequent, pinned), now).map { it.id })
    }

    @Test
    fun `removing pins restores usage order without erasing statistics`() {
        val frequent = CommonInfoItem(id = "frequent", uses = mapOf("one" to now, "two" to now))
        val pinned = CommonInfoItem(id = "pinned", pinnedAt = now, uses = mapOf("one" to now))
        val reset = listOf(frequent, pinned).map { it.copy(pinnedAt = 0) }
        assertEquals("frequent", orderCommonInfo(reset, now).first().id)
        assertEquals(1, reset.last().uses.size)
    }

    @Test
    fun `sending uses complete literal fields and excludes the label`() {
        val address = CommonInfoItem(title = "家里", kind = CommonInfoKind.ADDRESS,
            recipient = "张三", phone = "13800000000", content = "示例路 66 号")
        assertEquals("张三 13800000000\n示例路 66 号", address.sendText())
        assertEquals("/literal", CommonInfoItem(title = "标签", content = "/literal").sendText())
    }

    @Test
    fun `summaries mask identity and phone while sending preserves the value`() {
        val identity = CommonInfoItem(kind = CommonInfoKind.IDENTITY, content = "123456789012345678")
        assertFalse(identity.maskedSummary().contains("123456"))
        assertEquals(identity.content, identity.sendText())
        assertEquals("138 **** 0000", CommonInfoItem(kind = CommonInfoKind.PHONE, content = "13800000000").maskedSummary())
    }
}
