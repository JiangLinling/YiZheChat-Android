/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.push
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.test.A_ROOM_ID
import org.junit.Test

class CreateRoomNavigationTest {
    @Test
    fun `completed room creation replaces the creation flow`() {
        val backstack: BackStack<LoggedInFlowNode.NavTarget> = BackStack(
            initialElement = LoggedInFlowNode.NavTarget.Home,
            savedStateMap = null,
        )
        backstack.push(LoggedInFlowNode.NavTarget.CreateRoom)

        backstack.replaceCreateRoomWithRoom(A_ROOM_ID)

        val activeRoom = backstack.activeElement as LoggedInFlowNode.NavTarget.Room
        val createRoomElement = backstack.elements.value.single {
            it.key.navTarget == LoggedInFlowNode.NavTarget.CreateRoom
        }
        assertThat(activeRoom.roomIdOrAlias).isEqualTo(A_ROOM_ID.toRoomIdOrAlias())
        assertThat(createRoomElement.targetState).isEqualTo(BackStack.State.DESTROYED)
        assertThat(backstack.elements.value.map { it.key.navTarget })
            .contains(LoggedInFlowNode.NavTarget.Home)
    }
}
