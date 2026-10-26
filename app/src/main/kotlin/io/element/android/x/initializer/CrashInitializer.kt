/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.features.rageshake.impl.crash.VectorUncaughtExceptionHandler
import io.element.android.features.rageshake.impl.di.RageshakeBindings
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings

class CrashInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val crashDataStore = context.bindings<RageshakeBindings>().preferencesCrashDataStore()
        val diagnostics = context.bindings<AppBindings>().secureDiagnosticLogStore()
        SecureDiagnostics.start(context, diagnostics, crashDataStore)
        VectorUncaughtExceptionHandler(
            crashDataStore,
            persistCrashDetails = false,
            diagnostics = diagnostics,
        ).activate()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
