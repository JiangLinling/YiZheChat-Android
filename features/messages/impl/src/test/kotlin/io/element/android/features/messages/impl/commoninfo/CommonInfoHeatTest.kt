/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommonInfoHeatTest {
    private val now = 100L * COMMON_USAGE_DAY_MS
    private fun item(vararg ages: Long) = CommonInfoItem(uses = ages.mapIndexed { i, age -> "event-$i" to now - age }.toMap())

    @Test fun `heat halves every seven days without a calendar reset`() {
        assertThat(commonInfoHeat(item(0), now).score).isWithin(0.00001).of(1.0)
        assertThat(commonInfoHeat(item(7 * COMMON_USAGE_DAY_MS), now).score).isWithin(0.00001).of(0.5)
        assertThat(commonInfoHeat(item(14 * COMMON_USAGE_DAY_MS), now).score).isWithin(0.00001).of(0.25)
    }
    @Test fun `old and future uses cannot inflate heat`() {
        val value = commonInfoHeat(item(31 * COMMON_USAGE_DAY_MS, -1), now)
        assertThat(value.score).isEqualTo(0.0)
        assertThat(value.lastUsedAt).isEqualTo(0)
        assertThat(value.level).isEqualTo(CommonInfoHeatLevel.NONE)
    }
    @Test fun `recent use outranks more numerous stale uses`() {
        val recent = item(0).copy(id = "recent")
        val stale = item(*LongArray(8) { 28 * COMMON_USAGE_DAY_MS }).copy(id = "stale")
        assertThat(orderCommonInfo(listOf(stale, recent), now).map { it.id }).containsExactly("recent", "stale").inOrder()
    }
    @Test fun `pin overrides heat and reset returns to heat order`() {
        val pin = item().copy(id = "pin", pinnedAt = 1)
        val hot = item(*LongArray(8)).copy(id = "hot")
        assertThat(orderCommonInfo(listOf(hot, pin), now).map { it.id }).containsExactly("pin", "hot").inOrder()
        assertThat(orderCommonInfo(listOf(hot, pin.copy(pinnedAt = 0)), now).first().id).isEqualTo("hot")
    }
    @Test fun `colour levels follow the same decaying score as order`() {
        val hot = item(*LongArray(6))
        assertThat(commonInfoHeat(hot, now).level).isEqualTo(CommonInfoHeatLevel.FREQUENT)
        assertThat(commonInfoHeat(hot, now + 7 * COMMON_USAGE_DAY_MS).level).isEqualTo(CommonInfoHeatLevel.REGULAR)
        assertThat(commonInfoHeat(hot, now + 21 * COMMON_USAGE_DAY_MS).level).isEqualTo(CommonInfoHeatLevel.OCCASIONAL)
        assertThat(commonInfoHeat(hot, now + 31 * COMMON_USAGE_DAY_MS).level).isEqualTo(CommonInfoHeatLevel.NONE)
    }
    @Test fun `unused items retain stable creation order`() {
        assertThat(orderCommonInfo(listOf(item().copy(id = "old", createdAt = 1), item().copy(id = "new", createdAt = 2)), now).map { it.id })
            .containsExactly("new", "old").inOrder()
    }
}
