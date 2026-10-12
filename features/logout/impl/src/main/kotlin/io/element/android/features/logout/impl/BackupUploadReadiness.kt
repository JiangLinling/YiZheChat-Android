/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.logout.impl

import io.element.android.libraries.matrix.api.encryption.BackupUploadState
import io.element.android.libraries.matrix.api.encryption.SteadyStateException

internal enum class BackupUploadProblem {
    CONNECTION,
    BACKUP_DISABLED,
    STATUS_LAGGED,
    UPLOAD_FAILED,
}

internal fun BackupUploadState.problemOrNull(): BackupUploadProblem? = when (this) {
    BackupUploadState.Error -> BackupUploadProblem.UPLOAD_FAILED
    is BackupUploadState.SteadyException -> when (exception) {
        is SteadyStateException.Connection -> BackupUploadProblem.CONNECTION
        is SteadyStateException.BackupDisabled -> BackupUploadProblem.BACKUP_DISABLED
        is SteadyStateException.Lagged -> BackupUploadProblem.STATUS_LAGGED
    }
    BackupUploadState.Unknown,
    BackupUploadState.Waiting,
    is BackupUploadState.Uploading,
    BackupUploadState.Done -> null
}

/**
 * A backup configured on the server is not proof that this device's latest room keys reached it.
 * Device removal is ready only after the SDK explicitly reports a completed upload steady state.
 */
internal fun BackupUploadState.isCompleteForDeviceRemoval(): Boolean = this == BackupUploadState.Done
