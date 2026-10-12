/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HuaweiPushParserTest {
    @Test
    fun `only Huawei manufacturer enables Huawei Push`() {
        assertThat(isHuaweiPushTarget("HUAWEI")).isTrue()
        assertThat(isHuaweiPushTarget(" huawei ")).isTrue()
        assertThat(isHuaweiPushTarget("unknown", "HUAWEI")).isTrue()
        assertThat(isHuaweiPushTarget("Xiaomi")).isFalse()
        assertThat(isHuaweiPushTarget("HONOR")).isFalse()
    }

    @Test
    fun `valid opaque payload is mapped to common push data`() {
        val result = HuaweiPushParser().parse(
            mapOf(
                "event_id" to "\$event",
                "room_id" to "!room:yizhesy.cn",
                "unread" to "2",
                "cs" to "client-secret",
            )
        )

        assertThat(result?.eventId?.value).isEqualTo("\$event")
        assertThat(result?.roomId?.value).isEqualTo("!room:yizhesy.cn")
        assertThat(result?.unread).isEqualTo(2)
        assertThat(result?.clientSecret).isEqualTo("client-secret")
    }

    @Test
    fun `payload without routing identifiers is rejected`() {
        assertThat(HuaweiPushParser().parse(mapOf("cs" to "secret"))).isNull()
    }
}
