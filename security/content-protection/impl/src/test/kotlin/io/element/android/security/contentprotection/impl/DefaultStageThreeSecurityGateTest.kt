/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.DeviceRecipientRegistrySnapshot
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsRequestAuthorization
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentInvalidation
import io.element.android.security.contentprotection.api.MatrixContentProtectionBinding
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MatrixContentProtectionStatus
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import io.element.android.security.contentprotection.api.StageThreeGateRequest
import io.element.android.security.contentprotection.api.StageThreeOperation
import io.element.android.security.contentprotection.api.StageThreeReleaseRetentionAuthority
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import io.element.android.security.securestorage.test.FakeVaultKeyService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultStageThreeSecurityGateTest {
    @Test
    fun `retained read admission checks live trust while avoiding repeated hardware metadata queries`() = runTest {
        var descriptors = 0
        val keys = object : DeviceShareKeyService by TestDeviceShareKeyService {
            override suspend fun descriptor(): DeviceShareKeyDescriptor {
                descriptors++
                return DESCRIPTOR
            }
        }
        val fixture = fixture(deviceShareKeyService = keys)
        val release = request(StageThreeOperation.RELEASE)
        val admission = fixture.gate.openReadAuthority(release)
        repeat(100) { admission.requireAccess() }
        assertThat(descriptors).isEqualTo(1)
        fixture.deviceTrust.set(trustSnapshot().copy(trustState = DeviceTrustState.REVOKED))
        assertThat(runCatching { admission.requireAccess() }.exceptionOrNull()).isInstanceOf(StageThreeGateDenied::class.java)
        fixture.deviceTrust.set(trustSnapshot())
        fixture.gate.requireAccess(release)
        assertThat(descriptors).isEqualTo(2)
    }

    @Test
    fun `retained read authority cannot be opened without full trust admission`() = runTest {
        val fixture = fixture()
        fixture.deviceTrust.set(trustSnapshot().copy(trustState = DeviceTrustState.REVOKED))
        assertThat(runCatching { fixture.gate.openReadAuthority(request(StageThreeOperation.RELEASE)) }.exceptionOrNull())
            .isInstanceOf(StageThreeGateDenied::class.java)
        assertThat(runCatching { fixture.gate.openReadAuthority(request()) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runtime authorization refresh defaults to a hard fail closed boundary`() {
        val runtime = DefaultMatrixContentProtectionRuntime()
        val refreshes = mutableListOf<MatrixAuthorizationRefresh>()
        runtime.registerAuthorizationRefreshListener(refreshes::add)

        runtime.requestAuthorizationRefresh()

        assertThat(refreshes).hasSize(1)
        assertThat(refreshes.single().revision).isEqualTo(1L)
        assertThat(refreshes.single().mode).isEqualTo(MatrixAuthorizationRefreshMode.HARD_INVALIDATION)
        assertThat(refreshes.single().latestHardInvalidationRevision).isEqualTo(1L)
    }

    @Test
    fun `selective cache expiry carries the latest hard generation without creating a new one`() {
        val runtime = DefaultMatrixContentProtectionRuntime()
        val refreshes = mutableListOf<MatrixAuthorizationRefresh>()
        runtime.registerAuthorizationRefreshListener(refreshes::add)
        runtime.requestAuthorizationRefresh()

        runtime.requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)

        assertThat(refreshes).hasSize(2)
        assertThat(refreshes.last().revision).isEqualTo(2L)
        assertThat(refreshes.last().mode).isEqualTo(MatrixAuthorizationRefreshMode.SELECTIVE_CACHE_EXPIRY)
        assertThat(refreshes.last().latestHardInvalidationRevision).isEqualTo(1L)
    }

    @Test
    fun `unlock retry retains the latest hard security boundary`() {
        val runtime = DefaultMatrixContentProtectionRuntime()
        val refreshes = mutableListOf<MatrixAuthorizationRefresh>()
        runtime.registerAuthorizationRefreshListener(refreshes::add)
        runtime.requestAuthorizationRefresh()
        runtime.requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.PREVIEW_ACCESS_REOPENED)
        assertThat(refreshes.last().revision).isEqualTo(2L)
        assertThat(refreshes.last().latestHardInvalidationRevision).isEqualTo(1L)
        assertThat(refreshes.last().mode).isEqualTo(MatrixAuthorizationRefreshMode.PREVIEW_ACCESS_REOPENED)
    }

    @Test
    fun `history recovery is scoped and rejects a stale generation`() {
        val runtime = DefaultMatrixContentProtectionRuntime()
        val refreshes = mutableListOf<MatrixAuthorizationRefresh>()
        runtime.registerAuthorizationRefreshListener(refreshes::add)

        runtime.requestHistoryRecoveryRefresh(
            accountScopeId = "account-hash",
            roomScopeIds = setOf("!first:example.org", "!second:example.org"),
            recoveryGeneration = 2L,
        )
        runtime.requestHistoryRecoveryRefresh(
            accountScopeId = "account-hash",
            roomScopeIds = setOf("!first:example.org"),
            recoveryGeneration = 1L,
        )

        assertThat(refreshes).hasSize(1)
        assertThat(refreshes.single().mode).isEqualTo(MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA)
        assertThat(refreshes.single().accountScopeId).isEqualTo("account-hash")
        assertThat(refreshes.single().roomScopeIds).containsExactly("!first:example.org", "!second:example.org")
        assertThat(refreshes.single().recoveryGeneration).isEqualTo(2L)
        assertThat(refreshes.single().latestHardInvalidationRevision).isEqualTo(0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generic refresh cannot create an unscoped history authorization`() {
        DefaultMatrixContentProtectionRuntime()
            .requestAuthorizationRefresh(MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA)
    }

    @Test
    fun `content invalidation clears only its target without advancing authorization revision`() {
        val runtime = DefaultMatrixContentProtectionRuntime()
        val cleared = mutableListOf<ContentId>()
        val invalidations = mutableListOf<MatrixContentInvalidation>()
        val authorizationRefreshes = mutableListOf<MatrixAuthorizationRefresh>()
        runtime.registerSensitiveContentClearer(cleared::add)
        runtime.registerContentInvalidationListener(invalidations::add)
        runtime.registerAuthorizationRefreshListener(authorizationRefreshes::add)

        runtime.requestContentInvalidation(ContentId("content-target"))

        assertThat(cleared).containsExactly(ContentId("content-target"))
        assertThat(invalidations).hasSize(1)
        assertThat(invalidations.single().contentId).isEqualTo(ContentId("content-target"))
        assertThat(runtime.authorizationRevision()).isEqualTo(0L)
        assertThat(authorizationRefreshes).isEmpty()
    }

    @Test
    fun `verified strict deployment passes the final gate`() = runTest {
        val fixture = fixture()

        val readiness = fixture.gate.requireAccess(request())

        assertThat(readiness.policyId).isEqualTo(POLICY_ID)
        assertThat(readiness.installationId).isEqualTo(INSTALLATION_ID)
    }

    @Test
    fun `verified v3 room contract may extend beyond the legacy global retention ceiling`() = runTest {
        val fixture = fixture(
            activePolicy = strictPolicy().copy(expiresAtEpochMillis = 20_000),
        )
        val contractExpiry = 15_000L

        val readiness = fixture.gate.requireAccess(
            request().copy(
                policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, contractExpiry),
                roomLifecycle = roomLifecycle(messageExpiresAtEpochMillis = contractExpiry),
            )
        )

        assertThat(readiness.policyId).isEqualTo(POLICY_ID)
    }

    @Test
    fun `v3 room contract cannot bypass an expiry mismatch`() = runTest {
        val fixture = fixture(activePolicy = strictPolicy().copy(expiresAtEpochMillis = 20_000))

        assertDenied(
            fixture,
            StageThreeDenialReason.RETENTION_EXCEEDED,
            request().copy(
                policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, 15_000),
                roomLifecycle = roomLifecycle(messageExpiresAtEpochMillis = 14_000),
            ),
        )
    }

    @Test
    fun `expired v3 room contract requests one bounded refresh`() = runTest {
        val fixture = fixture(activePolicy = strictPolicy().copy(expiresAtEpochMillis = 20_000))
        val contractExpiry = 15_000L

        val failure = runCatchingExceptions {
            fixture.gate.requireAccess(
                request().copy(
                    policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, contractExpiry),
                    roomLifecycle = roomLifecycle(
                        messageExpiresAtEpochMillis = contractExpiry,
                        contractExpiresAtEpochMillis = NOW,
                    ),
                )
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ContentProtectionException.LifecycleContractRefreshRequired::class.java)
    }

    @Test
    fun `legacy request above global retention ceiling remains denied`() = runTest {
        val fixture = fixture(activePolicy = strictPolicy().copy(expiresAtEpochMillis = 20_000))

        assertDenied(
            fixture,
            StageThreeDenialReason.RETENTION_EXCEEDED,
            request().copy(policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, 15_000)),
        )
    }

    @Test
    fun `online KMS may authoritatively validate v3 release retention above legacy ceiling`() = runTest {
        val historical = strictPolicy().copy(expiresAtEpochMillis = 20_000)
        val fixture = fixture(activePolicy = historical, historicalPolicies = listOf(historical))

        val readiness = fixture.gate.requireAccess(
            request(operation = StageThreeOperation.RELEASE).copy(
                policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, 15_000),
                releaseRetentionAuthority = StageThreeReleaseRetentionAuthority.ONLINE_KMS,
            )
        )

        assertThat(readiness.policyId).isEqualTo(POLICY_ID)
    }

    @Test
    fun `online KMS retention authority cannot be used for bounded offline release`() = runTest {
        val historical = strictPolicy().copy(
            expiresAtEpochMillis = 20_000,
            offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
            maximumOfflineMillis = 5_000,
        )
        val fixture = fixture(
            activePolicy = historical,
            historicalPolicies = listOf(historical),
            configuration = kmsConfiguration().copy(maximumOfflineLeaseMillis = 5_000),
        )

        assertDenied(
            fixture,
            StageThreeDenialReason.RETENTION_EXCEEDED,
            request(operation = StageThreeOperation.RELEASE).copy(
                policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, 15_000),
                offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE,
                releaseRetentionAuthority = StageThreeReleaseRetentionAuthority.ONLINE_KMS,
            ),
        )
    }

    @Test
    fun `online KMS retention authority still rejects an expired release`() = runTest {
        val historical = strictPolicy().copy(expiresAtEpochMillis = 20_000)
        val fixture = fixture(activePolicy = historical, historicalPolicies = listOf(historical))

        assertDenied(
            fixture,
            StageThreeDenialReason.RETENTION_EXCEEDED,
            request(operation = StageThreeOperation.RELEASE).copy(
                policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, NOW),
                releaseRetentionAuthority = StageThreeReleaseRetentionAuthority.ONLINE_KMS,
            ),
        )
    }

    @Test
    fun `built in policy cannot impersonate a verified remote policy`() = runTest {
        val fixture = fixture(source = DataProtectionPolicySource.BUILT_IN_BASELINE)

        assertDenied(fixture, StageThreeDenialReason.REMOTE_POLICY_UNAVAILABLE)
    }

    @Test
    fun `revoked device is denied before content keys are released`() = runTest {
        val fixture = fixture()
        fixture.deviceTrust.set(trustSnapshot().copy(trustState = DeviceTrustState.REVOKED))

        assertDenied(fixture, StageThreeDenialReason.DEVICE_NOT_TRUSTED)
    }

    @Test
    fun `unpinned KMS transport cannot pass the final gate`() = runTest {
        val fixture = fixture(configuration = kmsConfiguration().copy(certificatePinsSha256 = emptySet()))

        assertDenied(fixture, StageThreeDenialReason.KMS_TRANSPORT_NOT_PINNED)
    }

    @Test
    fun `missing Matrix envelope mapping keeps production fail closed`() = runTest {
        val fixture = fixture(matrixStatus = MatrixContentProtectionStatus.NOT_CONNECTED)

        assertDenied(fixture, StageThreeDenialReason.MATRIX_MAPPING_NOT_READY)
    }

    @Test
    fun `independently policy compliant device keys do not need identical hardware levels`() = runTest {
        val fixture = fixture(
            deviceShareKeyService = deviceShareKeyService(HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT),
        )
        fixture.deviceTrust.set(trustSnapshot().copy(hardwareSecurityLevel = HardwareSecurityLevel.STRONGBOX))

        val readiness = fixture.gate.requireAccess(request())

        assertThat(readiness.installationId).isEqualTo(INSTALLATION_ID)
    }

    @Test
    fun `software backed share key remains denied by hardware policy`() = runTest {
        val fixture = fixture(
            deviceShareKeyService = deviceShareKeyService(HardwareSecurityLevel.SOFTWARE),
        )

        assertDenied(fixture, StageThreeDenialReason.DEVICE_SHARE_KEY_MISMATCH)
    }

    @Test
    fun `active managed license permits only the software key bound online path`() = runTest {
        val fixture = fixture(deviceShareKeyService = deviceShareKeyService(HardwareSecurityLevel.SOFTWARE))
        fixture.deviceTrust.set(managedTrustSnapshot())

        val readiness = fixture.gate.requireAccess(request())

        assertThat(readiness.installationId).isEqualTo(INSTALLATION_ID)
    }

    @Test
    fun `managed license cannot inherit bounded offline access from room policy`() = runTest {
        val fixture = fixture(
            activePolicy = strictPolicy().copy(
                offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
                maximumOfflineMillis = 5_000,
            ),
            deviceShareKeyService = deviceShareKeyService(HardwareSecurityLevel.SOFTWARE),
        )
        fixture.deviceTrust.set(managedTrustSnapshot())

        assertDenied(
            fixture,
            StageThreeDenialReason.OFFLINE_POLICY_MISMATCH,
            request().copy(offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE),
        )
    }

    @Test
    fun `strongbox policy rejects a TEE share key even when the trust key is strongbox`() = runTest {
        val fixture = fixture(
            activePolicy = strictPolicy().copy(hardwareTrustRequirement = HardwareTrustRequirement.STRONGBOX),
            deviceShareKeyService = deviceShareKeyService(HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT),
        )
        fixture.deviceTrust.set(trustSnapshot().copy(hardwareSecurityLevel = HardwareSecurityLevel.STRONGBOX))

        assertDenied(fixture, StageThreeDenialReason.DEVICE_SHARE_KEY_MISMATCH)
    }

    @Test
    fun `bounded offline policy permits stricter online only burn content`() = runTest {
        val fixture = fixture(
            activePolicy = strictPolicy().copy(
                offlineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
                maximumOfflineMillis = 1_000,
            ),
        )

        val readiness = fixture.gate.requireAccess(request())

        assertThat(readiness.policyId).isEqualTo(POLICY_ID)
    }

    @Test
    fun `online only policy cannot be weakened to bounded offline content`() = runTest {
        val fixture = fixture()

        assertDenied(
            fixture,
            StageThreeDenialReason.OFFLINE_POLICY_MISMATCH,
            request().copy(offlineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE),
        )
    }

    @Test
    fun `release resolves the exact retained signed policy after a policy rollover`() = runTest {
        val historical = strictPolicy()
        val current = strictPolicy().copy(
            version = POLICY_VERSION + 1,
            effectiveAtEpochMillis = 1_800,
            expiresAtEpochMillis = 20_000,
        )
        val fixture = fixture(activePolicy = current, historicalPolicies = listOf(historical))

        val readiness = fixture.gate.requireAccess(request(operation = StageThreeOperation.RELEASE))

        assertThat(readiness.policyVersion).isEqualTo(POLICY_VERSION)
    }

    @Test
    fun `new content cannot protect with a historical policy after rollover`() = runTest {
        val historical = strictPolicy()
        val current = strictPolicy().copy(
            version = POLICY_VERSION + 1,
            effectiveAtEpochMillis = 1_800,
            expiresAtEpochMillis = 20_000,
        )
        val fixture = fixture(activePolicy = current, historicalPolicies = listOf(historical))

        assertDenied(fixture, StageThreeDenialReason.POLICY_MISMATCH, request())
    }

    @Test
    fun `release fails closed when the exact historical signed policy is unavailable`() = runTest {
        val current = strictPolicy().copy(
            version = POLICY_VERSION + 1,
            effectiveAtEpochMillis = 1_800,
            expiresAtEpochMillis = 20_000,
        )
        val fixture = fixture(activePolicy = current)

        assertDenied(
            fixture,
            StageThreeDenialReason.POLICY_MISMATCH,
            request(operation = StageThreeOperation.RELEASE),
        )
    }

    @Test
    fun `historical policy must have been active when the content was created`() = runTest {
        val fixture = fixture(historicalPolicies = listOf(strictPolicy()))

        assertDenied(
            fixture,
            StageThreeDenialReason.POLICY_NOT_ACTIVE,
            request(operation = StageThreeOperation.RELEASE, createdAtEpochMillis = 999),
        )
    }

    @Test
    fun `connected binding requires the exact approved union for every audience account`() = runTest {
        val binding = ConnectedMatrixContentProtectionBinding(
            runtime = ConnectedRuntime,
            registry = TestRecipientRegistry(
                mapOf(
                    ACCOUNT_HASH to listOf(RECIPIENT),
                    SECOND_ACCOUNT_HASH to listOf(SECOND_RECIPIENT),
                )
            ),
        )

        assertThat(
            binding.recipientsApproved(
                setOf(SECOND_ACCOUNT_HASH, ACCOUNT_HASH),
                listOf(SECOND_RECIPIENT, RECIPIENT),
            )
        ).isTrue()
        assertThat(binding.recipientsApproved(setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH), listOf(RECIPIENT))).isFalse()
        assertThat(
            binding.recipientsApproved(
                setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH),
                listOf(RECIPIENT, SECOND_RECIPIENT, OUTSIDER_RECIPIENT),
            )
        ).isFalse()
    }

    @Test
    fun `connected binding fails closed when any audience registry is missing`() = runTest {
        val binding = ConnectedMatrixContentProtectionBinding(
            runtime = ConnectedRuntime,
            registry = TestRecipientRegistry(mapOf(ACCOUNT_HASH to listOf(RECIPIENT))),
        )

        assertThat(
            binding.recipientsApproved(
                setOf(ACCOUNT_HASH, SECOND_ACCOUNT_HASH),
                listOf(RECIPIENT, SECOND_RECIPIENT),
            )
        ).isFalse()
    }

    private suspend fun assertDenied(
        fixture: Fixture,
        expected: StageThreeDenialReason,
        gateRequest: StageThreeGateRequest = request(),
    ) {
        val failure = runCatchingExceptions { fixture.gate.requireAccess(gateRequest) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(StageThreeGateDenied::class.java)
        assertThat((failure as StageThreeGateDenied).reason).isEqualTo(expected)
    }

    private suspend fun fixture(
        source: DataProtectionPolicySource = DataProtectionPolicySource.VERIFIED_REMOTE,
        configuration: KmsClientConfiguration = kmsConfiguration(),
        matrixStatus: MatrixContentProtectionStatus = MatrixContentProtectionStatus.READY,
        activePolicy: DataProtectionPolicyV1 = strictPolicy(),
        historicalPolicies: List<DataProtectionPolicyV1> = emptyList(),
        deviceShareKeyService: DeviceShareKeyService = TestDeviceShareKeyService,
    ): Fixture {
        val vaultKeys = FakeVaultKeyService()
        vaultKeys.provision(
            VaultProvisioningRequest(
                vaultId = VAULT_ID,
                accountIdHash = ACCOUNT_HASH,
                requireUserAuthentication = true,
                requireTrustedHardware = true,
            )
        )
        vaultKeys.unlock(VAULT_ID)
        val deviceTrust = FakeDeviceTrustService(trustSnapshot())
        val matrixBinding = TestMatrixBinding(matrixStatus)
        val gate = DefaultStageThreeSecurityGate(
            policyProvider = FixedPolicyProvider(
                active = ActiveDataProtectionPolicy(activePolicy, source),
                historicalPolicies = historicalPolicies,
            ),
            deviceTrustService = deviceTrust,
            vaultKeyService = vaultKeys,
            deviceShareKeyService = deviceShareKeyService,
            configurationProvider = KmsClientConfigurationProvider { configuration },
            authorizationProvider = KmsAuthorizationProvider { KmsRequestAuthorization.bearer("short-lived-test-token") },
            matrixBinding = matrixBinding,
            clock = SystemClock { NOW },
        )
        return Fixture(gate, deviceTrust)
    }

    private fun request(
        operation: StageThreeOperation = StageThreeOperation.PROTECT,
        createdAtEpochMillis: Long = CONTENT_CREATED_AT,
    ) = StageThreeGateRequest(
        operation = operation,
        vaultId = VAULT_ID,
        accountIdHash = ACCOUNT_HASH,
        installationId = INSTALLATION_ID,
        policy = ContentPolicyReference(PolicyId(POLICY_ID), POLICY_VERSION, CONTENT_EXPIRY),
        contentCreatedAtEpochMillis = createdAtEpochMillis,
        offlineLeaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
        recipients = listOf(RECIPIENT),
    )

    private fun strictPolicy() = DataProtectionPolicyV1(
        policyId = POLICY_ID,
        version = POLICY_VERSION,
        issuedAtEpochMillis = 500,
        effectiveAtEpochMillis = 1_000,
        expiresAtEpochMillis = POLICY_EXPIRY,
        contentRetentionMillis = 8_000,
        maximumOfflineMillis = 0,
        offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
        notificationExposure = NotificationExposure.HIDDEN,
        hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
        allowContentExport = false,
        allowClipboard = false,
        allowExternalShare = false,
        screenCapturePolicy = ScreenCapturePolicy.BLOCK,
    )

    private fun roomLifecycle(
        messageExpiresAtEpochMillis: Long,
        contractExpiresAtEpochMillis: Long = 2_500,
    ) = ResolvedRoomLifecycle(
        roomIdSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 1 },
        profileId = "STANDARD_30D",
        profileKind = RoomLifecycleProfileKind.STANDARD,
        retentionMillis = 8_000,
        maximumOfflineMillis = 0,
        recoveryPolicy = RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY,
        messageExpiresAtEpochMillis = messageExpiresAtEpochMillis,
        issuedAtEpochMillis = 1_900,
        expiresAtEpochMillis = contractExpiresAtEpochMillis,
        protocolVersion = 3,
        contractId = "contract_012345678901234567890123",
        profileRevision = 1,
        contractSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 2 },
        signingKeyId = "signing-key-1",
    )

    private data class Fixture(
        val gate: DefaultStageThreeSecurityGate,
        val deviceTrust: FakeDeviceTrustService,
    )

    private class FixedPolicyProvider(
        private val active: ActiveDataProtectionPolicy,
        historicalPolicies: List<DataProtectionPolicyV1> = emptyList(),
    ) : DataProtectionPolicyProvider {
        private val history = (historicalPolicies + active.policy).associateBy { it.policyId to it.version }

        override fun current(): ActiveDataProtectionPolicy = active

        override fun resolve(policyId: String, version: Long): ActiveDataProtectionPolicy? =
            history[policyId to version]?.let { ActiveDataProtectionPolicy(it, DataProtectionPolicySource.VERIFIED_REMOTE) }
    }

    private class TestMatrixBinding(
        private val currentStatus: MatrixContentProtectionStatus,
    ) : MatrixContentProtectionBinding {
        override fun status(): MatrixContentProtectionStatus = currentStatus

        override suspend fun recipientsApproved(accountIdHashes: Set<String>, recipients: List<DeviceShareRecipient>): Boolean = true
    }

    private data object ConnectedRuntime : MatrixContentProtectionRuntime {
        override fun attach() = Unit

        override fun detach() = Unit

        override fun isConnected(): Boolean = true
    }

    private class TestRecipientRegistry(
        private val recipientsByAccount: Map<String, List<DeviceShareRecipient>>,
    ) : DeviceRecipientRegistry {
        override suspend fun approvedRecipients(accountIdHash: String): DeviceRecipientRegistrySnapshot = DeviceRecipientRegistrySnapshot(
            accountIdHash = accountIdHash,
            issuedAtEpochMillis = NOW - 100,
            expiresAtEpochMillis = NOW + 100,
            recipients = recipientsByAccount[accountIdHash].orEmpty(),
        )
    }

    private data object TestDeviceShareKeyService : DeviceShareKeyService {
        override suspend fun descriptor(): DeviceShareKeyDescriptor = DESCRIPTOR

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope = error("Not used")

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray = error("Not used")

        override suspend fun destroy(): Boolean = true
    }

    private fun deviceShareKeyService(hardwareSecurityLevel: HardwareSecurityLevel): DeviceShareKeyService =
        object : DeviceShareKeyService {
            override suspend fun descriptor(): DeviceShareKeyDescriptor = DESCRIPTOR.copy(
                hardwareSecurityLevel = hardwareSecurityLevel,
            )

            override suspend fun wrapShare(
                recipient: DeviceShareRecipient,
                contentId: ContentId,
                immutableContextSha256: ByteArray,
                share: ByteArray,
            ): DeviceShareEnvelope = error("Not used")

            override suspend fun unwrapShare(
                envelope: DeviceShareEnvelope,
                contentId: ContentId,
                immutableContextSha256: ByteArray,
            ): ByteArray = error("Not used")

            override suspend fun destroy(): Boolean = true
        }

    private companion object {
        const val INSTALLATION_ID = "installation-1"
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECOND_ACCOUNT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val POLICY_ID = "strict-policy"
        const val POLICY_VERSION = 7L
        const val CONTENT_CREATED_AT = 1_500L
        const val NOW = 2_000L
        const val CONTENT_EXPIRY = 9_000L
        const val POLICY_EXPIRY = 10_000L
        val VAULT_ID = AccountVaultId("vault-1")
        val DESCRIPTOR = DeviceShareKeyDescriptor(
            installationId = INSTALLATION_ID,
            keyId = "device-share-key-1",
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = byteArrayOf(1, 2, 3),
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
        )
        val RECIPIENT = DeviceShareRecipient(
            installationId = DESCRIPTOR.installationId,
            keyId = DESCRIPTOR.keyId,
            algorithm = DESCRIPTOR.algorithm,
            publicKeyDer = DESCRIPTOR.publicKeyDer,
            allowsBoundedOffline = true,
        )
        val SECOND_RECIPIENT = DeviceShareRecipient(
            installationId = "installation-2",
            keyId = "device-share-key-2",
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = byteArrayOf(4, 5, 6),
            allowsBoundedOffline = true,
        )
        val OUTSIDER_RECIPIENT = DeviceShareRecipient(
            installationId = "installation-outsider",
            keyId = "device-share-key-outsider",
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = byteArrayOf(7, 8, 9),
            allowsBoundedOffline = true,
        )

        fun trustSnapshot() = DeviceTrustSnapshot(
            installationId = INSTALLATION_ID,
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = 2,
            lastVerifiedAtEpochMillis = NOW - 100,
        )

        fun managedTrustSnapshot() = trustSnapshot().copy(
            hardwareSecurityLevel = HardwareSecurityLevel.SOFTWARE,
            trustState = DeviceTrustState.MANAGED,
            managedLicense = ManagedDeviceLicense(
                licenseId = "managed-license-1",
                installationId = INSTALLATION_ID,
                devicePublicKeySha256 = "ab".repeat(32),
                contentShareKeyId = DESCRIPTOR.keyId,
                appPackageName = "com.yizhe.chat",
                appVersionCode = 1,
                revision = 1,
                allowedScopes = setOf("PROTECTED_MESSAGES", "PROTECTED_MEDIA"),
                offlineAllowed = false,
                issuedAtEpochMillis = 1,
                expiresAtEpochMillis = Long.MAX_VALUE / 2,
            ),
        )

        fun kmsConfiguration() = KmsClientConfiguration(
            baseUrl = "https://kms.example.org/",
            signingPublicKeyDer = byteArrayOf(1, 2, 3),
            certificatePinsSha256 = setOf("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            maximumOfflineLeaseMillis = 0,
            maximumOfflineLicenseUses = 0,
            clockRollbackToleranceMillis = 0,
        )
    }
}
