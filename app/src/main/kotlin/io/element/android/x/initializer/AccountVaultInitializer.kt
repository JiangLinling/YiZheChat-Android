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

/** Reconciles durable account Vault records only after pending destructive operations have recovered. */
class AccountVaultInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Lifecycle observer registration must happen on the Android main thread. Schedule it without
        // blocking startup; the coordinator keeps Vault reconciliation work on the application scope.
        context.bindings<AppBindings>().accountVaultLifecycleCoordinator().startAsync()
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
            context.bindings<AppBindings>().callRecoveryService().start()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(
        DeletionRecoveryInitializer::class.java,
        DeviceTrustInitializer::class.java,
    )
}
