/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.logout.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.encryption.BackupUploadState
import io.element.android.libraries.matrix.api.encryption.SteadyStateException
import org.junit.Test

class BackupUploadReadinessTest {
    @Test
    fun `backup failures are classified without exposing SDK details`() {
        assertThat(BackupUploadState.Error.problemOrNull()).isEqualTo(BackupUploadProblem.UPLOAD_FAILED)
        assertThat(BackupUploadState.SteadyException(SteadyStateException.Connection("secret")).problemOrNull())
            .isEqualTo(BackupUploadProblem.CONNECTION)
        assertThat(BackupUploadState.SteadyException(SteadyStateException.BackupDisabled("secret")).problemOrNull())
            .isEqualTo(BackupUploadProblem.BACKUP_DISABLED)
        assertThat(BackupUploadState.SteadyException(SteadyStateException.Lagged("secret")).problemOrNull())
            .isEqualTo(BackupUploadProblem.STATUS_LAGGED)
    }

    @Test
    fun `only explicit upload completion is ready for destructive device removal`() {
        assertThat(BackupUploadState.Done.isCompleteForDeviceRemoval()).isTrue()
        assertThat(BackupUploadState.Unknown.isCompleteForDeviceRemoval()).isFalse()
        assertThat(BackupUploadState.Waiting.isCompleteForDeviceRemoval()).isFalse()
        assertThat(BackupUploadState.Uploading(3, 4).isCompleteForDeviceRemoval()).isFalse()
        assertThat(BackupUploadState.Error.isCompleteForDeviceRemoval()).isFalse()
    }
}
