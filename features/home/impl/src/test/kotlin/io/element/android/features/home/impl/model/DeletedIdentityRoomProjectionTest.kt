/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.model

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection
import org.junit.Test
import java.security.MessageDigest

class DeletedIdentityRoomProjectionTest {
    @Test
    fun `deleted peer suppresses direct conversation but not another account group`() {
        val deletedUserId = "@deleted:example.test"
        val projection = FakeDeletedIdentityProjection(setOf(hash(deletedUserId)))
        val deletedHero = AvatarData(
            id = deletedUserId,
            name = "Deleted",
            size = AvatarSize.RoomListItem,
        )

        assertThat(
            aRoomListRoomSummary(isDm = true, isDirect = true, heroes = listOf(deletedHero))
                .referencesDeletedDirectPeer(projection)
        ).isTrue()
        assertThat(
            aRoomListRoomSummary(isDm = false, isDirect = false, heroes = listOf(deletedHero))
                .referencesDeletedDirectPeer(projection)
        ).isFalse()
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
