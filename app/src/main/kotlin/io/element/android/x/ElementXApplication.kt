/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.compose.material3.ComposeMaterial3Flags.isAnchoredDraggableComponentsStrictOffsetCheckEnabled
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.work.Configuration
import dev.zacsweers.metro.createGraphFactory
import io.element.android.libraries.androidutils.startup.StartupTrace
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.pushproviders.unifiedpush.embedded.EmbeddedNtfyConnectionService
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.x.di.AppGraph
import io.element.android.x.info.logApplicationInfo
import io.element.android.x.initializer.AccountVaultInitializer
import io.element.android.x.initializer.CacheCleanerInitializer
import io.element.android.x.initializer.CrashInitializer
import io.element.android.x.initializer.DeletionRecoveryInitializer
import io.element.android.x.initializer.DeviceRootKeyInitializer
import io.element.android.x.initializer.DeviceTrustInitializer
import io.element.android.x.initializer.ManagementControlInitializer
import io.element.android.x.initializer.PlaintextMigrationInitializer
import io.element.android.x.initializer.PlatformInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class ElementXApplication : Application(), DependencyInjectionGraphOwner, Configuration.Provider {
    sealed interface StartupState {
        data object Initializing : StartupState
        data object Ready : StartupState
        data class Failed(val reason: String) : StartupState
    }

    override val graph: AppGraph = createGraphFactory<AppGraph.Factory>().create(this)

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _startupState = MutableStateFlow<StartupState>(StartupState.Initializing)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(MetroWorkerFactory(graph.workerProviders))
        .build()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate() {
        StartupTrace.mark("Application.onCreate")
        super.onCreate()

        // These initializers may perform Keystore, SQLCipher, migration, and disk work.
        // Keep the main thread available for the real Boot UI while preserving their order.
        startupScope.launch {
            runCatching {
                StartupTrace.measure("Application.initializers") {
                    initializeComponents()
                }
                logApplicationInfo(this@ElementXApplication)
            }.onSuccess {
                // Package-replaced and boot broadcasts can arrive before encrypted push state is
                // available. Retry after application initialization so an existing registration
                // always restores its persistent connection without requiring re-registration.
                // The built-in ntfy channel remains the primary path on every device. A vendor
                // provider is only a server-coordinated fallback when this process cannot claim
                // an event (for example, when HarmonyOS freezes the Android compatibility
                // process). Starting both does not create two Matrix pushers: the Huawei
                // registration binds its token to this same coordinated ntfy registration.
                EmbeddedNtfyConnectionService.start(this@ElementXApplication)
                startVendorPushIfSupported()
                StartupTrace.mark("Application.ready")
                _startupState.value = StartupState.Ready
                startupScope.launch(Dispatchers.IO) {
                    try {
                        graph.retainedSessionPreparation().prepare()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Timber.w("Background session preparation unavailable: %s", failure.javaClass.simpleName)
                    }
                }
            }.onFailure { error ->
                StartupTrace.mark("Application.failed ${error::class.simpleName}")
                Timber.e(error, "Application startup initialization failed")
                _startupState.value = StartupState.Failed(error.message ?: error::class.simpleName.orEmpty())
            }
        }

        // Disable the strict offset check for anchored draggable components, as it can cause issues with bottom sheets.
        // Remove once https://issuetracker.google.com/issues/477038695 is fixed.
        isAnchoredDraggableComponentsStrictOffsetCheckEnabled = false
    }

    private fun startVendorPushIfSupported(): Boolean {
        val provider = graph.vendorPushProviders
            .asSequence()
            .filter { it.isAvailable() }
            .sortedByDescending { it.priority }
            .firstOrNull()
            ?: return false
        provider.start()
        return true
    }

    private fun initializeComponents() {
        // AndroidX Startup serializes initializeComponent() calls behind a process-wide lock.
        // These components are explicitly ordered here, so invoking create() directly prevents
        // a sticky Android service from blocking the main thread on that lock during cold start.
        StartupTrace.measure("initializer.DeviceRootKey") {
            DeviceRootKeyInitializer().create(this)
        }
        StartupTrace.measure("initializer.DeviceTrust") {
            DeviceTrustInitializer().create(this)
        }
        StartupTrace.measure("initializer.PlaintextMigration") {
            PlaintextMigrationInitializer().create(this)
        }
        StartupTrace.measure("initializer.Crash") {
            CrashInitializer().create(this)
        }
        StartupTrace.measure("initializer.Platform") {
            PlatformInitializer().create(this)
        }
        StartupTrace.measure("initializer.DeletionRecovery") {
            DeletionRecoveryInitializer().create(this)
        }
        StartupTrace.measure("initializer.ManagementControl") {
            ManagementControlInitializer().create(this)
        }
        StartupTrace.measure("initializer.AccountVault") {
            AccountVaultInitializer().create(this)
        }
        StartupTrace.measure("initializer.CacheCleaner") {
            CacheCleanerInitializer().create(this)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            graph.accountVaultLifecycleCoordinator().onMemoryPressure()
        }
    }
}
