/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.newRoot
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import io.element.android.features.discovery.impl.DiscoveryRootNavigation
import io.element.android.features.discovery.impl.DiscoveryRootTab
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Full native read/navigation flow. The owning Discovery node supplies the accepted reader and authorised business identity.
 * There is deliberately no default reader that pretends a missing backend is a successful empty page.
 */
internal class DiscoveryMemberFlowNode(
    buildContext: BuildContext,
    initialRoute: MemberRoute,
    private val presenter: DiscoveryMemberPresenter,
    private val editorPort: DiscoveryPersonEditorPort,
    private val addressPort: DiscoveryAddressPort,
    private val identity: StateFlow<Long>,
    private val accountRevision: StateFlow<Long>,
    private val resumed: StateFlow<Boolean>,
    private val resumeRevision: StateFlow<Long>,
    private val external: DiscoveryMemberActions,
    private val onHome: () -> Unit,
    private val today: () -> LocalDate,
    private val onAvatar: ((Long) -> Unit)?,
) : BaseFlowNode<MemberRoute>(
    BackStack(initialElement = initialRoute, savedStateMap = buildContext.savedStateMap), buildContext, emptyList(),
) {
    override fun resolve(navTarget: MemberRoute, buildContext: BuildContext): Node = if (navTarget is MemberRoute.EditPerson) {
        DiscoveryPersonEditorNode(buildContext, PersonEditTarget(navTarget.kind, navTarget.userId), editorPort,
            accountRevision, resumed, today, onBack = { backstack.pop() },
            onSaved = { result, use ->
                // Saved is an authorised server result. This only changes the Discovery business identity.
                if (use) external.selectIdentity(result.person.id)
                backstack.pop()
            }, onAvatar = navTarget.userId?.let { userId -> onAvatar?.let { pick -> { pick(userId) } } })
    } else if (navTarget == MemberRoute.Addresses) {
        DiscoveryAddressNode(buildContext, addressPort, accountRevision, resumed, chooseForOrder = false,
            onBack = { backstack.pop() }, onSelected = { backstack.pop() })
    } else node(buildContext) { modifier ->
        val person by identity.collectAsState()
        val foreground by resumed.collectAsState()
        val revision by resumeRevision.collectAsState()
        val active = LocalNodeTargetVisibility.current && foreground && person > 0
        val state = presenter.present(navTarget, person, active, revision)
        val actions = external.copy(
            back = { backstack.pop() },
            addresses = { if (active) backstack.push(MemberRoute.Addresses) },
            route = { destination ->
                if (destination is MemberRoute.Orders && navTarget is MemberRoute.Orders) backstack.replace(destination)
                else backstack.push(destination)
            },
            selectIdentity = { target ->
                val authorised = (state.page as? MemberPage.Identities)?.people?.any { it.id == target } == true
                if (active && authorised) external.selectIdentity(target)
            },
            editPerson = edit@{ target ->
                if (!active) return@edit
                if (target == null) {
                    backstack.push(MemberRoute.EditPerson(PersonEditKind.NEW_CHILD, null))
                } else {
                    val personToEdit = when (val page = state.page) {
                        is MemberPage.Profile -> page.identity.takeIf { it.id == target }
                        is MemberPage.Identities -> page.people.firstOrNull { it.id == target }
                        else -> null
                    }
                    if (personToEdit != null) backstack.push(MemberRoute.EditPerson(
                        if (personToEdit.child) PersonEditKind.CHILD else PersonEditKind.SELF, personToEdit.id))
                }
            },
        )
        if (navTarget == MemberRoute.Profile || navTarget == MemberRoute.Services) {
            DiscoveryRootNavigation(
                if (navTarget == MemberRoute.Profile) DiscoveryRootTab.PROFILE else DiscoveryRootTab.SERVICES,
                onSelect = { tab ->
                    when (tab) {
                        DiscoveryRootTab.HOME -> onHome()
                        DiscoveryRootTab.COURSES -> external.courses()
                        DiscoveryRootTab.SERVICES -> backstack.newRoot(MemberRoute.Services)
                        DiscoveryRootTab.PROFILE -> backstack.newRoot(MemberRoute.Profile)
                    }
                }, modifier = modifier,
            ) { DiscoveryMemberView(state, actions) }
        } else DiscoveryMemberView(state, actions, modifier)
    }

    @Composable override fun View(modifier: Modifier) { BackstackView(modifier) }
}
