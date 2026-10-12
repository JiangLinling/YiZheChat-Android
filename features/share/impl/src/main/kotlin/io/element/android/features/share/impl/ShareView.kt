/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.res.stringResource
import io.element.android.libraries.ui.strings.R
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.features.share.api.ShareResult
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

@Composable
fun ShareView(
    state: ShareState,
    onShareSuccess: (ShareResult) -> Unit,
) {
    AsyncActionView(
        async = state.shareAction,
        onSuccess = {
            onShareSuccess(it)
        },
        errorMessage = { error ->
            stringResource(when (error) {
                is ContentProtectionException.RecipientDeviceUnavailable -> R.string.error_share_recipient_device_unavailable
                is ContentProtectionException.AccessDenied -> R.string.error_share_security_access_denied
                is ContentProtectionException.KmsUnavailable -> R.string.error_share_security_service_unavailable
                else -> R.string.error_share_send_failed
            })
        },
        onErrorDismiss = {
            state.eventSink(ShareEvents.ClearError)
        },
    )
}

@PreviewsDayNight
@Composable
internal fun ShareViewPreview(@PreviewParameter(ShareStateProvider::class) state: ShareState) = ElementPreview {
    ShareView(
        state = state,
        onShareSuccess = {}
    )
}
