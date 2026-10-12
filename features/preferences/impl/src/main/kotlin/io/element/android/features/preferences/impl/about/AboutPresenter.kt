/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.security.dataprotection.api.AppUpdateDownloadProgress
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.PreparedAppUpdate
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
class AboutPresenter(
    private val buildMeta: BuildMeta,
    private val managementControlService: ManagementControlService,
) : Presenter<AboutState> {
    @Composable
    override fun present(): AboutState {
        val scope = rememberCoroutineScope()
        var updateCheck by remember { mutableStateOf<AsyncData<ManagedAppRelease?>>(AsyncData.Uninitialized) }
        var preparedUpdate by remember { mutableStateOf<AsyncData<PreparedAppUpdate>>(AsyncData.Uninitialized) }
        var downloadProgress by remember { mutableStateOf<AppUpdateDownloadProgress?>(null) }

        LaunchedEffect(Unit) {
            updateCheck = AsyncData.Loading()
            updateCheck = runCatching { managementControlService.latestAppRelease() }.fold(
                onSuccess = { AsyncData.Success(it) },
                onFailure = { AsyncData.Failure(it) },
            )
        }

        fun handleEvent(event: AboutEvent) {
            when (event) {
                AboutEvent.CheckForUpdate -> scope.launch {
                    updateCheck = AsyncData.Loading()
                    updateCheck = runCatching { managementControlService.latestAppRelease() }.fold(
                        onSuccess = { AsyncData.Success(it) },
                        onFailure = { AsyncData.Failure(it) },
                    )
                }
                is AboutEvent.DownloadUpdate -> {
                    if (preparedUpdate is AsyncData.Loading || preparedUpdate is AsyncData.Success) return
                    downloadProgress = AppUpdateDownloadProgress(downloadedBytes = 0L, totalBytes = -1L)
                    preparedUpdate = AsyncData.Loading()
                    scope.launch {
                        preparedUpdate = runCatching {
                            managementControlService.downloadAndVerifyAppRelease(event.release) { progress ->
                                withContext(Dispatchers.Main.immediate) {
                                    downloadProgress = progress
                                }
                            }
                        }.fold(
                            onSuccess = { AsyncData.Success(it) },
                            onFailure = { AsyncData.Failure(it) },
                        )
                        downloadProgress = null
                    }
                }
                AboutEvent.ClearUpdateError -> {
                    if (updateCheck is AsyncData.Failure) updateCheck = AsyncData.Uninitialized
                    if (preparedUpdate is AsyncData.Failure) preparedUpdate = AsyncData.Uninitialized
                    downloadProgress = null
                }
            }
        }

        return AboutState(
            elementLegals = getAllLegals().toImmutableList(),
            currentVersion = buildMeta.versionName,
            updateCheck = updateCheck,
            preparedUpdate = preparedUpdate,
            downloadProgress = downloadProgress,
            eventSink = ::handleEvent,
        )
    }
}
