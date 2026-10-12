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
import org.junit.Test

class BackstackExtTest {
    @Test
    fun `safeRoot restores a target that exists underneath the active child`() {
        val backstack = BackStack(initialElement = "security-setup", savedStateMap = null)
        backstack.push("analytics-opt-in")

        backstack.safeRoot("security-setup")

        assertThat(backstack.activeElement).isEqualTo("security-setup")
    }

    @Test
    fun `safeRoot keeps the current active root unchanged`() {
        val backstack = BackStack(initialElement = "home", savedStateMap = null)

        backstack.safeRoot("home")

        assertThat(backstack.activeElement).isEqualTo("home")
        assertThat(backstack.elements.value).hasSize(1)
    }
}
