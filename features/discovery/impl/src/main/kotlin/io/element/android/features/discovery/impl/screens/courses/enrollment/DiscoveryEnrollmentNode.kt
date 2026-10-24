/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.element.android.features.discovery.impl.screens.member.PersonEditKind
import io.element.android.features.discovery.impl.screens.member.PersonEditTarget

/** Owns P14/P16/P15 page state and return refresh, with the production protected port supplied by its factory. */
internal class DiscoveryEnrollmentNode(
    buildContext: BuildContext,
    private val courseId: Long,
    private val sessionId: Long,
    private val port: DiscoveryEnrollmentPort,
    private val accountRevision: StateFlow<Long>,
    private val resumed: StateFlow<Boolean>,
    private val resumeRevision: StateFlow<Long>,
    private val nowMillis: () -> Long,
    private val onBack: () -> Unit,
    private val onAddChild: (() -> Unit)?,
    private val onEditTarget: ((PersonEditTarget) -> Unit)?,
    private val onOrder: ((String) -> Unit)?,
    private val onCourses: () -> Unit,
    private val returnedPerson: StateFlow<Long?>? = null,
    private val onPersonConsumed: () -> Unit = {},
) : Node(buildContext) {
    @Composable override fun View(modifier: Modifier) {
        val foreground by resumed.collectAsState()
        val account by accountRevision.collectAsState()
        val revision by resumeRevision.collectAsState()
        val visible = LocalNodeTargetVisibility.current
        val returned = returnedPerson?.collectAsState()?.value
        val controller = remember(account, foreground) { DiscoveryEnrollmentController(courseId, sessionId, port, nowMillis) }
        val scope = rememberCoroutineScope()
        val state by controller.state.collectAsState()
        fun leaveResult(destination: () -> Unit) {
            if (state.receipt == null) destination() else scope.launch { controller.dismissResult(destination) }
        }
        BackHandler(enabled = foreground && visible && state.receipt != null) { leaveResult(onBack) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        DisposableEffect(account) { onDispose { onPersonConsumed() } }
        LaunchedEffect(controller, visible, revision, returned) {
            if (!foreground) { onPersonConsumed(); return@LaunchedEffect }
            if (!visible) return@LaunchedEffect
            when (controller.state.value.phase) {
                EnrollmentPhase.LOADING, EnrollmentPhase.FAILED -> controller.load()
                EnrollmentPhase.EDITING, EnrollmentPhase.QUOTED -> controller.refreshOptions()
                EnrollmentPhase.UNCERTAIN, EnrollmentPhase.RECEIPT -> controller.checkResult()
                else -> Unit
            }
            returned?.let { if (controller.state.value.editable) { controller.useReturnedPerson(it); onPersonConsumed() } }
        }
        if (foreground && visible) DiscoveryEnrollmentView(
            state = state,
            onEdit = controller::edit,
            onQuote = { scope.launch { controller.requestQuote() } },
            onSubmit = { scope.launch { controller.submit() } },
            onCheckResult = { scope.launch { controller.checkResult() } },
            onRetry = { scope.launch {
                controller.load()
                returned?.let { if (controller.state.value.editable) { controller.useReturnedPerson(it); onPersonConsumed() } }
            } },
            onBack = { leaveResult(onBack) }, onAddChild = onAddChild, onEditPerson = onEditTarget?.let { edit -> { id: Long ->
                state.options?.people?.firstOrNull { it.id == id }?.let { person ->
                    edit(PersonEditTarget(if (person.relation == EnrollmentRelation.SELF) PersonEditKind.SELF else PersonEditKind.CHILD, id))
                }
                Unit
            } },
            onOrder = onOrder, onCourses = { leaveResult(onCourses) }, modifier = modifier,
        )
    }
}
