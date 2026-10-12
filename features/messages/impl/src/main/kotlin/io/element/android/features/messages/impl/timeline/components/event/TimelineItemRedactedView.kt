/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.libraries.designsystem.icons.CompoundDrawables
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.features.messages.impl.R

@Composable
fun TimelineItemRedactedView(
    @Suppress("UNUSED_PARAMETER") content: TimelineItemRedactedContent,
    isMine: Boolean,
    trustedReason: TrustedRedactionReason?,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier
) {
    val text = when (trustedReason) {
        TrustedRedactionReason.USER_WITHDRAWN -> stringResource(
            if (isMine) R.string.message_withdrawn_by_you else R.string.message_withdrawn_by_other
        )
        TrustedRedactionReason.ADMINISTRATIVE_REVOCATION -> stringResource(R.string.message_removed_by_security_policy)
        else -> stringResource(id = CommonStrings.common_message_removed)
    }
    TimelineItemInformativeView(
        text = text,
        iconDescription = stringResource(id = CommonStrings.common_message_removed),
        iconResourceId = CompoundDrawables.ic_compound_delete,
        onContentLayoutChange = onContentLayoutChange,
        modifier = modifier
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemRedactedViewPreview() = ElementPreview {
    TimelineItemRedactedView(
        TimelineItemRedactedContent,
        isMine = false,
        trustedReason = null,
        onContentLayoutChange = {},
    )
}
