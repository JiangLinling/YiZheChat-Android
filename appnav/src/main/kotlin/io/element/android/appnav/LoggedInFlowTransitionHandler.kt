/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.navigation.transition.JumpToEndTransitionHandler
import com.bumble.appyx.core.navigation.transition.ModifierTransitionHandler
import com.bumble.appyx.core.navigation.transition.TransitionDescriptor
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.transitionhandler.rememberBackstackSlider

/**
 * Completes startup-gate replacements immediately; later user navigation keeps its slide transition.
 */
class LoggedInFlowTransitionHandler(
    private val backstack: BackStack<LoggedInFlowNode.NavTarget>,
    private val slider: ModifierTransitionHandler<LoggedInFlowNode.NavTarget, BackStack.State>,
    private val fader: ModifierTransitionHandler<LoggedInFlowNode.NavTarget, BackStack.State>,
) : ModifierTransitionHandler<LoggedInFlowNode.NavTarget, BackStack.State>() {
    override fun createModifier(
        modifier: Modifier,
        transition: Transition<BackStack.State>,
        descriptor: TransitionDescriptor<LoggedInFlowNode.NavTarget, BackStack.State>
    ): Modifier {
        val hasStartupGate = backstack.elements.value.any { element ->
            isStartupGate(element.key.navTarget)
        }
        val handler = if (hasStartupGate) fader else slider
        return handler.createModifier(modifier, transition, descriptor)
    }
}

internal fun isStartupGate(target: LoggedInFlowNode.NavTarget): Boolean = when (target) {
    LoggedInFlowNode.NavTarget.Placeholder, LoggedInFlowNode.NavTarget.SecuritySetup,
    is LoggedInFlowNode.NavTarget.StartupFailure -> true
    else -> false
}

@Composable
fun rememberLoggedInFlowTransitionHandler(
    backstack: BackStack<LoggedInFlowNode.NavTarget>,
): ModifierTransitionHandler<LoggedInFlowNode.NavTarget, BackStack.State> {
    val slider = rememberBackstackSlider<LoggedInFlowNode.NavTarget>(
        transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
    )
    val fader = remember { JumpToEndTransitionHandler<LoggedInFlowNode.NavTarget, BackStack.State>() }
    return remember(backstack, slider, fader) {
        LoggedInFlowTransitionHandler(backstack, slider, fader)
    }
}
