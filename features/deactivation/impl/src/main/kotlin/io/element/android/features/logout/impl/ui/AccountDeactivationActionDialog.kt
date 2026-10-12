/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.dialogs.RetryDialog
import io.element.android.libraries.matrix.api.exception.AccountDeactivationException
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.features.deactivation.impl.R
import io.element.android.security.dataprotection.api.PermanentAccountDeletionSubmissionBlockedException

@Composable
fun AccountDeactivationActionDialog(
    state: AsyncAction<Unit>,
    onConfirmClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    when (state) {
        AsyncAction.Uninitialized ->
            Unit
        is AsyncAction.Confirming ->
            AccountDeactivationConfirmationDialog(
                onSubmitClick = onConfirmClick,
                onDismiss = onDismissDialog
            )
        is AsyncAction.Loading ->
            ProgressDialog(text = stringResource(CommonStrings.common_please_wait))
        is AsyncAction.Failure ->
            RetryDialog(
                title = stringResource(id = CommonStrings.dialog_title_error),
                content = stringResource(
                    id = when (state.error) {
                        is PermanentAccountDeletionSubmissionBlockedException -> R.string.screen_deactivate_account_service_upgrading
                        is AccountDeactivationException.InvalidCredentials -> R.string.screen_deactivate_account_invalid_password
                        else -> CommonStrings.error_unknown
                    }
                ),
                onRetry = onRetryClick,
                onDismiss = onDismissDialog,
            )
        is AsyncAction.Success -> Unit
    }
}
