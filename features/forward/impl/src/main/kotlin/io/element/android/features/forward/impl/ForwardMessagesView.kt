/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun ForwardMessagesView(
    state: ForwardMessagesState,
    onForwardSuccess: (List<RoomId>) -> Unit,
) {
    AsyncActionView(
        async = state.forwardAction,
        onSuccess = {
            onForwardSuccess(it)
        },
        errorMessage = {
            stringResource(
                id = if (state.pairStates.values.any { it is ForwardPairState.Ambiguous || it is ForwardPairState.Sending }) {
                    CommonStrings.error_forward_result_unconfirmed
                } else {
                    CommonStrings.error_forward_incomplete
                },
            )
        },
        onErrorDismiss = {
            if (state.pairStates.values.any { it is ForwardPairState.Failed }) {
                state.eventSink(ForwardMessagesEvents.RetryFailed)
            } else {
                state.eventSink(ForwardMessagesEvents.ClearError)
            }
        },
    )
}

@PreviewsDayNight
@Composable
internal fun ForwardMessagesViewPreview(@PreviewParameter(ForwardMessagesStateProvider::class) state: ForwardMessagesState) = ElementPreview {
    ForwardMessagesView(
        state = state,
        onForwardSuccess = {}
    )
}
