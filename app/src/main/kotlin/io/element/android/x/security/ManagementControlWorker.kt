/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.libraries.workmanager.api.di.WorkerKey

@AssistedInject
class ManagementControlWorker(
    @Assisted params: WorkerParameters,
    @ApplicationContext context: Context,
    private val coordinator: ManagementControlCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatchingExceptions { coordinator.joinStartupOrSynchronize() }
        .fold(
            onSuccess = { complete -> if (complete) Result.success() else Result.retry() },
            onFailure = { Result.retry() },
        )

    @ContributesIntoMap(AppScope::class, binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>())
    @WorkerKey(ManagementControlWorker::class)
    @AssistedFactory
    interface Factory : MetroWorkerFactory.WorkerInstanceFactory<ManagementControlWorker>
}
