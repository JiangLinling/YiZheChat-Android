/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.poll.api.pollcontent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text

/** Shared visual treatment; callers retain ownership of drafts, validation and submission. */
@Composable
fun InteractionFormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textSecondary,
        )
        InteractionFormCard(content = content)
    }
}

@Composable
fun InteractionFormCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(ElementTheme.colors.bgCanvasDefault)
            .border(1.dp, ElementTheme.colors.borderDisabled, shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun InteractionNumberBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = 26.dp, minHeight = 26.dp)
            .background(ElementTheme.colors.bgAccentRest.copy(alpha = 0.09f), RoundedCornerShape(7.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textActionAccent,
        )
    }
}

@Composable
fun InteractionFormField(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 3,
) {
    val fieldStyle = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 15.sp, lineHeight = 22.sp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 5,
        modifier = modifier.fillMaxWidth().heightIn(min = if (singleLine) 48.dp else 72.dp).padding(vertical = 12.dp),
        textStyle = fieldStyle.copy(color = ElementTheme.colors.textPrimary),
        cursorBrush = SolidColor(ElementTheme.colors.textActionAccent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        decorationBox = { field ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(hint, style = fieldStyle, color = ElementTheme.colors.textSecondary)
                field()
            }
        },
    )
}
