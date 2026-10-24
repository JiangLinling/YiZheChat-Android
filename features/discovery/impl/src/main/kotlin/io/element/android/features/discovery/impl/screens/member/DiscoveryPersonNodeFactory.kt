/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.*
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.node
import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.DiscoveryRootNavigation
import io.element.android.features.discovery.impl.DiscoveryRootTab
import io.element.android.features.discovery.impl.DiscoveryProfileRootView
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import io.element.android.features.discovery.impl.actions.DiscoveryBusinessActions
import io.element.android.features.discovery.impl.session.AndroidDiscoveryIdentityGate
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

@Inject
class DiscoveryPersonNodeFactory internal constructor(
    private val reader: DiscoveryPersonReader,
    private val port: DefaultDiscoveryPersonEditorPort,
    private val actions: DiscoveryBusinessActions,
    gate: AndroidDiscoveryIdentityGate,
    @SessionCoroutineScope scope: CoroutineScope,
) {
    private val accountRevision = gate.scope.runningFold(0L) { revision, _ -> revision + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun profile(buildContext: BuildContext, resumed: StateFlow<Boolean>, onRoot: (DiscoveryRootTab) -> Unit,
        onPersonal: (DiscoveryPersonalKind) -> Unit, onSearch: () -> Unit, onAddresses: () -> Unit,
        onPeople: () -> Unit, onChildren: () -> Unit, onMallOrders: () -> Unit, onEdit: (PersonEditTarget) -> Unit) = node(buildContext) { modifier ->
        val account by accountRevision.collectAsState(); val foreground by resumed.collectAsState()
        if (foreground && LocalNodeTargetVisibility.current) key(account) {
            var person by remember { mutableStateOf<MemberPersonRecord?>(null) }
            var loading by remember { mutableStateOf(true) }; var failed by remember { mutableStateOf(false) }
            var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(retry) {
                person = null; loading = true; failed = false
                try { person = reader.self() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
                loading = false
            }
            DiscoveryRootNavigation(DiscoveryRootTab.PROFILE, onRoot, modifier) {
                DiscoveryProfileRootView(onPersonal, onSearch, onAddresses, onPeople, onChildren, onMallOrders,
                    person, loading, failed, onRetry = { retry++ }, onEditProfile = { person?.let { onEdit(it.target) } })
            }
        }
    }

    internal fun editor(buildContext: BuildContext, target: PersonEditTarget, resumed: StateFlow<Boolean>,
        onBack: () -> Unit, onSaved: (PersonEditResult, Boolean) -> Unit, allowUse: Boolean) =
        DiscoveryPersonEditorNode(buildContext, target, port, accountRevision, resumed, ::memberBusinessDate, onBack, onSaved, onAvatar = null, allowUse = allowUse)

    internal fun people(buildContext: BuildContext, childrenOnly: Boolean, resumed: StateFlow<Boolean>, revision: StateFlow<Long>,
        onBack: () -> Unit, onEdit: (PersonEditTarget) -> Unit) = node(buildContext) { modifier ->
        val foreground by resumed.collectAsState()
        val account by accountRevision.collectAsState()
        val refresh by revision.collectAsState()
        if (foreground && LocalNodeTargetVisibility.current) key(account) {
            var state by remember { mutableStateOf(MemberPeopleState()) }
            var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(refresh, retry) {
                state = MemberPeopleState()
                try {
                    // Resume a confirmed/pending personal operation before exposing another create form.
                    val saved = actions.personResult()
                    val pending = actions.pendingPerson()
                    val pendingTarget = pending?.let {
                        if (it.child) PersonEditTarget(if (it.childId == null) PersonEditKind.NEW_CHILD else PersonEditKind.CHILD, it.childId)
                        else reader.self().target
                    }
                    state = state.copy(resume = saved?.target ?: pendingTarget)
                    val self = reader.self()
                    val children = reader.children()
                    require(children.none { it.target.userId == self.target.userId })
                    state = MemberPeopleState(loading = false, self = self, children = children, resume = saved?.target ?: pendingTarget)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { state = state.copy(loading = false, failed = true) }
            }
            DiscoveryPeopleView(state, childrenOnly, onBack, onEdit, onRetry = { retry++ }, modifier = modifier)
        }
    }
}
