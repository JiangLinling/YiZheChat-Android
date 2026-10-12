/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav

import com.bumble.appyx.core.navigation.NavElements
import com.bumble.appyx.navmodel.backstack.BackStack

/** The caller must have revalidated the current session, security setup and signed CORE receipt. */
internal class LoggedInNavigationRestorer(
    savedElements: NavElements<LoggedInFlowNode.NavTarget, BackStack.State>?,
) {
    private var pending = savedElements?.takeIf { elements ->
        elements.isNotEmpty() && elements.all { element ->
            when (element.key.navTarget) {
                LoggedInFlowNode.NavTarget.Home,
                is LoggedInFlowNode.NavTarget.Room,
                is LoggedInFlowNode.NavTarget.UserProfile,
                is LoggedInFlowNode.NavTarget.Settings,
                LoggedInFlowNode.NavTarget.AddFriend,
                LoggedInFlowNode.NavTarget.MyQrCode,
                LoggedInFlowNode.NavTarget.RoomDirectory -> true
                else -> false
            }
        }
    }

    fun onReady(backstack: BackStack<LoggedInFlowNode.NavTarget>, wasReady: Boolean) {
        val restored = pending
        pending = null
        when {
            restored != null -> backstack.accept(ReplaceAllOperation(restored))
            !wasReady -> backstack.safeRoot(LoggedInFlowNode.NavTarget.Home)
        }
    }
}
