/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appnav.loggedin

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.contentprotection.api.DeviceRecipientRegistry
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.MatrixContentProtectionBinding
import io.element.android.security.contentprotection.api.MatrixContentProtectionStatus
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountActivationExpired
import io.element.android.security.dataprotection.api.AccountReactivationCheckpointState
import io.element.android.security.dataprotection.api.AccountReactivationRequest
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Stable, non-sensitive prerequisite codes for entry diagnostics. */
internal class AccountEntryPrerequisiteFailure(val reasonCode: String) : IllegalStateException(reasonCode)

private fun requireEntryPrerequisite(satisfied: Boolean, reasonCode: String) {
    if (!satisfied) throw AccountEntryPrerequisiteFailure(reasonCode)
}

/**
 * Produces one server-signed CORE_READY receipt after every existing fail-closed
 * prerequisite has independently succeeded. It never changes those prerequisites.
 */
@Inject
@SingleIn(SessionScope::class)
class AccountReactivationCoordinator(
    private val managementControlService: ManagementControlService,
    private val deviceTrustService: DeviceTrustService,
    private val vaultKeyService: VaultKeyService,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val kmsAuthorizationProvider: KmsAuthorizationProvider,
    private val deviceRecipientRegistry: DeviceRecipientRegistry,
    private val matrixContentProtectionBinding: MatrixContentProtectionBinding,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val accountReactivationScopeStore: AccountReactivationScopeStore,
) {
    private val activationMutex = Mutex()

    /**
     * Classifies only the local account/installation continuity boundary. A retained marker
     * that cannot be authenticated is propagated as a failure so navigation can fail closed.
     */
    suspend fun requiresCoreReadyEnforcement(matrixClient: MatrixClient): Result<Boolean> = runCatching {
        val trust = deviceTrustService.current()
        accountReactivationScopeStore.requiresCoreReadyEnforcement(
            accountIdHash = accountIdHash(matrixClient.sessionId.value),
            installationId = trust.installationId,
            matrixDeviceIdHash = sha256Hex(matrixClient.deviceId.value),
        )
    }

    /** Restores the post-reset convergence job after process recreation without repeating reset. */
    suspend fun hasPendingExplicitIdentityReset(matrixClient: MatrixClient): Result<Boolean> = runCatching {
        val trust = deviceTrustService.current()
        val checkpoint = accountReactivationScopeStore.current(
            accountIdHash = accountIdHash(matrixClient.sessionId.value),
            installationId = trust.installationId,
            matrixDeviceIdHash = sha256Hex(matrixClient.deviceId.value),
        ) ?: return@runCatching false
        if (checkpoint.pendingExplicitIdentityReset) return@runCatching true
        val before = checkpoint.identityResetPreviousKeyHash ?: return@runCatching false
        val state = matrixClient.encryptionService.getIdentityResetCompletionState().getOrThrow()
        if (!state.isComplete || state.identityKeyHash == null || state.identityKeyHash == before) return@runCatching false
        check(accountReactivationScopeStore.markExplicitIdentityReset(
            accountIdHash(matrixClient.sessionId.value), trust.installationId, sha256Hex(matrixClient.deviceId.value),
        ))
        true
    }

    suspend fun canRestoreOffline(matrixClient: MatrixClient): Boolean {
        return try {
            readOfflineCoreCheckpoint(matrixClient)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            timber.log.Timber.w("Offline entry checkpoint is unavailable: %s", failure.javaClass.simpleName)
            false
        }
    }

    private suspend fun readOfflineCoreCheckpoint(matrixClient: MatrixClient): Boolean {
        val entry = managementControlService.offlineEntryStatus() ?: return false
        val trust = deviceTrustService.current()
        if (!trust.allowsLocalVaultAccess) return false
        val vaultId = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
        if (!vaultKeyService.isUnlocked(vaultId)) return false
        val accountHash = accountIdHash(matrixClient.sessionId.value)
        val deviceHash = sha256Hex(matrixClient.deviceId.value)
        val binding = entry.accountBindings.singleOrNull { it.accountIdHash == accountHash && it.matrixDeviceIdHash == deviceHash } ?: return false
        val checkpoint = accountReactivationScopeStore.current(accountHash, trust.installationId, deviceHash) ?: return false
        if (checkpoint.pendingExplicitIdentityReset || checkpoint.state != AccountReactivationCheckpointState.COMMITTED) return false
        return checkpoint.receipt?.let { receipt ->
            receipt.activationId == binding.activationId &&
            receipt.accountMobileGeneration == binding.accountMobileGeneration && receipt.matchesCurrentSecurityState(
            accountHash,
                trust.installationId,
                deviceHash,
                trust.attestationVersion,
                entry.policyVersion,
                deviceShareKeyService.descriptor().keyId,
        )
        } == true
    }

    /** A retained, signed list grant permits only navigation; content and sends keep their own gates. */
    private val localListDiagnosticBudget = java.util.concurrent.atomic.AtomicInteger(8)

    private fun localListUnavailable(reason: String): Boolean {
        if (localListDiagnosticBudget.getAndDecrement() > 0) {
            timber.log.Timber.tag("YiZheStartup").i("[Startup] Startup.localList.account.unavailable reason=%s", reason)
        }
        return false
    }

    // Every admission fact is still freshly read in order. Keep the IO chain off the caller
    // thread so each nested store does not resume the UI between reads or run the Keystore
    // descriptor tail there. withContext restores the caller and preserves cancellation.
    suspend fun canRestoreLocalList(matrixClient: MatrixClient): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = managementControlService.localListEntryStatus()
            if (entry == null || entry.remainingMillis <= 0) {
                localListUnavailable("ENTRY_UNAVAILABLE")
            } else {
                val trust = deviceTrustService.current()
                val accountHash = accountIdHash(matrixClient.sessionId.value)
                val deviceHash = sha256Hex(matrixClient.deviceId.value)
                val binding = entry.accountBindings.singleOrNull { it.accountIdHash == accountHash && it.matrixDeviceIdHash == deviceHash }
                val checkpoint = accountReactivationScopeStore.current(accountHash, trust.installationId, deviceHash)
                val receipt = checkpoint?.receipt
                when {
                    !trust.allowsLocalVaultAccess -> localListUnavailable("TRUST")
                    !vaultKeyService.isUnlocked(deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)) ->
                        localListUnavailable("VAULT_LOCKED")
                    binding == null -> localListUnavailable("ACCOUNT_BINDING")
                    checkpoint == null -> localListUnavailable("CHECKPOINT_MISSING")
                    receipt == null -> localListUnavailable("RECEIPT_MISSING")
                    checkpoint.state != AccountReactivationCheckpointState.COMMITTED -> localListUnavailable("CHECKPOINT_NOT_COMMITTED")
                    checkpoint.pendingExplicitIdentityReset -> localListUnavailable("IDENTITY_RESET")
                    receipt.activationId != binding.activationId -> localListUnavailable("ACTIVATION_MISMATCH")
                    receipt.accountMobileGeneration != binding.accountMobileGeneration -> localListUnavailable("GENERATION_MISMATCH")
                    !receipt.matchesCurrentSecurityState(accountHash, trust.installationId, deviceHash, trust.attestationVersion,
                        entry.policyVersion, deviceShareKeyService.descriptor().keyId) -> localListUnavailable("SECURITY_STATE_MISMATCH")
                    else -> true
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            localListUnavailable("CHECK_FAILED")
        }
    }

    suspend fun hasCurrentCoreReceipt(matrixClient: MatrixClient): Boolean = runCatching {
        val policy = dataProtectionPolicyProvider.current()
        if (policy.source != DataProtectionPolicySource.VERIFIED_REMOTE) return@runCatching false
        val trust = deviceTrustService.current()
        val accountHash = accountIdHash(matrixClient.sessionId.value)
        val deviceHash = sha256Hex(matrixClient.deviceId.value)
        val checkpoint = accountReactivationScopeStore.current(accountHash, trust.installationId, deviceHash) ?: return@runCatching false
        val receipt = checkpoint.receipt ?: return@runCatching false
        checkpoint.state == AccountReactivationCheckpointState.COMMITTED && !checkpoint.pendingExplicitIdentityReset &&
            receipt.accountMobileGeneration > 0 && receipt.matchesCurrentSecurityState(
                accountHash,
                trust.installationId,
                deviceHash,
                trust.attestationVersion,
                policy.policy.version,
                deviceShareKeyService.descriptor().keyId,
            )
    }.getOrElse { failure ->
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        false
    }

    suspend fun activate(matrixClient: MatrixClient): Result<AccountActivationReceipt> = activationMutex.withLock {
        runCatching {
        val sync = io.element.android.libraries.androidutils.startup.StartupTrace.measureSuspend("Startup.core.sync") {
            managementControlService.synchronize()
        }
        requireEntryPrerequisite(sync.status == ManagementSyncStatus.SYNCHRONIZED, "ACTIVATION_DEVICE_SYNC")
        val policy = dataProtectionPolicyProvider.current()
        requireEntryPrerequisite(policy.source == DataProtectionPolicySource.VERIFIED_REMOTE, "ACTIVATION_POLICY_UNAVAILABLE")
        requireEntryPrerequisite(sync.acceptedPolicyVersion == null || sync.acceptedPolicyVersion == policy.policy.version,
            "ACTIVATION_POLICY_REVISION")

        val trust = deviceTrustService.current()
        requireEntryPrerequisite(trust.allowsLocalVaultAccess, "ACTIVATION_DEVICE_TRUST")
        val vaultId = deriveAccountVaultId(matrixClient.sessionId.value, matrixClient.deviceId.value)
        requireEntryPrerequisite(vaultKeyService.isUnlocked(vaultId), "ACTIVATION_VAULT_LOCKED")
        val shareKey = deviceShareKeyService.descriptor()
        requireEntryPrerequisite(shareKey.installationId == trust.installationId, "ACTIVATION_RECIPIENT_INSTALLATION")

        val accountHash = accountIdHash(matrixClient.sessionId.value)
        val matrixDeviceIdHash = sha256Hex(matrixClient.deviceId.value)
        val existing = accountReactivationScopeStore.current(
            accountIdHash = accountHash,
            installationId = trust.installationId,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        val explicitIdentityResetPending = existing?.pendingExplicitIdentityReset == true
        requireEntryPrerequisite(existing?.identityResetPreviousKeyHash == null, "ACTIVATION_IDENTITY_RESET_PENDING")

        // Normal reactivation proves the existing KMS/Matrix boundary before CORE_READY. An
        // explicit identity reset is the one legal exception to that ordering: the old boundary
        // is intentionally unavailable, so RESET_CONFIRMED must be committed first. The caller
        // then creates the new KMS profile boundary before HOME is released.
        // A signed candidate projection cannot appear in the account's active recipient list
        // until ownership commits. The server independently verifies Matrix identity, policy,
        // device admission and the registered recipient key at PREPARE and COMMIT.
        val candidateHandover = accountHash in sync.accountHandoverRequiredAccountHashes
        if (!explicitIdentityResetPending && !candidateHandover) {
            requireEntryPrerequisite(kmsAuthorizationProvider.isAvailable(), "ACTIVATION_KMS_UNAVAILABLE")
            requireEntryPrerequisite(matrixContentProtectionBinding.status() == MatrixContentProtectionStatus.READY,
                "ACTIVATION_CONTENT_MAPPING")
            val recipients = io.element.android.libraries.androidutils.startup.StartupTrace.measureSuspend("Startup.core.recipients") {
                deviceRecipientRegistry.approvedRecipients(accountHash)
            }
            val ownRecipient = recipients.recipients.singleOrNull { recipient ->
                recipient.installationId == trust.installationId && recipient.keyId == shareKey.keyId
            }
            requireEntryPrerequisite(ownRecipient != null, "ACTIVATION_RECIPIENT_MISSING")
            requireEntryPrerequisite(matrixContentProtectionBinding.recipientsApproved(setOf(accountHash), recipients.recipients),
                "ACTIVATION_RECIPIENT_PROJECTION")
        }

        existing?.receipt?.takeIf { receipt ->
            existing.state == AccountReactivationCheckpointState.COMMITTED &&
                receipt.accountMobileGeneration > 0 &&
                receipt.matchesCurrentSecurityState(
                    accountHash = accountHash,
                    installationId = trust.installationId,
                    matrixDeviceIdHash = matrixDeviceIdHash,
                    attestationVersion = trust.attestationVersion,
                    policyVersion = policy.policy.version,
                    recipientKeyId = shareKey.keyId,
                )
        }?.let { return@runCatching it }

        val resumable = existing?.takeIf { checkpoint ->
            when (checkpoint.state) {
                AccountReactivationCheckpointState.ALLOCATED -> true
                // An expired local prepare may already have committed remotely. Only the
                // authority can distinguish a lost response from an uncommitted expiration.
                AccountReactivationCheckpointState.PREPARED -> true
                AccountReactivationCheckpointState.COMMITTED -> false
            }
        }
        var attempt = resumable?.attempt ?: accountReactivationScopeStore.nextAttempt(
            accountIdHash = accountHash,
            installationId = trust.installationId,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        fun requestForAttempt() = AccountReactivationRequest(
            accountIdHash = accountHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
            clientScopeId = attempt.clientScopeId,
            clientGeneration = attempt.clientGeneration,
            scopeStartedAtEpochMillis = attempt.scopeStartedAtEpochMillis,
            idempotencyKey = attempt.idempotencyKey,
            explicitIdentityResetConfirmed = explicitIdentityResetPending,
        )
        suspend fun commitAttempt(reusePrepared: Boolean): AccountActivationReceipt {
            val request = requestForAttempt()
            val prepared = resumable
                ?.takeIf { reusePrepared && it.state == AccountReactivationCheckpointState.PREPARED }
                ?.prepared
                ?: managementControlService.prepareAccountReactivation(request).also { prepared ->
                    accountReactivationScopeStore.markPrepared(attempt, prepared)
                }
            return io.element.android.libraries.androidutils.startup.StartupTrace.measureSuspend("Startup.core.commit") {
                managementControlService.commitAccountReactivation(request, prepared)
            }
        }
        val receipt = try {
            commitAttempt(reusePrepared = true)
        } catch (_: AccountActivationExpired) {
            // A definitive uncommitted expiration permits one new durable attempt. Network
            // failures and unknown outcomes retain the original identity for the next retry.
            attempt = accountReactivationScopeStore.nextAttempt(accountHash, trust.installationId, matrixDeviceIdHash)
            commitAttempt(reusePrepared = false)
        }
        // A valid server commit and its local checkpoint form one UI-visible transaction. Finish
        // the small encrypted local write even if navigation refreshes immediately after recovery.
        // Security prerequisites are still rechecked before this receipt is reused.
        withContext(NonCancellable) {
            accountReactivationScopeStore.markCommitted(attempt, receipt)
        }
        receipt
    }
    }

    private fun AccountActivationReceipt.matchesCurrentSecurityState(
        accountHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        attestationVersion: Long,
        policyVersion: Long,
        recipientKeyId: String,
    ): Boolean =
        accountIdHash == accountHash &&
            this.installationId == installationId &&
            this.matrixDeviceIdHash == matrixDeviceIdHash &&
            this.attestationVersion == attestationVersion &&
            this.policyVersion == policyVersion &&
            this.recipientKeyId == recipientKeyId

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
