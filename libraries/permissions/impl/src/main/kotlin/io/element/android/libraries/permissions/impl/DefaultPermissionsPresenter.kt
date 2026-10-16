/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.permissions.impl

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.shouldShowRationale
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.permissions.api.PermissionsState
import io.element.android.libraries.permissions.api.PermissionsStore
import io.element.android.libraries.permissions.impl.action.PermissionActions
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

private val loggerTag = LoggerTag("DefaultPermissionsPresenter")

@AssistedInject
class DefaultPermissionsPresenter(
    @Assisted val permission: String,
    private val permissionsStore: PermissionsStore,
    private val composablePermissionStateProvider: ComposablePermissionStateProvider,
    private val permissionActions: PermissionActions,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : PermissionsPresenter {
    @AssistedFactory
    @ContributesBinding(AppScope::class)
    interface Factory : PermissionsPresenter.Factory {
        override fun create(permission: String): DefaultPermissionsPresenter
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @SuppressLint("InlinedApi")
    @Composable
    override fun present(): PermissionsState {
        val lifecycleOwner = LocalLifecycleOwner.current
        var settingsOperationId by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsWasBackgrounded by rememberSaveable { mutableStateOf(false) }
        val permissionRequestOperationId = remember { AtomicReference<String?>(null) }

        // To reset the store: ResetStore()

        val isAlreadyDenied: Boolean by remember {
            permissionsStore.isPermissionDenied(permission)
        }.collectAsState(initial = false)

        val isAlreadyAsked: Boolean by remember {
            permissionsStore.isPermissionAsked(permission)
        }.collectAsState(initial = false)

        val showDialog = rememberSaveable { mutableStateOf(false) }
        var permissionState: PermissionState? = null

        fun onPermissionResult(result: Boolean) {
            Timber.tag(loggerTag.value).d("onPermissionResult: $result")
            // A false rationale also occurs when Android silently denies a request.
            // Explain every denial immediately; history must never stand in for an OS grant.
            showDialog.value = !result
            appCoroutineScope.launch {
                val operationId = permissionRequestOperationId.getAndSet(null)
                var persisted = false
                try {
                    appOperationalStateCoordinator.perform(
                        lifecycleOperationRequest(
                            lifecycleId = FullAppLifecycleId.LC_03,
                            trigger = if (operationId == null) "permission_result" else "feature_requires_permission",
                            operationId = operationId ?: java.util.UUID.randomUUID().toString(),
                        )
                    ) {
                        permissionsStore.setPermissionAsked(permission, true)
                        permissionsStore.setPermissionDenied(permission, !result)
                        persisted = true
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Timber.tag(loggerTag.value).w(failure, "Unable to bind permission result lifecycle")
                    if (!persisted) {
                        permissionsStore.setPermissionAsked(permission, true)
                        permissionsStore.setPermissionDenied(permission, !result)
                    }
                }
            }
        }

        DisposableEffect(lifecycleOwner, settingsOperationId) {
            // Observer registration synchronizes to the current state, including ON_RESUME.
            // Only a real departure followed by return completes the settings operation.
            if (settingsOperationId != null && !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                settingsWasBackgrounded = true
            }
            val observer = LifecycleEventObserver { _, event ->
                val operationId = settingsOperationId
                if (operationId != null && (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)) {
                    settingsWasBackgrounded = true
                }
                if (event == Lifecycle.Event.ON_RESUME && operationId != null && settingsWasBackgrounded) {
                    settingsOperationId = null
                    settingsWasBackgrounded = false
                    showDialog.value = permissionState?.status?.isGranted != true
                    appCoroutineScope.launch {
                        runCatching {
                            appOperationalStateCoordinator.perform(
                                lifecycleOperationRequest(
                                    lifecycleId = FullAppLifecycleId.LC_03,
                                    trigger = "settings_return",
                                    operationId = operationId,
                                )
                            ) { Unit }
                        }.onFailure { failure ->
                            Timber.tag(loggerTag.value).w(failure, "Unable to complete settings return lifecycle")
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        permissionState = composablePermissionStateProvider.provide(
            permission = permission,
            onPermissionResult = ::onPermissionResult
        )

        LaunchedEffect(this) {
            if (permissionState.status.isGranted) {
                // User may have granted permission from the settings, so reset the store regarding this permission
                permissionsStore.resetPermission(permission)
            }
        }

        LaunchedEffect(permissionState.status.isGranted) {
            if (permissionState.status.isGranted) showDialog.value = false
        }

        fun handleEvent(event: PermissionsEvent) {
            when (event) {
                PermissionsEvent.CloseDialog -> {
                    showDialog.value = false
                }
                PermissionsEvent.RequestPermissions -> {
                    if (permissionState.status !is PermissionStatus.Granted &&
                        (isAlreadyDenied || (isAlreadyAsked && !permissionState.status.shouldShowRationale))
                    ) {
                        showDialog.value = true
                    } else {
                        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission")
                        permissionRequestOperationId.set(request.operationId)
                        appCoroutineScope.launch {
                            runCatching { appOperationalStateCoordinator.start(request) }
                                .onFailure { Timber.tag(loggerTag.value).w(it, "Unable to start permission lifecycle") }
                        }
                        permissionState.launchPermissionRequest()
                    }
                }
                PermissionsEvent.OpenSystemSettingAndCloseDialog, is PermissionsEvent.OpenSystemSettingsUsingActivity -> {
                    showDialog.value = false
                    if (settingsOperationId != null) return
                    val request = lifecycleOperationRequest(FullAppLifecycleId.LC_03, "settings_return")
                    settingsWasBackgrounded = false
                    settingsOperationId = request.operationId
                    appCoroutineScope.launch {
                        try {
                            appOperationalStateCoordinator.start(request)
                            if (event is PermissionsEvent.OpenSystemSettingsUsingActivity) {
                                event.launchSettings()
                            } else {
                                permissionActions.openSettings(permission)
                            }
                        } catch (failure: Exception) {
                            settingsOperationId = null
                            settingsWasBackgrounded = false
                            if (failure is CancellationException) throw failure
                            Timber.tag(loggerTag.value).w(failure, "Unable to open permission settings with lifecycle identity")
                        }
                    }
                }
            }
        }

        return PermissionsState(
            permission = permissionState.permission,
            permissionGranted = permissionState.status.isGranted,
            shouldShowRationale = permissionState.status.shouldShowRationale,
            showDialog = showDialog.value,
            permissionAlreadyAsked = isAlreadyAsked,
            permissionAlreadyDenied = isAlreadyDenied,
            eventSink = ::handleEvent,
        )
    }

    /*
    @Composable
    private fun ResetStore() {
        LaunchedEffect(this@DefaultPermissionsPresenter) {
            launch {
                permissionsStore.resetStore()
            }
        }
    }
     */
}
