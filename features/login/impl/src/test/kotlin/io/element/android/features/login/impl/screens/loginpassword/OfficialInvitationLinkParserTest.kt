/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfficialInvitationLinkParserTest {
    private val publicId = "abcdefghijklmnopqrstuvwx"

    @Test
    fun `accepts only exact official HTTPS invitation links`() {
        assertThat(OfficialInvitationLinkParser.publicIdOrNull("https://www.yizhesy.cn/i/$publicId"))
            .isEqualTo(publicId)
        assertThat(OfficialInvitationLinkParser.publicIdOrNull(" https://yizhesy.cn/i/$publicId/ "))
            .isEqualTo(publicId)
    }

    @Test
    fun `rejects lookalike hosts and links with untrusted components`() {
        val rejected = listOf(
            "http://www.yizhesy.cn/i/$publicId",
            "https://www.yizhesy.cn.evil.example/i/$publicId",
            "https://www.yizhesy.cn@evil.example/i/$publicId",
            "https://user@www.yizhesy.cn/i/$publicId",
            "https://www.yizhesy.cn:443/i/$publicId",
            "https://www.yizhesy.cn/i/$publicId?code=123456",
            "https://www.yizhesy.cn/i/$publicId#fragment",
            "https://www.yizhesy.cn/other/$publicId",
            "https://www.yizhesy.cn/i/short",
        )

        rejected.forEach { value ->
            assertThat(OfficialInvitationLinkParser.publicIdOrNull(value)).isNull()
        }
    }
}
