/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import timber.log.Timber

internal const val EMBEDDED_CALL_WIDGET_BASE_URL = "https://appassets.androidplatform.net/element-call/index.html"
internal const val EMBEDDED_CALL_WIDGET_ORIGIN = "https://appassets.androidplatform.net"

data class CallProtectionRequest(
    val accountId: String,
    val deviceId: String,
    val roomEncrypted: Boolean,
    val widgetBaseUrl: String,
)

data class CallProtectionSession(
    val vaultId: AccountVaultId,
    val allowedOrigin: String,
    val blockScreenCapture: Boolean,
    val allowRecording: Boolean = false,
    val allowPersistentWebData: Boolean = false,
)

enum class CallProtectionDenialReason {
    RTC_E2EE_NOT_VERIFIED,
    UNENCRYPTED_ROOM,
    UNTRUSTED_WIDGET_ORIGIN,
    DEVICE_ACCESS_BLOCKED,
    DEVICE_SECURITY_VALIDATION_PENDING,
    HARDWARE_REQUIREMENT_NOT_MET,
    VAULT_LOCKED,
}

class CallProtectionDeniedException(
    val reason: CallProtectionDenialReason,
) : SecurityException("Protected call authorization was denied: ${reason.name}.")

interface CallProtectionService {
    suspend fun authorize(request: CallProtectionRequest): CallProtectionSession

    /**
     * Emits false when the session loses authorization. Next currently has no independent call-scoped
     * Vault lease: a Vault, platform or application lock invalidates authorization. App visibility alone does not.
     */
    fun observeDeviceAccess(session: CallProtectionSession): Flow<Boolean>
}

@ContributesBinding(AppScope::class)
class DefaultCallProtectionService(
    private val policyProvider: DataProtectionPolicyProvider,
    private val policyRevisionProvider: DataProtectionPolicyRevisionProvider,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val previewAuthorization: ProtectedPreviewAuthorizationGate,
) : CallProtectionService {
    override suspend fun authorize(request: CallProtectionRequest): CallProtectionSession {
        val policy = policyProvider.current().policy
        if (!policy.allowRtc) deny(CallProtectionDenialReason.RTC_E2EE_NOT_VERIFIED)
        if (!request.roomEncrypted) deny(CallProtectionDenialReason.UNENCRYPTED_ROOM)
        val allowedOrigin = trustedWidgetOrigin(request.widgetBaseUrl)
            ?: deny(CallProtectionDenialReason.UNTRUSTED_WIDGET_ORIGIN)
        validateDeviceTrust(policy.hardwareTrustRequirement)
        if (NextDeploymentConfig.isNextDeployment && !previewAuthorization.canAttemptPreview()) deny(CallProtectionDenialReason.VAULT_LOCKED)
        val vaultId = deriveAccountVaultId(request.accountId, request.deviceId)
        // Opening a call from a notification races ProcessLifecycleOwner: the call Activity can ask for the
        // protected widget just before AccountVaultLifecycleCoordinator observes that the app is visible and
        // unlocks the Vault. Wait for that existing policy-controlled unlock; never unlock it from the call path.
        val isVaultUnlocked = vaultKeyService.isUnlocked(vaultId) || withTimeoutOrNull(VAULT_UNLOCK_WAIT_TIMEOUT) {
            vaultKeyService.state(vaultId).first { it == VaultState.UNLOCKED }
            true
        } == true
        if (!isVaultUnlocked) deny(CallProtectionDenialReason.VAULT_LOCKED)
        // The lifecycle-controlled unlock may suspend while policy or device access changes.
        val currentPolicy = policyProvider.current().policy
        if (!currentPolicy.allowRtc) deny(CallProtectionDenialReason.RTC_E2EE_NOT_VERIFIED)
        validateDeviceTrust(currentPolicy.hardwareTrustRequirement)
        if (NextDeploymentConfig.isNextDeployment && !previewAuthorization.canAttemptPreview()) deny(CallProtectionDenialReason.VAULT_LOCKED)
        return CallProtectionSession(
            vaultId = vaultId,
            allowedOrigin = allowedOrigin,
            blockScreenCapture = currentPolicy.screenCapturePolicy == ScreenCapturePolicy.BLOCK,
        )
    }

    override fun observeDeviceAccess(session: CallProtectionSession): Flow<Boolean> = combine(
        policyRevisionProvider.policyRevision,
        deviceTrustService.trust(),
        if (NextDeploymentConfig.isNextDeployment) vaultKeyService.state(session.vaultId) else flowOf(VaultState.UNLOCKED),
        previewAuthorization.accessState,
    ) { _, trust, vaultState, previewAccess ->
        val policy = policyProvider.current().policy
        val trustClassAllowed = if (trust.trustState == DeviceTrustState.MANAGED) {
            trust.managedRtcAllowed()
        } else {
            (policy.hardwareTrustRequirement == HardwareTrustRequirement.NONE || trust.remotelyTrusted) &&
                trust.meets(policy.hardwareTrustRequirement)
        }
        val allowed = policy.allowRtc &&
            // Matrix session restoration may unlock its storage Vault while the device/app is
            // still locked. Key availability alone must never authorize call context recovery.
            (!NextDeploymentConfig.isNextDeployment || previewAccess.allowsPreviewAttempt) &&
            vaultState == VaultState.UNLOCKED &&
            trust.allowsLocalVaultAccess &&
            trustClassAllowed
        if (!allowed) Timber.d(
            "RTC access boundary denied: rtc=%s platform=%s app=%s previewTrust=%s vault=%s localTrust=%s class=%s",
            policy.allowRtc, previewAccess.platformUnlocked, previewAccess.applicationUnlocked,
            previewAccess.deviceTrusted, vaultState, trust.allowsLocalVaultAccess, trustClassAllowed,
        )
        allowed
    }
        .distinctUntilChanged()

    private suspend fun validateDeviceTrust(requirement: HardwareTrustRequirement) {
        val trust = deviceTrustService.current()
        if (!trust.allowsLocalVaultAccess) deny(CallProtectionDenialReason.DEVICE_ACCESS_BLOCKED)
        val managedRtcAllowed = trust.managedRtcAllowed()
        if (trust.trustState == DeviceTrustState.MANAGED && !managedRtcAllowed) {
            deny(CallProtectionDenialReason.HARDWARE_REQUIREMENT_NOT_MET)
        }
        if (requirement != HardwareTrustRequirement.NONE && !trust.remotelyTrusted && !managedRtcAllowed) {
            deny(CallProtectionDenialReason.DEVICE_SECURITY_VALIDATION_PENDING)
        }
        if (!managedRtcAllowed && !trust.meets(requirement)) {
            deny(CallProtectionDenialReason.HARDWARE_REQUIREMENT_NOT_MET)
        }
    }

    private fun DeviceTrustSnapshot.meets(requirement: HardwareTrustRequirement): Boolean = when (requirement) {
        HardwareTrustRequirement.NONE -> true
        HardwareTrustRequirement.TEE_OR_STRONGBOX ->
            hardwareSecurityLevel in setOf(HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT, HardwareSecurityLevel.STRONGBOX)
        HardwareTrustRequirement.STRONGBOX -> hardwareSecurityLevel == HardwareSecurityLevel.STRONGBOX
    }

    private fun DeviceTrustSnapshot.managedRtcAllowed(): Boolean =
        managedLicense?.takeIf { trustState == DeviceTrustState.MANAGED }
            ?.let { it.isActive(System.currentTimeMillis()) && MANAGED_RTC_SCOPE in it.allowedScopes } == true

    private fun trustedWidgetOrigin(baseUrl: String): String? {
        if (baseUrl != EMBEDDED_CALL_WIDGET_BASE_URL) return null
        val uri = runCatchingExceptions { URI(baseUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || uri.userInfo != null) return null
        return EMBEDDED_CALL_WIDGET_ORIGIN
    }

    private fun deny(reason: CallProtectionDenialReason): Nothing = throw CallProtectionDeniedException(reason)

    private companion object {
        const val MANAGED_RTC_SCOPE = "PROTECTED_RTC"
        val VAULT_UNLOCK_WAIT_TIMEOUT = 2.seconds
    }
}
