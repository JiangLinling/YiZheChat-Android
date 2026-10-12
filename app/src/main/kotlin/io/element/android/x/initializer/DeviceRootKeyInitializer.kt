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

/** Creates or validates the installation-bound, non-exportable device root key before account data is opened. */
class DeviceRootKeyInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        runBlocking {
            context.bindings<AppBindings>().deviceRootKeyService().ensureReady()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
