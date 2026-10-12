/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

enum class ProtectionStage {
    P1_PLAINTEXT_RESIDUALS,
    P2_HARDWARE_VAULT,
    P3_CONTENT_KMS,
    DLP_USAGE_CONTROLS,
}

enum class SensitiveDataKind {
    CREDENTIAL,
    MESSAGE_TEXT,
    IMAGE,
    VOICE_MESSAGE,
    VIDEO,
    ATTACHMENT,
    LOCATION,
    DRAFT_OR_SEARCH,
    NOTIFICATION,
    LOG,
    DATABASE_RESIDUE,
    KEY_MATERIAL,
    PUSH_METADATA,
    CALL_MEDIA,
    CALL_METADATA,
    MEMORY_PLAINTEXT,
    EXPORTED_COPY,
    SERVER_CIPHERTEXT,
    DEVICE_ATTESTATION,
    OFFLINE_LEASE,
    AUDIT_METADATA,
}

enum class SensitiveCarrier {
    APP_DATABASE,
    APP_PRIVATE_FILE,
    APP_CACHE,
    APP_MEMORY,
    ANDROID_KEYSTORE,
    ANDROID_SYSTEM,
    EXTERNAL_OR_OTHER_APP,
    MATRIX_EVENT_STORE,
    MATRIX_MEDIA_REPOSITORY,
    MATRIX_KEY_BACKUP,
    PUSH_PROVIDER,
    CALL_INFRASTRUCTURE,
    SERVER_BACKUP,
    KMS,
    AUDIT_STORE,
}

enum class ProtectionReadiness {
    EXISTING_ENCRYPTION,
    PLAINTEXT_RISK,
    SYSTEM_BOUNDARY,
    RUNTIME_AUDIT_REQUIRED,
    PLANNED,
}

enum class SensitiveAssetOwner {
    SESSION_STORAGE,
    MATRIX_CORE,
    MEDIA_PIPELINE,
    VOICE_PIPELINE,
    UPLOAD_AND_SHARE,
    PUSH_AND_NOTIFICATIONS,
    LOGGING_AND_AUDIT,
    DATABASE_MAINTENANCE,
    SECURE_STORAGE,
    CONTENT_PROTECTION,
    LOCATION,
    CALLS,
    UI_MEMORY,
    PLATFORM_DLP,
    SERVER_PLATFORM,
}

data class SensitiveAssetDescriptor(
    val id: String,
    val displayName: String,
    val kind: SensitiveDataKind,
    val carriers: Set<SensitiveCarrier>,
    val currentReadiness: ProtectionReadiness,
    val targetStage: ProtectionStage,
    val owner: SensitiveAssetOwner,
    val targetControl: String,
) {
    init {
        require(id.matches(Regex("[LMS]-[0-9]{3}"))) { "Sensitive asset id must use the documented L/M/S-000 format." }
        require(displayName.isNotBlank()) { "Sensitive asset display name must not be blank." }
        require(carriers.isNotEmpty()) { "Sensitive asset must have at least one carrier." }
        require(targetControl.isNotBlank()) { "Sensitive asset target control must not be blank." }
    }
}

enum class NotificationExposure {
    HIDDEN,
    SENDER_ONLY,
    CONTENT_ALLOWED,
}

enum class OfflineAccessMode {
    ONLINE_REQUIRED,
    BOUNDED_OFFLINE,
}

enum class HardwareTrustRequirement {
    NONE,
    TEE_OR_STRONGBOX,
    STRONGBOX,
}

enum class ScreenCapturePolicy {
    BLOCK,
    ALLOW,
}

data class DataProtectionPolicyV1(
    val policyId: String,
    val version: Long,
    val issuedAtEpochMillis: Long,
    val effectiveAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val contentRetentionMillis: Long,
    val maximumOfflineMillis: Long,
    val offlineAccessMode: OfflineAccessMode,
    val notificationExposure: NotificationExposure,
    val hardwareTrustRequirement: HardwareTrustRequirement,
    val allowContentExport: Boolean,
    val allowClipboard: Boolean,
    val allowExternalShare: Boolean,
    val screenCapturePolicy: ScreenCapturePolicy,
    /** Signed-policy gate for group receipts; direct messages use cumulative Matrix receipts by default. */
    val allowGroupReadReceipts: Boolean = false,
    /** A signed production policy may only make the content-envelope requirement stricter, never disable it. */
    val requireContentEnvelope: Boolean = true,
    /** RTC remains unavailable until a separately verified media E2EE implementation is released. */
    val allowRtc: Boolean = false,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported data protection policy schema version." }
        require(policyId.isNotBlank()) { "Policy id must not be blank." }
        require(version > 0) { "Policy version must be positive." }
        require(issuedAtEpochMillis >= 0) { "Policy issue time must not be negative." }
        require(effectiveAtEpochMillis >= issuedAtEpochMillis) { "Policy cannot take effect before it is issued." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > effectiveAtEpochMillis) {
            "Policy expiry must be after its effective time."
        }
        require(contentRetentionMillis > 0) { "Content retention must be positive." }
        require(maximumOfflineMillis >= 0) { "Maximum offline duration must not be negative." }
        require(maximumOfflineMillis <= contentRetentionMillis) { "Maximum offline duration must not exceed content retention." }
        require(offlineAccessMode == OfflineAccessMode.BOUNDED_OFFLINE || maximumOfflineMillis == 0L) {
            "Online-only policy must not carry an offline lease."
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

typealias DataProtectionPolicy = DataProtectionPolicyV1
