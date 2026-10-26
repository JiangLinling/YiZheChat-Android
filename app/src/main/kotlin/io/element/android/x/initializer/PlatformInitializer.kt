/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import android.system.Os
import androidx.startup.Initializer
import io.element.android.features.rageshake.impl.di.RageshakeBindings
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.matrix.api.tracing.LogLevel
import io.element.android.libraries.matrix.api.tracing.TracingConfiguration
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PlatformInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appBindings = context.bindings<AppBindings>()
        SecureDiagnostics.start(context, appBindings.secureDiagnosticLogStore(), context.bindings<RageshakeBindings>().preferencesCrashDataStore())
        val platformService = appBindings.platformService()
        val bugReporter = appBindings.bugReporter()
        val preferencesStore = appBindings.preferencesStore()
        val tracingConfiguration = TracingConfiguration(
            writesToLogcat = true,
            writesToFilesConfiguration = WriteToFilesConfiguration.Disabled,
            logLevel = LogLevel.TRACE,
            // App Timber is collected directly before any SDK filter; never echo raw app messages into logcat.
            extraTargets = emptyList(),
            traceLogPacks = runBlocking { preferencesStore.getTracingLogPacksFlow().first() },
            sdkSentryDsn = appBindings.sentrySdkDsn()?.value?.takeIf { it.isNotBlank() },
        )
        bugReporter.setCurrentTracingLogLevel(tracingConfiguration.logLevel.name)
        // Also set env variable for rust back trace
        Os.setenv("RUST_BACKTRACE", "1", true)
        platformService.init(tracingConfiguration)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(CrashInitializer::class.java)
}
