/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import org.junit.Test

class FriendQrScanResultTest {
    private val me = UserId("@alice:example.org")

    @Test
    fun `valid own codes remain valid but never open a profile`() {
        listOf(FriendQrCode.encode(me), me.value, "https://matrix.to/#/%40alice%3Aexample.org").forEach { code ->
            assertThat(handleFriendQrCode(code, { it == me }, { error("Self profile must not open") }))
                .isEqualTo(FriendQrScanResult.Self)
        }
    }

    @Test
    fun `valid other account opens exactly that profile once`() {
        val friend = UserId("@bob:example.org")
        val opened = mutableListOf<UserId>()
        assertThat(handleFriendQrCode(FriendQrCode.encode(friend), { it == me }, opened::add))
            .isEqualTo(FriendQrScanResult.Handled)
        assertThat(opened).containsExactly(friend)
    }

    @Test
    fun `invalid and malformed input never reaches identity or navigation`() {
        listOf("", "https://example.org", "@missing-server", "yizhe://friend/v1?user_id=%ZZ").forEach { code ->
            assertThat(handleFriendQrCode(code, { error("No valid identity") }, { error("Invalid profile") }))
                .isEqualTo(FriendQrScanResult.Invalid)
        }
    }
}
