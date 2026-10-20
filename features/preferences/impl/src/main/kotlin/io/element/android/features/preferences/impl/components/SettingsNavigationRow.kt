/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun SettingsNavigationRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    summary: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = ListItemContent.Icon(IconSource.Vector(icon)),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = onClick,
    )
}
