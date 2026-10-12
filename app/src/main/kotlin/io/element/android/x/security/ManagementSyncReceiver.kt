/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.x.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings

class ManagementSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MANAGEMENT_SYNC_ACTION) return
        context.bindings<AppBindings>().managementControlCoordinator().synchronizeAsync()
    }

    private companion object {
        const val MANAGEMENT_SYNC_ACTION = "io.element.android.yizhe.MANAGEMENT_SYNC"
    }
}
