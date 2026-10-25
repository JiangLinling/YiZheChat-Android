/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.PendingSendLifetime
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import org.junit.Test

class RoomClientOwnershipTest {
    @Test
    fun `same account restored by another factory cannot borrow the old room`() {
        val oldFactory = Any()
        val currentFactory = Any()
        val room = RoomClientOwnership(oldFactory, A_SESSION_ID, A_ROOM_ID)
        assertThat(room.belongsTo(oldFactory, A_SESSION_ID, A_ROOM_ID)).isTrue()
        assertThat(room.belongsTo(currentFactory, A_SESSION_ID, A_ROOM_ID)).isFalse()
        assertThat(room.belongsTo(oldFactory, A_SESSION_ID_2, A_ROOM_ID)).isFalse()
        assertThat(room.belongsTo(oldFactory, A_SESSION_ID, A_ROOM_ID_2)).isFalse()
        assertThat(RoomClientOwnership(null, A_SESSION_ID, A_ROOM_ID).belongsTo(oldFactory, A_SESSION_ID, A_ROOM_ID)).isFalse()
    }

    @Test
    fun `close request rejects new observers while an original send still retains native dependencies`() {
        val factory = Any()
        val room = RoomClientOwnership(factory, A_SESSION_ID, A_ROOM_ID)
        var nativeClosed = false
        val lifetime = PendingSendLifetime { nativeClosed = true }
        val originalSend = lifetime.retain()
        room.close()
        lifetime.close()
        assertThat(nativeClosed).isFalse()
        assertThat(room.belongsTo(factory, A_SESSION_ID, A_ROOM_ID)).isFalse()
        originalSend.close()
        assertThat(nativeClosed).isTrue()
    }
}
