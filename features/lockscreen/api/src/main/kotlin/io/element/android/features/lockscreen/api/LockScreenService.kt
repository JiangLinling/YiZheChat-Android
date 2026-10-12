/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.api

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

interface LockScreenService {
    /**
     * The current lock state of the app.
     */
    val lockState: StateFlow<LockScreenLockState>

    /** Actual unlocked-to-locked transitions caused by the configured background grace period. */
    val backgroundLockTimeouts: Flow<Unit> get() = emptyFlow()

    /**
     * Check if setting up the lock screen is required.
     * @return true if the lock screen is mandatory and not setup yet, false otherwise.
     */
    fun isSetupRequired(): Flow<Boolean>

    /**
     * Check if pin is setup.
     * @return true if the pin is setup, false otherwise.
     */
    fun isPinSetup(): Flow<Boolean>
}

/**
 * Keeps screen-capture protection aligned with the local PIN and the active data-protection policy.
 * @param activity the activity to set the flag on.
 * @param policyProvider supplies the current centrally managed screen-capture policy.
 * @param policyRevision emits when the managed policy must be re-evaluated.
 */
fun LockScreenService.handleSecureFlag(
    activity: ComponentActivity,
    policyProvider: DataProtectionPolicyProvider,
    policyRevision: Flow<Long>,
    localContentHidden: Flow<Boolean> = flowOf(false),
) {
    combine(isPinSetup(), policyRevision, localContentHidden) { _, _, hidden -> hidden }
        .onEach { hidden -> applyScreenCaptureProtection(activity, policyProvider, hidden) }
        .launchIn(activity.lifecycleScope)
}

/** Local authentication protection applies even when capture of legitimately visible content is allowed. */
fun shouldHideProtectedContent(pinConfigured: Boolean?, isResumed: Boolean, lockState: LockScreenLockState): Boolean =
    lockState == LockScreenLockState.Locked || (pinConfigured != false && !isResumed)

/** Apply synchronously before Android can snapshot or draw the returning protected window. */
fun applyScreenCaptureProtection(
    activity: ComponentActivity,
    policyProvider: DataProtectionPolicyProvider,
    localContentHidden: Boolean,
) {
    val blockCapture = localContentHidden || policyProvider.current().policy.screenCapturePolicy == ScreenCapturePolicy.BLOCK
    if (blockCapture) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.setRecentsScreenshotEnabled(!blockCapture)
    }
}
