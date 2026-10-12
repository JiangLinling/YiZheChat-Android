/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.security

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.security.CallProtectionDenialReason
import io.element.android.features.call.impl.security.CallProtectionDeniedException
import io.element.android.features.call.impl.security.CallProtectionRequest
import io.element.android.features.call.impl.security.DefaultCallProtectionService
import io.element.android.features.call.impl.security.EMBEDDED_CALL_WIDGET_BASE_URL
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.security.dataprotection.api.DataProtectionPolicyRevisionProvider
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import io.element.android.security.securestorage.test.FakeVaultKeyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultCallProtectionServiceTest {
    @Test
    fun `Next rejects admission when platform or PIN is locked despite available Vault keys`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val fixture = createFixture()
        for (access in listOf(ProtectedPreviewAccessState(false, true, true), ProtectedPreviewAccessState(true, false, true))) {
            fixture.previewAccess.value = access
            assertThat(fixture.vaultKeyService.isUnlocked(fixture.vaultId)).isTrue()
            val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()
            assertThat((failure as CallProtectionDeniedException).reason).isEqualTo(CallProtectionDenialReason.VAULT_LOCKED)
        }
    }

    @Test
    fun `Next session restoration cannot regain access until both system and application unlock`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())
        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.previewAccess.value = ProtectedPreviewAccessState(false, false, true)
            assertThat(awaitItem()).isFalse()
            fixture.vaultKeyService.lock(fixture.vaultId)
            check(fixture.vaultKeyService.unlock(fixture.vaultId))
            expectNoEvents()
            fixture.previewAccess.value = ProtectedPreviewAccessState(true, false, true)
            expectNoEvents()
            fixture.previewAccess.value = ProtectedPreviewAccessState(true, true, true)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `authorize rejects RTC while verified media E2EE is disabled by policy`() = runTest {
        val fixture = createFixture(allowRtc = false)

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CallProtectionDeniedException::class.java)
        assertThat((failure as CallProtectionDeniedException).reason).isEqualTo(CallProtectionDenialReason.RTC_E2EE_NOT_VERIFIED)
    }

    @Test
    fun `authorize permits encrypted bundled call with an unlocked Vault`() = runTest {
        val fixture = createFixture()

        val session = fixture.service.authorize(aRequest())

        assertThat(session.vaultId).isEqualTo(fixture.vaultId)
        assertThat(session.allowedOrigin).isEqualTo("https://appassets.androidplatform.net")
        assertThat(session.allowRecording).isFalse()
        assertThat(session.allowPersistentWebData).isFalse()
        assertThat(session.blockScreenCapture).isTrue()
    }

    @Test
    fun `managed device may call under hardware policy only with RTC scope`() = runTest {
        val fixture = createFixture(hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX)
        fixture.deviceTrustService.set(managedSnapshot(setOf("PROTECTED_MESSAGES", "PROTECTED_MEDIA", "PROTECTED_RTC")))

        val session = fixture.service.authorize(aRequest())

        assertThat(session.vaultId).isEqualTo(fixture.vaultId)
    }

    @Test
    fun `managed device without RTC scope cannot call even when hardware policy is none`() = runTest {
        val fixture = createFixture(hardwareTrustRequirement = HardwareTrustRequirement.NONE)
        fixture.deviceTrustService.set(managedSnapshot(setOf("PROTECTED_MESSAGES", "PROTECTED_MEDIA")))

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason)
            .isEqualTo(CallProtectionDenialReason.HARDWARE_REQUIREMENT_NOT_MET)
    }

    @Test
    fun `authorize waits for the lifecycle controlled Vault unlock`() = runTest {
        val fixture = createFixture(unlockVault = false)
        backgroundScope.launch {
            delay(500)
            check(fixture.vaultKeyService.unlock(fixture.vaultId))
        }

        val session = fixture.service.authorize(aRequest())

        assertThat(session.vaultId).isEqualTo(fixture.vaultId)
    }

    @Test
    fun `authorize rejects RTC disabled while waiting for Vault unlock`() = runTest {
        val fixture = createFixture(unlockVault = false)
        backgroundScope.launch {
            delay(500)
            fixture.policyProvider.allowRtc = false
            fixture.policyRevision.value += 1
            check(fixture.vaultKeyService.unlock(fixture.vaultId))
        }

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason)
            .isEqualTo(CallProtectionDenialReason.RTC_E2EE_NOT_VERIFIED)
    }

    @Test
    fun `authorize rejects device revoked while waiting for Vault unlock`() = runTest {
        val fixture = createFixture(unlockVault = false)
        backgroundScope.launch {
            delay(500)
            fixture.deviceTrustService.applyRemoteRestriction(DeviceTrustState.REVOKED, verifiedAtEpochMillis = 1)
            check(fixture.vaultKeyService.unlock(fixture.vaultId))
        }

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason)
            .isEqualTo(CallProtectionDenialReason.DEVICE_ACCESS_BLOCKED)
    }

    @Test
    fun `authorize rejects managed RTC scope removed while waiting for Vault unlock`() = runTest {
        val fixture = createFixture(unlockVault = false)
        fixture.deviceTrustService.set(managedSnapshot(setOf("PROTECTED_MESSAGES", "PROTECTED_MEDIA", "PROTECTED_RTC")))
        backgroundScope.launch {
            delay(500)
            fixture.deviceTrustService.set(managedSnapshot(setOf("PROTECTED_MESSAGES", "PROTECTED_MEDIA")))
            check(fixture.vaultKeyService.unlock(fixture.vaultId))
        }

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason)
            .isEqualTo(CallProtectionDenialReason.HARDWARE_REQUIREMENT_NOT_MET)
    }

    @Test
    fun `authorize rejects a Vault that remains locked`() = runTest {
        val fixture = createFixture(unlockVault = false)

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CallProtectionDeniedException::class.java)
        assertThat((failure as CallProtectionDeniedException).reason).isEqualTo(CallProtectionDenialReason.VAULT_LOCKED)
    }

    @Test
    fun `authorize rejects an unencrypted room without falling back`() = runTest {
        val fixture = createFixture()

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest(roomEncrypted = false)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CallProtectionDeniedException::class.java)
        assertThat((failure as CallProtectionDeniedException).reason).isEqualTo(CallProtectionDenialReason.UNENCRYPTED_ROOM)
    }

    @Test
    fun `authorize rejects a developer supplied call origin`() = runTest {
        val fixture = createFixture()

        val failure = runCatchingExceptions {
            fixture.service.authorize(aRequest(widgetBaseUrl = "https://call.example.org"))
        }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason).isEqualTo(CallProtectionDenialReason.UNTRUSTED_WIDGET_ORIGIN)
    }

    @Test
    fun `authorize reports pending device validation separately from hardware capability`() = runTest {
        val fixture = createFixture(hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX)
        fixture.deviceTrustService.set(
            DeviceTrustSnapshot(
                installationId = "pending-installation",
                hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                trustState = DeviceTrustState.PENDING,
                attestationVersion = 1,
                lastVerifiedAtEpochMillis = null,
            )
        )

        val failure = runCatchingExceptions { fixture.service.authorize(aRequest()) }.exceptionOrNull()

        assertThat((failure as CallProtectionDeniedException).reason)
            .isEqualTo(CallProtectionDenialReason.DEVICE_SECURITY_VALIDATION_PENDING)
    }

    @Test
    fun `active session becomes invalid when the device is remotely revoked`() = runTest {
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())

        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.deviceTrustService.applyRemoteRestriction(DeviceTrustState.REVOKED, verifiedAtEpochMillis = 1)
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Next active session loses access when its account Vault locks`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())

        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.vaultKeyService.lock(fixture.vaultId)
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `active session remains valid after an unrelated policy synchronization`() = runTest {
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())

        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.policyRevision.value += 1
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `active session becomes invalid when RTC is explicitly disabled by policy`() = runTest {
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())

        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.policyProvider.allowRtc = false
            fixture.policyRevision.value += 1
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `active session becomes invalid when a stricter hardware policy is no longer met`() = runTest {
        val fixture = createFixture()
        val session = fixture.service.authorize(aRequest())

        fixture.service.observeDeviceAccess(session).test {
            assertThat(awaitItem()).isTrue()
            fixture.policyProvider.hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX
            fixture.policyRevision.value += 1
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun createFixture(
        allowRtc: Boolean = true,
        unlockVault: Boolean = true,
        hardwareTrustRequirement: HardwareTrustRequirement = HardwareTrustRequirement.NONE,
    ): Fixture {
        val deviceTrustService = FakeDeviceTrustService()
        val vaultKeyService = FakeVaultKeyService()
        val vaultId = deriveAccountVaultId(A_SESSION_ID.value, A_DEVICE_ID.value)
        vaultKeyService.provision(
            VaultProvisioningRequest(
                vaultId = vaultId,
                accountIdHash = accountIdHash(A_SESSION_ID.value),
                requireUserAuthentication = false,
                requireTrustedHardware = false,
            )
        )
        if (unlockVault) check(vaultKeyService.unlock(vaultId))
        val policyProvider = FakeDataProtectionPolicyProvider(
            allowRtc = allowRtc,
            hardwareTrustRequirement = hardwareTrustRequirement,
        )
        val policyRevision = MutableStateFlow(0L)
        val previewAccess = MutableStateFlow(ProtectedPreviewAccessState(true, true, true))
        val previewAuthorization = object : ProtectedPreviewAuthorizationGate {
            override val accessState = previewAccess
            override val authorizationRevision = MutableStateFlow(0L)
            override fun canAttemptPreview() = accessState.value.allowsPreviewAttempt
            override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean) = contentProtectionAuthorized && canAttemptPreview()
        }
        return Fixture(
            service = DefaultCallProtectionService(
                policyProvider = policyProvider,
                policyRevisionProvider = object : DataProtectionPolicyRevisionProvider {
                    override val policyRevision = policyRevision
                },
                deviceTrustService = deviceTrustService,
                vaultKeyService = vaultKeyService,
                previewAuthorization = previewAuthorization,
            ),
            policyProvider = policyProvider,
            policyRevision = policyRevision,
            deviceTrustService = deviceTrustService,
            vaultKeyService = vaultKeyService,
            vaultId = vaultId,
            previewAccess = previewAccess,
        )
    }

    private fun aRequest(
        roomEncrypted: Boolean = true,
        widgetBaseUrl: String = EMBEDDED_CALL_WIDGET_BASE_URL,
    ) = CallProtectionRequest(
        accountId = A_SESSION_ID.value,
        deviceId = A_DEVICE_ID.value,
        roomEncrypted = roomEncrypted,
        widgetBaseUrl = widgetBaseUrl,
    )

    private fun managedSnapshot(scopes: Set<String>): DeviceTrustSnapshot {
        val now = System.currentTimeMillis()
        return DeviceTrustSnapshot(
            installationId = "managed-installation",
            hardwareSecurityLevel = HardwareSecurityLevel.SOFTWARE,
            trustState = DeviceTrustState.MANAGED,
            attestationVersion = 1,
            lastVerifiedAtEpochMillis = now,
            managedLicense = ManagedDeviceLicense(
                licenseId = "managed-license",
                installationId = "managed-installation",
                devicePublicKeySha256 = "ab".repeat(32),
                contentShareKeyId = "managed-share-key",
                appPackageName = "com.yizhe.chat.debug",
                appVersionCode = 1,
                revision = 1,
                allowedScopes = scopes,
                offlineAllowed = false,
                issuedAtEpochMillis = now,
                expiresAtEpochMillis = now + 60_000,
            ),
        )
    }

    private data class Fixture(
        val service: DefaultCallProtectionService,
        val policyProvider: FakeDataProtectionPolicyProvider,
        val policyRevision: MutableStateFlow<Long>,
        val deviceTrustService: FakeDeviceTrustService,
        val vaultKeyService: FakeVaultKeyService,
        val vaultId: io.element.android.security.securestorage.api.AccountVaultId,
        val previewAccess: MutableStateFlow<ProtectedPreviewAccessState>,
    )
}
