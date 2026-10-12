/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefresh
import io.element.android.security.contentprotection.api.MatrixAuthorizationRefreshMode
import io.element.android.security.contentprotection.api.MatrixContentInvalidation
import io.element.android.security.contentprotection.api.MatrixContentProtectionBinding
import io.element.android.security.contentprotection.api.MatrixContentProtectionRuntime
import io.element.android.security.contentprotection.api.MatrixContentProtectionStatus
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.StageThreeDenialReason
import io.element.android.security.contentprotection.api.StageThreeGateDenied
import io.element.android.security.contentprotection.api.StageThreeGateRequest
import io.element.android.security.contentprotection.api.StageThreeOperation
import io.element.android.security.contentprotection.api.StageThreeReleaseRetentionAuthority
import io.element.android.security.contentprotection.api.StageThreeReadiness
import io.element.android.security.contentprotection.api.StageThreeSecurityGate
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * Final client-side acceptance gate for stage three.
 *
 * The gate deliberately has no permissive fallback. Until verified remote policy, trusted hardware, an unlocked
 * account vault, pinned/authenticated KMS and the Matrix envelope mapping are all present, content access is denied.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultStageThreeSecurityGate(
    private val policyProvider: DataProtectionPolicyProvider,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val configurationProvider: KmsClientConfigurationProvider,
    private val authorizationProvider: KmsAuthorizationProvider,
    private val matrixBinding: MatrixContentProtectionBinding,
    private val clock: SystemClock,
) : StageThreeSecurityGate {
    override suspend fun requireAccess(request: StageThreeGateRequest): StageThreeReadiness =
        checkAccess(request, null).first

    override suspend fun openReadAuthority(request: StageThreeGateRequest): io.element.android.security.contentprotection.api.StageThreeReadAuthority {
        require(request.operation == StageThreeOperation.RELEASE)
        val admitted = checkAccess(request, null).second
        return object : io.element.android.security.contentprotection.api.StageThreeReadAuthority {
            override suspend fun requireAccess() {
                // The key's hardware class is immutable. Policy, device trust, vault, transport and binding stay live.
                checkAccess(request, admitted)
            }
        }
    }

    @Suppress("ComplexCondition")
    private suspend fun checkAccess(
        request: StageThreeGateRequest,
        retainedDescriptor: io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor?,
    ): Pair<StageThreeReadiness, io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor> {
        require(request.accountIdHash.isNotBlank())
        require(request.accountIdHash in request.audienceAccountIdHashes)
        require(request.audienceAccountIdHashes.isNotEmpty())
        require(request.installationId.isNotBlank())
        require(request.contentCreatedAtEpochMillis > 0)

        val active = policyProvider.current()
        if (active.source != DataProtectionPolicySource.VERIFIED_REMOTE) {
            deny(StageThreeDenialReason.REMOTE_POLICY_UNAVAILABLE)
        }
        val now = clock.epochMillis()
        val currentPolicy = active.policy
        if (now < currentPolicy.effectiveAtEpochMillis || currentPolicy.expiresAtEpochMillis?.let { now >= it } == true) {
            deny(StageThreeDenialReason.POLICY_NOT_ACTIVE)
        }
        val policy = when (request.operation) {
            StageThreeOperation.PROTECT -> currentPolicy.takeIf {
                request.policy.policyId.value == it.policyId && request.policy.policyVersion == it.version
            }
            StageThreeOperation.RELEASE -> policyProvider.resolve(
                policyId = request.policy.policyId.value,
                version = request.policy.policyVersion,
            )?.takeIf { it.source == DataProtectionPolicySource.VERIFIED_REMOTE }?.policy
        } ?: deny(StageThreeDenialReason.POLICY_MISMATCH)
        if (request.contentCreatedAtEpochMillis < policy.effectiveAtEpochMillis ||
            policy.expiresAtEpochMillis?.let { request.contentCreatedAtEpochMillis >= it } == true
        ) {
            deny(StageThreeDenialReason.POLICY_NOT_ACTIVE)
        }
        val lifecycleContract = request.roomLifecycle?.takeIf {
            request.operation == StageThreeOperation.PROTECT &&
                it.protocolVersion == ROOM_LIFECYCLE_CONTRACT_PROTOCOL_VERSION
        }
        if (lifecycleContract != null) {
            // HttpKmsShareClient has already verified the server signature, room binding, contract digest and
            // lifecycle bounds. The gate still requires a complete v3 reference and an exact expiry binding so
            // callers cannot opt into the v3 path with an unbound value. KMS repeats the authoritative contract
            // lookup and exact-expiry check before storing any share.
            if (lifecycleContract.roomIdSha256.size != ContentProtectionFormat.SHA256_SIZE_BYTES ||
                lifecycleContract.profileId.isBlank() ||
                lifecycleContract.retentionMillis <= 0 ||
                lifecycleContract.maximumOfflineMillis < 0 ||
                lifecycleContract.messageExpiresAtEpochMillis != request.policy.expiresAtEpochMillis ||
                lifecycleContract.messageExpiresAtEpochMillis <= now ||
                lifecycleContract.issuedAtEpochMillis >= lifecycleContract.expiresAtEpochMillis ||
                lifecycleContract.contractId.isNullOrBlank() ||
                (lifecycleContract.profileRevision ?: 0) <= 0 ||
                lifecycleContract.contractSha256?.size != ContentProtectionFormat.SHA256_SIZE_BYTES ||
                lifecycleContract.signingKeyId.isNullOrBlank()
            ) {
                deny(StageThreeDenialReason.RETENTION_EXCEEDED)
            }
            if (now >= lifecycleContract.expiresAtEpochMillis) {
                throw ContentProtectionException.LifecycleContractRefreshRequired()
            }
        } else if (
            request.operation == StageThreeOperation.RELEASE &&
            request.releaseRetentionAuthority == StageThreeReleaseRetentionAuthority.ONLINE_KMS
        ) {
            // V3 room-epoch envelopes cannot expose their short-lived signed lifecycle contract without
            // changing the authenticated Matrix format. They are nevertheless always released through the
            // pinned online KMS, which verifies the request's immutable context against the stored V3 contract
            // before returning a share. Keep the immediate expiry check here; only the legacy upper-bound
            // comparison is deferred. Bounded-offline and legacy envelopes can never select this branch.
            if (request.offlineLeaseMode != OfflineLeaseMode.ONLINE_REQUIRED || now >= request.policy.expiresAtEpochMillis) {
                deny(StageThreeDenialReason.RETENTION_EXCEEDED)
            }
        } else {
            val retentionDeadline = safeAdd(request.contentCreatedAtEpochMillis, policy.contentRetentionMillis)
            val policyDeadline = minOf(retentionDeadline, policy.expiresAtEpochMillis ?: Long.MAX_VALUE)
            if (request.policy.expiresAtEpochMillis > policyDeadline || now >= request.policy.expiresAtEpochMillis) {
                deny(StageThreeDenialReason.RETENTION_EXCEEDED)
            }
        }
        if (!offlineModeMatches(request.offlineLeaseMode, policy.offlineAccessMode)) {
            Timber.tag(DIAGNOSTIC_TAG).w(
                "offline_policy_mismatch operation=%s request=%s policy=%s",
                request.operation.name,
                request.offlineLeaseMode.name,
                policy.offlineAccessMode.name,
            )
            deny(StageThreeDenialReason.OFFLINE_POLICY_MISMATCH)
        }
        if (policy.hardwareTrustRequirement == HardwareTrustRequirement.NONE) {
            deny(StageThreeDenialReason.POLICY_HARDWARE_REQUIREMENT_TOO_WEAK)
        }

        val trust = deviceTrustService.current()
        if (trust.installationId != request.installationId) {
            deny(StageThreeDenialReason.INSTALLATION_MISMATCH)
        }
        val managedLicense = trust.managedLicense
        val managedCompatible = trust.trustState == DeviceTrustState.MANAGED &&
            managedLicense?.isActive(now) == true
        if ((trust.trustState != DeviceTrustState.TRUSTED && !managedCompatible) || !trust.allowsLocalVaultAccess) {
            deny(StageThreeDenialReason.DEVICE_NOT_TRUSTED)
        }
        if (managedCompatible && request.offlineLeaseMode != OfflineLeaseMode.ONLINE_REQUIRED) {
            deny(StageThreeDenialReason.OFFLINE_POLICY_MISMATCH)
        }
        if (!managedCompatible && !hardwareMeetsPolicy(trust.hardwareSecurityLevel, policy.hardwareTrustRequirement)) {
            deny(StageThreeDenialReason.HARDWARE_NOT_TRUSTED)
        }
        if (!vaultKeyService.isUnlocked(request.vaultId)) {
            deny(StageThreeDenialReason.VAULT_LOCKED)
        }

        val descriptor = retainedDescriptor ?: deviceShareKeyService.descriptor()
        if (descriptor.installationId != request.installationId ||
            (!managedCompatible && !hardwareMeetsPolicy(descriptor.hardwareSecurityLevel, policy.hardwareTrustRequirement)) ||
            (managedCompatible && managedLicense.contentShareKeyId != descriptor.keyId)
        ) {
            deny(StageThreeDenialReason.DEVICE_SHARE_KEY_MISMATCH)
        }
        if (request.operation == StageThreeOperation.PROTECT) {
            val localRecipientMatches = request.recipients.any {
                it.installationId == descriptor.installationId && it.keyId == descriptor.keyId
            }
            if (!localRecipientMatches || !matrixBinding.recipientsApproved(request.audienceAccountIdHashes, request.recipients)) {
                deny(StageThreeDenialReason.RECIPIENT_REGISTRY_NOT_VERIFIED)
            }
        }

        val configuration = configurationProvider.configuration()
            ?: deny(StageThreeDenialReason.KMS_NOT_CONFIGURED)
        val baseUrl = configuration.baseUrl.toHttpUrlOrNull()
        if (baseUrl == null || !baseUrl.isHttps || configuration.signingPublicKeyDer.isEmpty() || configuration.allowCleartextForTests) {
            deny(StageThreeDenialReason.KMS_NOT_CONFIGURED)
        }
        if (configuration.certificatePinsSha256.isEmpty() ||
            configuration.certificatePinsSha256.any { !it.startsWith("sha256/") }
        ) {
            deny(StageThreeDenialReason.KMS_TRANSPORT_NOT_PINNED)
        }
        if (request.offlineLeaseMode == OfflineLeaseMode.BOUNDED_OFFLINE &&
            (configuration.maximumOfflineLeaseMillis !in 1..policy.maximumOfflineMillis ||
                lifecycleContract?.let { configuration.maximumOfflineLeaseMillis > it.maximumOfflineMillis } == true ||
                configuration.maximumOfflineLicenseUses <= 0)
        ) {
            Timber.tag(DIAGNOSTIC_TAG).w("offline_configuration_mismatch operation=%s", request.operation.name)
            deny(StageThreeDenialReason.OFFLINE_POLICY_MISMATCH)
        }
        if (!authorizationProvider.isAvailable()) {
            deny(StageThreeDenialReason.KMS_AUTHORIZATION_UNAVAILABLE)
        }
        if (matrixBinding.status() != MatrixContentProtectionStatus.READY) {
            deny(StageThreeDenialReason.MATRIX_MAPPING_NOT_READY)
        }

        return StageThreeReadiness(
            policyId = policy.policyId,
            policyVersion = policy.version,
            installationId = trust.installationId,
            deviceShareKeyId = descriptor.keyId,
        ) to descriptor
    }

    private fun offlineModeMatches(contentMode: OfflineLeaseMode, policyMode: OfflineAccessMode): Boolean = when (policyMode) {
        OfflineAccessMode.ONLINE_REQUIRED -> contentMode == OfflineLeaseMode.ONLINE_REQUIRED
        // BOUNDED_OFFLINE is the maximum access the policy permits, not a requirement that every
        // message must be readable offline. Burn-after-read deliberately chooses ONLINE_REQUIRED,
        // which is strictly narrower and must remain valid under a bounded-offline room policy.
        OfflineAccessMode.BOUNDED_OFFLINE ->
            contentMode == OfflineLeaseMode.ONLINE_REQUIRED || contentMode == OfflineLeaseMode.BOUNDED_OFFLINE
    }

    private fun hardwareMeetsPolicy(level: HardwareSecurityLevel, requirement: HardwareTrustRequirement): Boolean = when (requirement) {
        HardwareTrustRequirement.NONE -> false
        HardwareTrustRequirement.TEE_OR_STRONGBOX ->
            level == HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT || level == HardwareSecurityLevel.STRONGBOX
        HardwareTrustRequirement.STRONGBOX -> level == HardwareSecurityLevel.STRONGBOX
    }

    private fun safeAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun deny(reason: StageThreeDenialReason): Nothing {
        // Keep real-device diagnostics non-sensitive: the stable enum identifies only the failed
        // security prerequisite and never includes account, room, content, key, or installation data.
        Timber.tag(DIAGNOSTIC_TAG).w("gate_denied reason=%s", reason.name)
        throw StageThreeGateDenied(reason)
    }

    private companion object {
        const val DIAGNOSTIC_TAG = "YiZheStageThree"
        const val ROOM_LIFECYCLE_CONTRACT_PROTOCOL_VERSION = 3
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ConnectedMatrixContentProtectionBinding(
    private val runtime: MatrixContentProtectionRuntime,
    private val registry: DeviceRecipientRegistry,
) : MatrixContentProtectionBinding {
    override fun status(): MatrixContentProtectionStatus = if (runtime.isConnected()) {
        MatrixContentProtectionStatus.READY
    } else {
        MatrixContentProtectionStatus.NOT_CONNECTED
    }

    @Suppress("ComplexCondition")
    override suspend fun recipientsApproved(accountIdHashes: Set<String>, recipients: List<DeviceShareRecipient>): Boolean {
        if (!runtime.isConnected() || accountIdHashes.isEmpty() || recipients.isEmpty()) return false
        val approved = linkedMapOf<String, DeviceShareRecipient>()
        val snapshots = registry.approvedRecipients(accountIdHashes)
        if (snapshots.keys != accountIdHashes) return false
        for (accountIdHash in accountIdHashes.sorted()) {
            val snapshot = snapshots[accountIdHash] ?: return false
            if (snapshot.accountIdHash != accountIdHash || snapshot.recipients.isEmpty()) return false
            for (recipient in snapshot.recipients) {
                val existing = approved.putIfAbsent(recipient.installationId, recipient)
                if (existing != null && (existing.keyId != recipient.keyId || existing.algorithm != recipient.algorithm ||
                        !java.security.MessageDigest.isEqual(existing.publicKeyDer, recipient.publicKeyDer))
                ) {
                    return false
                }
            }
        }
        if (approved.size != recipients.size || recipients.map { it.installationId }.toSet() != approved.keys) return false
        return recipients.all { candidate ->
            val registered = approved[candidate.installationId]
            registered != null && registered.keyId == candidate.keyId && registered.algorithm == candidate.algorithm &&
                java.security.MessageDigest.isEqual(registered.publicKeyDer, candidate.publicKeyDer)
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMatrixContentProtectionRuntime : MatrixContentProtectionRuntime {
    private val connections = java.util.concurrent.atomic.AtomicInteger(0)
    private val sensitiveCacheClearers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val sensitiveContentClearers = java.util.concurrent.CopyOnWriteArraySet<(ContentId) -> Unit>()
    private val authorizationRefreshListeners = java.util.concurrent.CopyOnWriteArraySet<(MatrixAuthorizationRefresh) -> Unit>()
    private val contentInvalidationListeners = java.util.concurrent.CopyOnWriteArraySet<(MatrixContentInvalidation) -> Unit>()
    private val currentAuthorizationRevision = java.util.concurrent.atomic.AtomicLong(0L)
    private val latestHardInvalidationRevision = java.util.concurrent.atomic.AtomicLong(0L)
    private val currentContentInvalidationSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val authorizationRefreshLock = Any()
    private val latestHistoryRecoveryGenerationByAccount = mutableMapOf<String, Long>()

    override fun attach() {
        connections.incrementAndGet()
    }

    override fun detach() {
        connections.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun isConnected(): Boolean = connections.get() > 0

    override fun clearSensitiveCaches() {
        sensitiveCacheClearers.forEach { clearer ->
            runCatching { clearer() }
        }
    }

    override fun registerSensitiveCacheClearer(clearer: () -> Unit) {
        sensitiveCacheClearers += clearer
    }

    override fun registerScopedSensitiveCacheClearer(clearer: () -> Unit): AutoCloseable {
        sensitiveCacheClearers += clearer
        return AutoCloseable { sensitiveCacheClearers -= clearer }
    }

    override fun registerSensitiveContentClearer(clearer: (ContentId) -> Unit) {
        sensitiveContentClearers += clearer
    }

    override fun authorizationRevision(): Long = currentAuthorizationRevision.get()

    override fun registerAuthorizationRefreshListener(listener: (MatrixAuthorizationRefresh) -> Unit): AutoCloseable {
        authorizationRefreshListeners += listener
        return AutoCloseable { authorizationRefreshListeners -= listener }
    }

    override fun registerContentInvalidationListener(listener: (MatrixContentInvalidation) -> Unit): AutoCloseable {
        contentInvalidationListeners += listener
        return AutoCloseable { contentInvalidationListeners -= listener }
    }

    override fun requestAuthorizationRefresh(mode: MatrixAuthorizationRefreshMode) {
        require(mode != MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA) {
            "History recovery requires an explicit account, room scope and generation."
        }
        val refresh = synchronized(authorizationRefreshLock) {
            val revision = currentAuthorizationRevision.incrementAndGet()
            if (mode == MatrixAuthorizationRefreshMode.HARD_INVALIDATION) {
                latestHardInvalidationRevision.set(revision)
            }
            MatrixAuthorizationRefresh(
                revision = revision,
                mode = mode,
                latestHardInvalidationRevision = latestHardInvalidationRevision.get(),
            )
        }
        authorizationRefreshListeners.forEach { listener -> runCatching { listener(refresh) } }
    }

    override fun requestHistoryRecoveryRefresh(
        accountScopeId: String,
        roomScopeIds: Set<String>,
        recoveryGeneration: Long,
    ) {
        require(accountScopeId.isNotBlank())
        require(roomScopeIds.isNotEmpty() && roomScopeIds.none(String::isBlank))
        require(recoveryGeneration > 0L)
        val refresh = synchronized(authorizationRefreshLock) {
            val latestGeneration = latestHistoryRecoveryGenerationByAccount[accountScopeId] ?: 0L
            if (recoveryGeneration <= latestGeneration) return
            latestHistoryRecoveryGenerationByAccount[accountScopeId] = recoveryGeneration
            MatrixAuthorizationRefresh(
                revision = currentAuthorizationRevision.incrementAndGet(),
                mode = MatrixAuthorizationRefreshMode.HISTORY_RECOVERY_DELTA,
                latestHardInvalidationRevision = latestHardInvalidationRevision.get(),
                accountScopeId = accountScopeId,
                roomScopeIds = roomScopeIds.toSet(),
                recoveryGeneration = recoveryGeneration,
            )
        }
        authorizationRefreshListeners.forEach { listener -> runCatching { listener(refresh) } }
    }

    override fun requestContentInvalidation(contentId: ContentId) {
        require(contentId.value.isNotBlank())
        // Clear target plaintext synchronously before a timeline can observe the terminal signal.
        sensitiveContentClearers.forEach { clearer -> runCatching { clearer(contentId) } }
        val invalidation = MatrixContentInvalidation(
            sequence = currentContentInvalidationSequence.incrementAndGet(),
            contentId = contentId,
        )
        contentInvalidationListeners.forEach { listener -> runCatching { listener(invalidation) } }
    }
}
