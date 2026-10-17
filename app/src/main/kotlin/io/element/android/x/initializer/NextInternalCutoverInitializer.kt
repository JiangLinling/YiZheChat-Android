/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.runBlocking

/** Runs before management sync, push restoration, or account UI can use an old test session. */
class NextInternalCutoverInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        if (context.packageName != INTERNAL_PACKAGE || !NextDeploymentConfig.isNextDeployment) return
        runBlocking {
            context.bindings<AppBindings>().nextInternalCutoverMigration()
                .migrate(NextDeploymentConfig.matrixHomeserverUrl)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(DeletionRecoveryInitializer::class.java)

    private companion object {
        const val INTERNAL_PACKAGE = "com.yizhe.chat.internal"
    }
}
