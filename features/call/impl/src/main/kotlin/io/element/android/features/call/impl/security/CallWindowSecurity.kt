/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun ComponentActivity.observeCallWindowSecurity(
    policyProvider: DataProtectionPolicyProvider,
    policyRevisionProvider: DataProtectionPolicyRevisionProvider,
) {
    policyRevisionProvider.policyRevision
        .onEach { applyCallWindowSecurity(policyProvider.current().policy.screenCapturePolicy) }
        .launchIn(lifecycleScope)
}

internal fun ComponentActivity.applyCallWindowSecurity(screenCapturePolicy: ScreenCapturePolicy) {
    val blockCapture = screenCapturePolicy == ScreenCapturePolicy.BLOCK
    if (blockCapture) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!blockCapture)
    }
}
