/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

enum class FullAppLifecycleId(val wireValue: String) {
    LC_01("LC-01"),
    LC_02("LC-02"),
    LC_03("LC-03"),
    LC_04("LC-04"),
    LC_05("LC-05"),
    LC_06("LC-06"),
    LC_07("LC-07"),
    LC_08("LC-08"),
    LC_09("LC-09"),
    LC_10("LC-10"),
    LC_11("LC-11"),
    LC_12("LC-12"),
    LC_13("LC-13"),
    LC_14("LC-14"),
    LC_15("LC-15"),
    LC_16("LC-16"),
    LC_17("LC-17"),
    LC_18("LC-18"),
    LC_19("LC-19"),
    LC_20("LC-20"),
    LC_21("LC-21"),
    LC_22("LC-22"),
    LC_23("LC-23"),
    LC_24("LC-24"),
    LC_25("LC-25"),
    ;

    companion object {
        fun fromWireValue(value: String): FullAppLifecycleId =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported full-app lifecycle id.")
    }
}

enum class LifecycleComponent {
    ANDROID_FRONTEND,
    ANDROID_SERVICE,
    MATRIX,
    CONTROL_PLANE,
    DATABASE,
    KMS,
    PUSH,
    RTC,
    OFFICE_PREVIEW,
}

enum class LifecyclePhase {
    INTENT_PERSISTED,
    PREPARED,
    EXTERNAL_COMMITTED,
    PROJECTED,
    CLIENT_APPLIED,
    RECEIPTED,
}

enum class AppOperationalStatus {
    UNKNOWN,
    DISCOVERING,
    READY,
    LIMITED,
    WAITING_EXTERNAL,
    RETRYABLE_FAILURE,
    BLOCKED,
    TERMINATING,
    TERMINATED,
}

enum class LifecycleOperationStatus {
    PENDING,
    RUNNING,
    WAITING_EXTERNAL,
    RETRYABLE_FAILURE,
    BLOCKED,
    SUCCEEDED,
    CANCELLED,
    FAILED_FINAL,
    TERMINATED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, CANCELLED, FAILED_FINAL, TERMINATED)
}

enum class LifecycleStepStatus {
    PENDING,
    RUNNING,
    WAITING_EXTERNAL,
    COMPLETE,
    RETRY,
    TERMINATED,
}

enum class LifecycleCompensationStatus {
    NOT_REQUIRED,
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED,
}

data class LifecycleSubjectHashes(
    val accountIdHash: String? = null,
    val installationId: String? = null,
    val matrixDeviceIdHash: String? = null,
    val roomIdHash: String? = null,
    val eventIdHash: String? = null,
) {
    init {
        listOfNotNull(accountIdHash, matrixDeviceIdHash, roomIdHash, eventIdHash).forEach {
            require(SHA_256_REGEX.matches(it)) { "Lifecycle identity hashes must be lowercase SHA-256." }
        }
        installationId?.let {
            require(it.length in 16..256) { "Lifecycle installation identity length is invalid." }
        }
    }
}

data class AuthorityProjection(
    val authority: String,
    val authorityRevision: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val observedAtEpochMillis: Long,
    val status: AppOperationalStatus,
    val reasonCode: String? = null,
    val receiptReference: String? = null,
) {
    init {
        require(authority.isNotBlank()) { "Lifecycle authority is required." }
        require(authorityRevision.isNotBlank()) { "Lifecycle authority revision is required." }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Authority projection must expire after issue time." }
        require(observedAtEpochMillis >= issuedAtEpochMillis) { "Authority projection cannot be observed before issue time." }
        require(reasonCode == null || REASON_CODE_REGEX.matches(reasonCode)) { "Lifecycle reason code is invalid." }
    }
}

data class LifecycleStep(
    val stepId: String,
    val component: LifecycleComponent,
    val required: Boolean = true,
    val status: LifecycleStepStatus,
    val attemptCount: Int,
    val authorityRevision: String? = null,
    val resultCode: String? = null,
    val resultReference: String? = null,
    val receiptReference: String? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(stepId.length in 8..256) { "Lifecycle step identity length is invalid." }
        require(attemptCount >= 0) { "Lifecycle step attempt count cannot be negative." }
        require(resultCode == null || REASON_CODE_REGEX.matches(resultCode)) { "Lifecycle step result code is invalid." }
        if (required && status in setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED)) {
            require(!receiptReference.isNullOrBlank()) { "Required terminal lifecycle steps need a receipt." }
        }
    }
}

data class LifecycleOperation(
    val schemaVersion: Int = FULL_APP_LIFECYCLE_SCHEMA_VERSION,
    val catalogId: String = FULL_APP_LIFECYCLE_CATALOG_ID,
    val operationId: String,
    val lifecycleId: FullAppLifecycleId,
    val idempotencyKey: String,
    val subjects: LifecycleSubjectHashes,
    val status: LifecycleOperationStatus,
    val currentPhase: LifecyclePhase,
    val authorityRevisions: Map<String, String> = emptyMap(),
    val steps: List<LifecycleStep> = emptyList(),
    val deadlineEpochMillis: Long,
    val nextReconcileAtEpochMillis: Long? = null,
    val compensationStatus: LifecycleCompensationStatus = LifecycleCompensationStatus.NOT_REQUIRED,
    val terminalReceiptId: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(schemaVersion == FULL_APP_LIFECYCLE_SCHEMA_VERSION) { "Unsupported full-app lifecycle schema." }
        require(catalogId == FULL_APP_LIFECYCLE_CATALOG_ID) { "Unsupported full-app lifecycle catalog." }
        require(operationId.length in 16..256) { "Lifecycle operation identity length is invalid." }
        require(idempotencyKey.length in 16..256) { "Lifecycle idempotency key length is invalid." }
        require(deadlineEpochMillis > createdAtEpochMillis) { "Lifecycle deadline must follow creation." }
        require(updatedAtEpochMillis >= createdAtEpochMillis) { "Lifecycle update cannot precede creation." }
        require(steps.map { it.stepId }.distinct().size == steps.size) { "Lifecycle step identities must be unique." }
        require(status.isTerminal == !terminalReceiptId.isNullOrBlank()) {
            "Exactly terminal lifecycle operations must have a terminal receipt."
        }
        if (status == LifecycleOperationStatus.SUCCEEDED) {
            require(steps.filter { it.required }.all { it.status in setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED) }) {
                "A successful lifecycle operation cannot contain incomplete required steps."
            }
        }
    }
}

data class ComponentOperationalState(
    val status: AppOperationalStatus,
    val authority: String,
    val authorityRevision: String,
    val observedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val reasonCode: String? = null,
    val operationId: String? = null,
    val receiptReference: String? = null,
) {
    init {
        require(authority.isNotBlank()) { "Component authority is required." }
        require(authorityRevision.isNotBlank()) { "Component authority revision is required." }
        require(expiresAtEpochMillis > observedAtEpochMillis) { "Component state must expire after observation." }
        require(reasonCode == null || REASON_CODE_REGEX.matches(reasonCode)) { "Component reason code is invalid." }
    }
}

// Existing signed snapshot acceptance bound; never applied to an expiry or lease deadline.
const val APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS = 5 * 60 * 1_000L

data class AppOperationalSnapshot(
    val schemaVersion: Int = FULL_APP_LIFECYCLE_SCHEMA_VERSION,
    val catalogId: String = FULL_APP_LIFECYCLE_CATALOG_ID,
    val accountIdHash: String,
    val installationId: String,
    val matrixDeviceIdHash: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val overallStatus: AppOperationalStatus,
    val components: Map<String, ComponentOperationalState>,
    val activeOperationIds: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val signingKeyId: String,
    val signatureBase64: String,
    // Original verified wire payload, including unknown signed fields; never a second authority.
    val verifiedSignedJson: String? = null,
) {
    init {
        LifecycleSubjectHashes(
            accountIdHash = accountIdHash,
            installationId = installationId,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        require(schemaVersion == FULL_APP_LIFECYCLE_SCHEMA_VERSION) { "Unsupported full-app lifecycle schema." }
        require(catalogId == FULL_APP_LIFECYCLE_CATALOG_ID) { "Unsupported full-app lifecycle catalog." }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Operational snapshot must expire after issue time." }
        require(components.keys == REQUIRED_OPERATIONAL_COMPONENTS) { "Operational snapshot components are incomplete." }
        require(signatureBase64.length >= 16) { "Operational snapshot signature is missing." }
        if (overallStatus == AppOperationalStatus.READY) {
            require(components.values.none { it.status in NON_READY_COMPONENT_STATUSES }) {
                "READY snapshot contains a non-ready component."
            }
        }
    }

    /**
     * Accepts a small, caller-bounded future issue-time skew without extending the signed expiry.
     *
     * Server and handset clocks are independent. Rejecting a freshly signed snapshot merely because the
     * server clock is a few seconds ahead creates a permanent startup loop: every retry receives another
     * snapshot whose issue time is still slightly in the handset's future. Expiry remains strict so clock
     * tolerance never extends the authority window.
     */
    fun isUsableAt(
        nowEpochMillis: Long,
        maximumFutureIssueSkewMillis: Long = 0,
    ): Boolean {
        require(maximumFutureIssueSkewMillis >= 0) { "Operational snapshot clock skew must not be negative." }
        return issuedAtEpochMillis <= nowEpochMillis + maximumFutureIssueSkewMillis &&
            nowEpochMillis < expiresAtEpochMillis
    }
}

data class LifecycleReceipt(
    val schemaVersion: Int = FULL_APP_LIFECYCLE_SCHEMA_VERSION,
    val catalogId: String = FULL_APP_LIFECYCLE_CATALOG_ID,
    val receiptId: String,
    val operationId: String,
    val lifecycleId: FullAppLifecycleId,
    val subjects: LifecycleSubjectHashes,
    val terminalStatus: LifecycleOperationStatus,
    val authorityRevisions: Map<String, String>,
    val requiredStepReceipts: Map<String, String>,
    val policyVersion: Long,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val signatureBase64: String,
) {
    init {
        require(schemaVersion == FULL_APP_LIFECYCLE_SCHEMA_VERSION) { "Unsupported full-app lifecycle schema." }
        require(catalogId == FULL_APP_LIFECYCLE_CATALOG_ID) { "Unsupported full-app lifecycle catalog." }
        require(terminalStatus.isTerminal) { "Lifecycle receipt status must be terminal." }
        require(completedAtEpochMillis >= startedAtEpochMillis) { "Lifecycle receipt completion cannot precede start." }
        val mayHaveNoAppliedSteps = terminalStatus == LifecycleOperationStatus.FAILED_FINAL || terminalStatus == LifecycleOperationStatus.CANCELLED
        require((mayHaveNoAppliedSteps || requiredStepReceipts.isNotEmpty()) && requiredStepReceipts.all { it.key.isNotBlank() && it.value.isNotBlank() }) {
            "Lifecycle receipt must include required step receipts."
        }
        require(signatureBase64.length >= 16) { "Lifecycle receipt signature is missing." }
    }
}

const val FULL_APP_LIFECYCLE_SCHEMA_VERSION = 1
const val FULL_APP_LIFECYCLE_CATALOG_ID = "yizhechat-full-app-lifecycle-v1"

val REQUIRED_OPERATIONAL_COMPONENTS: Set<String> = setOf(
    "release",
    "access_session",
    "device_trust",
    "matrix",
    "kms",
    "push",
    "rtc",
    "recovery",
    "cleanup",
    "preview",
    "location",
)

private val NON_READY_COMPONENT_STATUSES = setOf(
    AppOperationalStatus.UNKNOWN,
    AppOperationalStatus.DISCOVERING,
    AppOperationalStatus.WAITING_EXTERNAL,
    AppOperationalStatus.RETRYABLE_FAILURE,
    AppOperationalStatus.BLOCKED,
    AppOperationalStatus.TERMINATING,
    AppOperationalStatus.TERMINATED,
)
private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
private val REASON_CODE_REGEX = Regex("^[A-Z0-9_]+$")
