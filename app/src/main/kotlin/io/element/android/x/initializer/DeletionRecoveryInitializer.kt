/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** Finishes durable local deletion work before a newly launched process opens account UI. */
class DeletionRecoveryInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val bindings = context.bindings<AppBindings>()
        val permanentRecovery = runBlocking {
            bindings.permanentAccountDeletionCoordinator().resumePending(coldStart = true)
        }
        if (permanentRecovery.retryRequired) {
            Timber.w(
                "Permanent deletion recovery requires retry: retry=%d unreadable=%d waitingMatrix=%d",
                permanentRecovery.retryRequiredCount,
                permanentRecovery.unreadableRecordCount,
                permanentRecovery.waitingForMatrixDeactivationCount,
            )
        }
        val recovery = runBlocking {
            bindings.dataDeletionCoordinator().resumePending(coldStart = true)
        }
        if (recovery.retryRequired) {
            val retryCount = recovery.operationReports.count { it.retryRequired }
            Timber.w(
                "Deletion recovery requires retry: pending=%d unreadable=%d",
                retryCount,
                recovery.unreadableJournalEntries,
            )
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(PlatformInitializer::class.java)
}
