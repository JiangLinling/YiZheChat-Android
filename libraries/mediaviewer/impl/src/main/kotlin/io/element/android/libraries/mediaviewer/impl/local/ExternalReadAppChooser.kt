/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.components.dialogs.ListOption
import io.element.android.libraries.designsystem.components.dialogs.SingleSelectionDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.securestorage.api.SecureMediaExternalRecipient
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ExternalReadAppChooser(
    options: List<ExternalReadRecipientOption>,
    onSelect: (SecureMediaExternalRecipient) -> Unit,
    onDismiss: () -> Unit,
) {
    SingleSelectionDialog(
        title = stringResource(CommonStrings.screen_media_viewer_external_read_choose_app),
        options = options.map { ListOption(it.label) }.toImmutableList(),
        onSelectOption = { onSelect(options[it].recipient) },
        onDismissRequest = onDismiss,
    )
}

@PreviewsDayNight
@Composable
internal fun ExternalReadAppChooserPreview(
    @PreviewParameter(ExternalReadAppChooserPreviewProvider::class) options: List<ExternalReadRecipientOption>,
) = ElementPreview {
    ExternalReadAppChooser(options, {}, {})
}

internal class ExternalReadAppChooserPreviewProvider : PreviewParameterProvider<List<ExternalReadRecipientOption>> {
    override val values = sequenceOf(
        listOf(previewRecipient("Office reader", 1)),
        listOf(previewRecipient("Office reader", 1), previewRecipient("PDF reader", 2)),
    )
}

private fun previewRecipient(label: String, uid: Int) = ExternalReadRecipientOption(
    SecureMediaExternalRecipient(uid, "example.reader$uid", "example.reader$uid.Viewer"),
    label,
)
