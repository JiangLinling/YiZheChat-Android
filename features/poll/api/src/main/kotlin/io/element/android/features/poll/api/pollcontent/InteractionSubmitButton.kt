/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.poll.api.pollcontent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text

/** Shared compact action for poll and relay forms, using the existing accent tokens. */
@Composable
fun InteractionSubmitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showProgress: Boolean = false,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !showProgress,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ElementTheme.colors.bgAccentRest,
            contentColor = ElementTheme.colors.textOnSolidPrimary,
            disabledContainerColor = ElementTheme.colors.bgAccentRest.copy(alpha = 0.12f),
            disabledContentColor = ElementTheme.colors.textSecondary,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ElementTheme.colors.textOnSolidPrimary)
            Text(text, style = ElementTheme.typography.fontBodyMdMedium)
        }
    }
}
