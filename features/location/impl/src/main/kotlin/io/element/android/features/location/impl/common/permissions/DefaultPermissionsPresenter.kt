/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.common.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.location.impl.common.actions.LocationActions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@Suppress("unused")
@AssistedInject
class DefaultPermissionsPresenter(
    @Assisted private val permissions: List<String>,
    private val permissionStateProvider: ComposableMultiplePermissionsStateProvider,
    private val locationActions: LocationActions,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : PermissionsPresenter {
    @AssistedFactory
    @ContributesBinding(AppScope::class)
    interface Factory : PermissionsPresenter.Factory {
        override fun create(permissions: List<String>): DefaultPermissionsPresenter
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    override fun present(): PermissionsState {
        val lifecycleOwner = LocalLifecycleOwner.current
        var requestOperationId by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsOperationId by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsWasBackgrounded by rememberSaveable { mutableStateOf(false) }

        val multiplePermissionsState = permissionStateProvider.provide(permissions) {
            val operationId = requestOperationId ?: return@provide
            requestOperationId = null
            appCoroutineScope.launch {
                try {
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission", operationId = operationId)
                    ) { Unit }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Timber.w(failure, "Unable to receipt location permission result")
                }
            }
        }

        DisposableEffect(lifecycleOwner, settingsOperationId) {
            if (settingsOperationId != null && !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                settingsWasBackgrounded = true
            }
            val observer = LifecycleEventObserver { _, event ->
                val operationId = settingsOperationId
                if (operationId != null && (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)) {
                    settingsWasBackgrounded = true
                }
                if (operationId != null && event == Lifecycle.Event.ON_RESUME && settingsWasBackgrounded) {
                    settingsOperationId = null
                    settingsWasBackgrounded = false
                    appCoroutineScope.launch {
                        try {
                            appOperationalStateCoordinator.perform(
                                lifecycleOperationRequest(FullAppLifecycleId.LC_03, "settings_return", operationId = operationId)
                            ) { Unit }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            Timber.w(failure, "Unable to receipt location permission settings return")
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        fun handleEvent(event: PermissionsEvents) {
            when (event) {
                PermissionsEvents.RequestPermissions -> {
                    if (requestOperationId == null && settingsOperationId == null) {
                        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission")
                        requestOperationId = request.operationId
                        appCoroutineScope.launch {
                            try {
                                appOperationalStateCoordinator.start(request)
                                multiplePermissionsState.launchMultiplePermissionRequest()
                            } catch (failure: Exception) {
                                requestOperationId = null
                                if (failure is CancellationException) throw failure
                                Timber.w(failure, "Unable to start location permission request")
                            }
                        }
                    }
                }
                PermissionsEvents.OpenAppSettings -> {
                    if (requestOperationId == null && settingsOperationId == null) {
                        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "settings_return")
                        settingsWasBackgrounded = false
                        settingsOperationId = request.operationId
                        appCoroutineScope.launch {
                            try {
                                appOperationalStateCoordinator.start(request)
                                locationActions.openAppSettings()
                            } catch (failure: Exception) {
                                settingsOperationId = null
                                if (failure is CancellationException) throw failure
                                Timber.w(failure, "Unable to start location permission settings return")
                            }
                        }
                    }
                }
            }
        }

        return PermissionsState(
            permissions = when {
                multiplePermissionsState.allPermissionsGranted -> PermissionsState.Permissions.AllGranted
                multiplePermissionsState.permissions.any { it.status.isGranted } -> PermissionsState.Permissions.SomeGranted
                else -> PermissionsState.Permissions.NoneGranted
            },
            shouldShowRationale = multiplePermissionsState.shouldShowRationale,
            eventSink = ::handleEvent,
        )
    }
}
