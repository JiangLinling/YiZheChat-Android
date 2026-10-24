/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.features.discovery.impl.screens.member.MemberPersonRecord
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun DiscoveryProfileRootView(onPersonal: (DiscoveryPersonalKind) -> Unit, onSearch: () -> Unit,
    onAddresses: () -> Unit, onPeople: () -> Unit, onChildren: () -> Unit, onMallOrders: () -> Unit,
    person: MemberPersonRecord?, loading: Boolean, failed: Boolean, onRetry: () -> Unit, onEditProfile: () -> Unit, modifier: Modifier = Modifier) {
    RootMenu(R.string.screen_discovery_root_profile, modifier) {
        if (loading) io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator()
        person?.let { profile ->
            Text(profile.identity.name, style = ElementTheme.typography.fontHeadingMdBold)
            if (profile.maskedPhone.isNotBlank()) Text(profile.maskedPhone, color = ElementTheme.colors.textSecondary)
            Text(stringResource(R.string.screen_discovery_profile_completion, profile.completion), color = ElementTheme.colors.textSecondary)
            RootMenuAction(R.string.screen_discovery_member_edit_profile, onEditProfile)
        }
        if (failed) {
            Text(stringResource(R.string.screen_discovery_person_load_failed))
            RootMenuAction(R.string.screen_discovery_retry, onRetry)
        }
        RootMenuAction(R.string.screen_discovery_member_identities, onPeople)
        RootMenuAction(R.string.screen_discovery_member_children, onChildren)
        RootMenuAction(R.string.screen_discovery_mall_orders, onMallOrders)
        RootMenuAction(R.string.screen_discovery_my_favorites) { onPersonal(DiscoveryPersonalKind.FAVORITES) }
        RootMenuAction(R.string.screen_discovery_my_follows) { onPersonal(DiscoveryPersonalKind.FOLLOWS) }
        RootMenuAction(R.string.screen_discovery_my_history) { onPersonal(DiscoveryPersonalKind.HISTORY) }
        RootMenuAction(R.string.screen_discovery_my_replies) { onPersonal(DiscoveryPersonalKind.REPLIES) }
        RootMenuAction(R.string.screen_discovery_search_title, onSearch)
        RootMenuAction(R.string.screen_discovery_member_addresses, onAddresses)
    }
}

@Composable
private fun RootMenu(@StringRes title: Int, modifier: Modifier, actions: @Composable () -> Unit) {
    LazyColumn(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(title), style = ElementTheme.typography.fontHeadingLgBold) }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() } }
    }
}

@Composable
private fun RootMenuAction(@StringRes title: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick,
        modifier = Modifier.fillMaxWidth().background(ElementTheme.colors.bgSubtlePrimary, RoundedCornerShape(14.dp)).padding(8.dp)) {
        Text(stringResource(title), style = ElementTheme.typography.fontBodyLgMedium)
    }
}
