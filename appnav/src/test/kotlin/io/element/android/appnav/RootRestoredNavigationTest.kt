/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.root.RootNavState
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.LoggedInState
import org.junit.Test

class RootRestoredNavigationTest {
    @Test
    fun `saved login screen does not mask a valid stored session`() {
        val navState = RootNavState(
            cacheIndex = 0,
            loggedInState = LoggedInState.LoggedIn("@yizhe01:yizhesy.cn", isTokenValid = true),
        )

        assertThat(restoredNavigationMatches(navState, listOf(RootFlowNode.NavTarget.NotLoggedInFlow(null)))).isFalse()
    }

    @Test
    fun `matching logged in navigation is preserved`() {
        val sessionId = SessionId("@yizhe01:yizhesy.cn")
        val navState = RootNavState(
            cacheIndex = 3,
            loggedInState = LoggedInState.LoggedIn(sessionId.value, isTokenValid = true),
        )

        assertThat(
            restoredNavigationMatches(
                navState,
                listOf(RootFlowNode.NavTarget.LoggedInFlow(sessionId, navId = 3)),
            )
        ).isTrue()
    }

    @Test
    fun `logged in boot snapshot is recomputed instead of restored`() {
        val navState = RootNavState(
            cacheIndex = 3,
            loggedInState = LoggedInState.LoggedIn("@yizhe01:yizhesy.cn", isTokenValid = true),
        )

        assertThat(restoredNavigationMatches(navState, listOf(RootFlowNode.NavTarget.LoggedInBoot))).isFalse()
    }

    @Test
    fun `signed out navigation only matches an invalid stored token`() {
        val sessionId = SessionId("@yizhe01:yizhesy.cn")
        val target = RootFlowNode.NavTarget.SignedOutFlow(sessionId)

        assertThat(
            restoredNavigationMatches(
                RootNavState(0, LoggedInState.LoggedIn(sessionId.value, isTokenValid = false)),
                listOf(target),
            )
        ).isTrue()
        assertThat(
            restoredNavigationMatches(
                RootNavState(0, LoggedInState.LoggedIn(sessionId.value, isTokenValid = true)),
                listOf(target),
            )
        ).isFalse()
    }
}
