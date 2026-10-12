/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.api

import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LockScreenSecureFlagTest {
    @Test
    fun `PIN background and locked return hide content but unlocked foreground retains ALLOW`() {
        assertThat(shouldHideProtectedContent(true, false, LockScreenLockState.Unlocked)).isTrue()
        assertThat(shouldHideProtectedContent(true, true, LockScreenLockState.Locked)).isTrue()
        assertThat(shouldHideProtectedContent(true, true, LockScreenLockState.Unlocked)).isFalse()
    }

    @Test
    fun `confirmed no PIN keeps unlocked background unmodified while unknown discovery fails closed`() {
        assertThat(shouldHideProtectedContent(false, false, LockScreenLockState.Unlocked)).isFalse()
        assertThat(shouldHideProtectedContent(null, false, LockScreenLockState.Unlocked)).isTrue()
        assertThat(shouldHideProtectedContent(false, true, LockScreenLockState.Locked)).isTrue()
    }

    @Test
    fun `local protection blocks ALLOW immediately and clearing it restores current capture policy`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val policy = MutablePolicyProvider(ScreenCapturePolicy.ALLOW)
        val revision = MutableStateFlow(0L)
        val hidden = MutableStateFlow(false)
        FakeLockScreenService(isPinSetup = true).handleSecureFlag(activity, policy, revision, hidden)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.hasSecureFlag()).isFalse()
        hidden.value = true
        applyScreenCaptureProtection(activity, policy, true)
        assertThat(activity.hasSecureFlag()).isTrue() // before lifecycle/Flow dispatch
        shadowOf(Looper.getMainLooper()).idle()
        policy.screenCapturePolicy = ScreenCapturePolicy.BLOCK
        revision.value++
        hidden.value = false
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.hasSecureFlag()).isTrue() // a local unlock cannot override the signed BLOCK
        policy.screenCapturePolicy = ScreenCapturePolicy.ALLOW
        revision.value++
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.hasSecureFlag()).isFalse()
    }

    @Test
    fun `policy ALLOW revision cannot reveal a still protected local window`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val policy = MutablePolicyProvider(ScreenCapturePolicy.BLOCK)
        val revision = MutableStateFlow(0L)
        val hidden = MutableStateFlow(true)
        FakeLockScreenService(isPinSetup = true).handleSecureFlag(activity, policy, revision, hidden)
        shadowOf(Looper.getMainLooper()).idle()
        policy.screenCapturePolicy = ScreenCapturePolicy.ALLOW
        revision.value++
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.hasSecureFlag()).isTrue()
    }

    @Test
    fun `ALLOW clears secure flag even when app pin is configured`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val policyProvider = MutablePolicyProvider(ScreenCapturePolicy.ALLOW)
        val revision = MutableStateFlow(0L)

        FakeLockScreenService(isPinSetup = true).handleSecureFlag(activity, policyProvider, revision)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.hasSecureFlag()).isFalse()
    }

    @Test
    fun `live policy revision applies BLOCK and ALLOW without recreating activity`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val policyProvider = MutablePolicyProvider(ScreenCapturePolicy.BLOCK)
        val revision = MutableStateFlow(0L)
        FakeLockScreenService(isPinSetup = true).handleSecureFlag(activity, policyProvider, revision)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(activity.hasSecureFlag()).isTrue()

        policyProvider.screenCapturePolicy = ScreenCapturePolicy.ALLOW
        revision.value++
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.hasSecureFlag()).isFalse()
    }
}

private fun ComponentActivity.hasSecureFlag(): Boolean =
    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

private class FakeLockScreenService(isPinSetup: Boolean) : LockScreenService {
    override val lockState: StateFlow<LockScreenLockState> = MutableStateFlow(LockScreenLockState.Unlocked)
    private val pinSetup = MutableStateFlow(isPinSetup)

    override fun isSetupRequired(): Flow<Boolean> = MutableStateFlow(false)

    override fun isPinSetup(): Flow<Boolean> = pinSetup
}

private class MutablePolicyProvider(
    var screenCapturePolicy: ScreenCapturePolicy,
) : DataProtectionPolicyProvider {
    override fun current(): ActiveDataProtectionPolicy = ActiveDataProtectionPolicy(
        policy = DataProtectionPolicyV1(
            policyId = "screen-capture-test",
            version = 1,
            issuedAtEpochMillis = 0,
            effectiveAtEpochMillis = 0,
            expiresAtEpochMillis = null,
            contentRetentionMillis = 86_400_000,
            maximumOfflineMillis = 3_600_000,
            offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
            notificationExposure = NotificationExposure.HIDDEN,
            hardwareTrustRequirement = HardwareTrustRequirement.NONE,
            allowContentExport = false,
            allowClipboard = false,
            allowExternalShare = false,
            screenCapturePolicy = screenCapturePolicy,
        ),
        source = DataProtectionPolicySource.VERIFIED_REMOTE,
    )
}
