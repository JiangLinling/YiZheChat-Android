/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.push
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import org.junit.Test

class LoggedInNavigationRestorerTest {
    @Test
    fun `original room is restored after temporary startup wait`() {
        val original = BackStack<LoggedInFlowNode.NavTarget>(LoggedInFlowNode.NavTarget.Home, savedStateMap = null)
        val room = LoggedInFlowNode.NavTarget.Room(requireNotNull(RoomIdOrAlias.from("!qa:example.org")))
        original.push(room)
        val restorer = LoggedInNavigationRestorer(original.elements.value)
        val recovering = BackStack<LoggedInFlowNode.NavTarget>(LoggedInFlowNode.NavTarget.Placeholder, savedStateMap = null)
        assertThat(recovering.activeElement).isEqualTo(LoggedInFlowNode.NavTarget.Placeholder)
        restorer.onReady(recovering, wasReady = false)
        assertThat(recovering.activeElement).isEqualTo(room)
        assertThat(recovering.elements.value.map { it.key.navTarget }).contains(LoggedInFlowNode.NavTarget.Home)
    }

    @Test
    fun `equivalent ready emissions do not reset the users current room`() {
        val restorer = LoggedInNavigationRestorer(null)
        val backstack = BackStack<LoggedInFlowNode.NavTarget>(LoggedInFlowNode.NavTarget.Placeholder, savedStateMap = null)
        restorer.onReady(backstack, wasReady = false)
        val room = LoggedInFlowNode.NavTarget.Room(requireNotNull(RoomIdOrAlias.from("!qa:example.org")))
        backstack.push(room)
        restorer.onReady(backstack, wasReady = true)
        assertThat(backstack.activeElement).isEqualTo(room)
    }

    @Test
    fun `old security setup snapshot does not override current admission result`() {
        val old = BackStack<LoggedInFlowNode.NavTarget>(LoggedInFlowNode.NavTarget.SecuritySetup, savedStateMap = null)
        val restorer = LoggedInNavigationRestorer(old.elements.value)
        val recovering = BackStack<LoggedInFlowNode.NavTarget>(LoggedInFlowNode.NavTarget.Placeholder, savedStateMap = null)
        restorer.onReady(recovering, wasReady = false)
        assertThat(recovering.activeElement).isEqualTo(LoggedInFlowNode.NavTarget.Home)
    }
}
