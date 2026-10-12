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

class FriendQrCodeTest {
    @Test
    fun `round trip YiZhe friend code`() {
        val userId = UserId("@alice:example.org")
        assertThat(FriendQrCode.decode(FriendQrCode.encode(userId))).isEqualTo(userId)
    }

    @Test
    fun `accept raw and matrix to user ids`() {
        val userId = UserId("@alice:example.org")
        assertThat(FriendQrCode.decode(userId.value)).isEqualTo(userId)
        assertThat(FriendQrCode.decode("https://matrix.to/#/%40alice%3Aexample.org")).isEqualTo(userId)
    }

    @Test
    fun `reject unrelated and malformed codes`() {
        assertThat(FriendQrCode.decode("https://example.org/not-a-friend")).isNull()
        assertThat(FriendQrCode.decode("@missing-server")).isNull()
    }
}
