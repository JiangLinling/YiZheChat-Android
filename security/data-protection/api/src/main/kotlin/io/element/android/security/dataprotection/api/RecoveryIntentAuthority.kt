package io.element.android.security.dataprotection.api

enum class RecoveryHistoryMode { STANDARD, CONFIDENTIAL }

data class RecoveryIntentScope(val accountIdHash: String, val installationId: String, val matrixDeviceIdHash: String) {
    init {
        require(accountIdHash.matches(Regex("[a-f0-9]{64}")) && matrixDeviceIdHash.matches(Regex("[a-f0-9]{64}")))
        require(installationId.matches(Regex("[A-Za-z0-9_.:-]{8,256}")))
    }
}

data class RecoveryIntentRecord(
    val scope: RecoveryIntentScope,
    val operationId: String,
    val idempotencyKey: String,
    val deadlineEpochMillis: Long,
    val revision: Long,
    val identityGeneration: String? = null,
    val mobileGeneration: Long? = null,
    val recoveryId: String? = null,
    val recoveryExpiresAtEpochMillis: Long? = null,
    val recoveryMode: RecoveryHistoryMode? = null,
) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9_.:-]{16,256}")) && idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{16,256}")) && revision > 0 && deadlineEpochMillis > 0)
        require((identityGeneration == null) == (mobileGeneration == null))
        require(identityGeneration == null || identityGeneration.matches(Regex("[a-f0-9]{64}")))
        require(mobileGeneration == null || mobileGeneration > 0)
        require((recoveryId == null) == (recoveryExpiresAtEpochMillis == null))
        require(recoveryId == null || recoveryId.matches(Regex("[A-Za-z0-9_.:-]{8,256}")))
        require(recoveryExpiresAtEpochMillis == null || recoveryExpiresAtEpochMillis > 0)
    }
    fun request() = LifecycleOperationRequest(operationId, FullAppLifecycleId.LC_08, "recovery_key_entered",
        idempotencyKey, scope.accountIdHash, scope.matrixDeviceIdHash, deadlineEpochMillis = deadlineEpochMillis, recoveryMode = recoveryMode)
}

/** These are nonce-bound, signature-verified server observations, never local authorization. */
data class RecoveryOperationContext(
    val operationId: String, val scope: RecoveryIntentScope, val trigger: String,
    val identityGeneration: String, val mobileGeneration: Long,
    val currentIdentityGeneration: String, val currentMobileGeneration: Long,
    val scopeValid: Boolean, val rootValid: Boolean,
    val status: LifecycleOperationStatus, val terminalReceiptId: String?,
    val deadlineEpochMillis: Long, val serverTimeEpochMillis: Long,
    val matrixComplete: Boolean, val recoveryId: String?, val recoveryExpiresAtEpochMillis: Long?,
    val terminalResultCode: String? = null,
    val idempotencyKeySha256: String,
    val currentRootValid: Boolean,
    val recoveryMode: RecoveryHistoryMode? = null,
)

/** A fresh signed observation of this exact draft. Absence never manufactures a terminal server operation. */
data class RecoveryIntentResolution(
    val operationId: String, val scope: RecoveryIntentScope, val idempotencyKeySha256: String,
    val deadlineEpochMillis: Long, val serverTimeEpochMillis: Long, val admitted: Boolean,
    val recoveryMode: RecoveryHistoryMode? = null,
)

interface RecoveryIntentJournal {
    fun clearAccount(accountIdHash: String): Boolean
    fun read(scope: RecoveryIntentScope): RecoveryIntentRecord?
    /** Must atomically commit before reporting success. No keys, recovery material or bearer tokens. */
    fun compareAndSet(scope: RecoveryIntentScope, expectedRevision: Long?, next: RecoveryIntentRecord?): Boolean
}

interface RecoveryIntentAuthority {
    /** Retire only the exact signed expired attempt before converging an already completed identity reset. */
    suspend fun retireExpiredForIdentityReset(scope: RecoveryIntentScope) {
        throw RecoveryIntentRejected("RECOVERY_RESET_RETIREMENT_UNSUPPORTED")
    }
    suspend fun restore(scope: RecoveryIntentScope): RecoveryIntentRecord?
    suspend fun admit(scope: RecoveryIntentScope): RecoveryIntentRecord
    suspend fun admit(scope: RecoveryIntentScope, mode: RecoveryHistoryMode): RecoveryIntentRecord {
        check(mode == RecoveryHistoryMode.STANDARD) { "CONFIDENTIAL_RECOVERY_UNSUPPORTED" }
        return admit(scope)
    }
    suspend fun verify(record: RecoveryIntentRecord): RecoveryOperationContext
    suspend fun waiting(record: RecoveryIntentRecord, recoveryId: String, expiresAtEpochMillis: Long): RecoveryIntentRecord
    suspend fun completed(record: RecoveryIntentRecord)
}

class RecoveryIntentRejected(val reasonCode: String) : SecurityException(reasonCode)
