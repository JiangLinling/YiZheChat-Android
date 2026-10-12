/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings
import io.element.android.x.security.ManagementControlWorker
import java.util.concurrent.TimeUnit

class ManagementControlInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val bindings = context.bindings<AppBindings>()
        bindings.managementControlCoordinator().startAsync()
        val request = PeriodicWorkRequestBuilder<ManagementControlWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(DeletionRecoveryInitializer::class.java)

    private companion object {
        const val UNIQUE_WORK_NAME = "yizhechat-management-control-sync-v1"
        const val SYNC_INTERVAL_MINUTES = 15L
    }
}
