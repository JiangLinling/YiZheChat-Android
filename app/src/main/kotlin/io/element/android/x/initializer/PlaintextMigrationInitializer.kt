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

/** Blocks account UI until all registered plaintext left by older releases has been removed. */
class PlaintextMigrationInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val report = runBlocking {
            context.bindings<AppBindings>().plaintextMigrationCoordinator().migrate()
        }
        check(!report.retryRequired) {
            "Plaintext migration is incomplete: ${report.errorCode ?: "UNKNOWN"}."
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
