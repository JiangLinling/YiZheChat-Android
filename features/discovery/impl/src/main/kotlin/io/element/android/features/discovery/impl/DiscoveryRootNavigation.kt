/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text

internal enum class DiscoveryRootTab(val label: Int) {
    HOME(R.string.screen_discovery_root_home),
    COURSES(R.string.screen_discovery_root_courses),
    SERVICES(R.string.screen_discovery_root_services),
    PROFILE(R.string.screen_discovery_root_profile),
}

@Composable
internal fun DiscoveryRootNavigation(selected: DiscoveryRootTab, onSelect: (DiscoveryRootTab) -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        NavigationBar {
            DiscoveryRootTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { if (tab != selected) onSelect(tab) },
                    icon = { Icon(imageVector = when (tab) {
                        DiscoveryRootTab.HOME -> if (tab == selected) CompoundIcons.HomeSolid() else CompoundIcons.Home()
                        DiscoveryRootTab.COURSES -> CompoundIcons.Document()
                        DiscoveryRootTab.SERVICES -> CompoundIcons.Calendar()
                        DiscoveryRootTab.PROFILE -> if (tab == selected) CompoundIcons.UserSolid() else CompoundIcons.User()
                    }, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        }
    }
}
