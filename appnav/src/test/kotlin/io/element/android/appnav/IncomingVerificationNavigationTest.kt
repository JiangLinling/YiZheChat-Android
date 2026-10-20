/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.singleTop
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingVerificationNavigationTest {
    private sealed interface Target {
        data object Home : Target
        data class Verification(val id: String) : Target
    }

    @Test
    fun `reproduce upstream singleTop duplicate Home key while old verification is exiting`() {
        val stack = BackStack<Target>(Target.Home, savedStateMap = null)
        stack.push(Target.Verification("first"))
        stack.onTransitionFinished(stack.elements.value.map { it.key })
        stack.pop()
        stack.singleTop(Target.Verification("second"))
        val keys = stack.elements.value.map { it.key }
        assertThat(keys.distinct().size).isLessThan(keys.size)
    }

    @Test
    fun `retry waits for previous exit then has unique keys and preserved Home`() = runTest {
        val stack = BackStack<Target>(Target.Home, savedStateMap = null)
        val navigation = IncomingVerificationNavigation(stack) { it is Target.Verification }
        assertThat(navigation.show("first", Target.Verification("first"), 1000)).isTrue()
        stack.onTransitionFinished(stack.elements.value.map { it.key })
        stack.pop()
        val retry = async { navigation.show("second", Target.Verification("second"), 1000) }
        runCurrent()
        assertThat(retry.isCompleted).isFalse()
        stack.onTransitionFinished(stack.elements.value.map { it.key })
        assertThat(retry.await()).isTrue()
        val keys = stack.elements.value.map { it.key }
        assertThat(keys.distinct()).hasSize(keys.size)
        assertThat(stack.activeElement).isEqualTo(Target.Verification("second"))
        stack.removeLast(Target.Verification("first"))
        assertThat(stack.activeElement).isEqualTo(Target.Verification("second"))
        stack.pop()
        assertThat(stack.activeElement).isEqualTo(Target.Home)
    }

    @Test
    fun `duplicate delivery cannot reopen completed request`() = runTest {
        val stack = BackStack<Target>(Target.Home, savedStateMap = null)
        val navigation = IncomingVerificationNavigation(stack) { it is Target.Verification }
        navigation.show("first", Target.Verification("first"), 1000)
        assertThat(navigation.show("first", Target.Verification("first"), 1000)).isFalse()
        stack.pop()
        stack.onTransitionFinished(stack.elements.value.map { it.key })
        assertThat(navigation.show("first", Target.Verification("first"), 1000)).isFalse()
        assertThat(stack.activeElement).isEqualTo(Target.Home)
    }

    @Test
    fun `expired queued request does not interrupt current verification`() = runTest {
        val stack = BackStack<Target>(Target.Home, savedStateMap = null)
        val navigation = IncomingVerificationNavigation(stack) { it is Target.Verification }
        navigation.show("first", Target.Verification("first"), 1000)
        assertThat(navigation.show("expired", Target.Verification("expired"), 1000)).isFalse()
        assertThat(stack.activeElement).isEqualTo(Target.Verification("first"))
    }
}
