/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.x.security

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UnifiedProtectedPreviewAuthorizationGate(
    @ApplicationContext private val context: Context,
    private val lockScreenService: LockScreenService,
    private val deviceTrustService: DeviceTrustService,
    matrixContentProtectionRuntime: MatrixContentProtectionRuntime,
    @AppCoroutineScope appCoroutineScope: CoroutineScope,
) : ProtectedPreviewAuthorizationGate {
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val platformUnlocked = MutableStateFlow(readPlatformUnlocked())
    private val mutableAuthorizationRevision = MutableStateFlow(matrixContentProtectionRuntime.authorizationRevision())
    override val authorizationRevision: StateFlow<Long> = mutableAuthorizationRevision.asStateFlow()

    override val accessState: StateFlow<ProtectedPreviewAccessState> = combine(
        platformUnlocked,
        lockScreenService.lockState,
        deviceTrustService.trust(),
    ) { isPlatformUnlocked, appLockState, trust ->
        ProtectedPreviewAccessState(
            platformUnlocked = isPlatformUnlocked,
            applicationUnlocked = appLockState == LockScreenLockState.Unlocked,
            deviceTrusted = trust.allowsLocalVaultAccess,
        )
    }.stateIn(
        scope = appCoroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = ProtectedPreviewAccessState(
            platformUnlocked = readPlatformUnlocked(),
            applicationUnlocked = lockScreenService.lockState.value == LockScreenLockState.Unlocked,
            deviceTrusted = false,
        ),
    )

    private val platformStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            platformUnlocked.value = readPlatformUnlocked()
        }
    }

    init {
        // Vault lifecycle recovery owns the refresh after its key is actually unlocked.
        // Starting a competing refresh here races that transition and retires active reads twice.
        // AppScope owns both objects for the whole process lifetime, so this registration is closed
        // together with the process. It carries no plaintext, only a monotonic generation number.
        matrixContentProtectionRuntime.registerAuthorizationRefreshListener { refresh ->
            mutableAuthorizationRevision.value = refresh.revision
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(platformStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(platformStateReceiver, filter)
        }
    }

    /**
     * This gate authorizes an in-memory decrypted projection; it does not decide how a notification
     * is redacted. Notification exposure is applied later by NotificationPrivacySanitizer. Keeping
     * the two boundaries separate prevents a HIDDEN notification policy from also hiding the same
     * authenticated message inside the room timeline and room list.
     */
    override fun canAttemptPreview(): Boolean = accessState.value.allowsPreviewAttempt

    override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean): Boolean =
        contentProtectionAuthorized && canAttemptPreview()

    private fun readPlatformUnlocked(): Boolean =
        powerManager?.isInteractive == true && keyguardManager?.isDeviceLocked == false
}
