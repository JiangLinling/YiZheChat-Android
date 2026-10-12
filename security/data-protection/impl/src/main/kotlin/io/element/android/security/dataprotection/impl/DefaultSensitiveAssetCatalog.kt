/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.security.dataprotection.api.ProtectionReadiness
import io.element.android.security.dataprotection.api.ProtectionStage
import io.element.android.security.dataprotection.api.SensitiveAssetCatalog
import io.element.android.security.dataprotection.api.SensitiveAssetDescriptor
import io.element.android.security.dataprotection.api.SensitiveAssetOwner
import io.element.android.security.dataprotection.api.SensitiveCarrier
import io.element.android.security.dataprotection.api.SensitiveDataKind

@ContributesBinding(AppScope::class)
class DefaultSensitiveAssetCatalog : SensitiveAssetCatalog {
    override fun all(): List<SensitiveAssetDescriptor> = assets
}

private val assets = listOf(
    asset(
        "L-001",
        "Session credentials and Matrix passphrase",
        SensitiveDataKind.CREDENTIAL,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Per-account credential key and cryptographic logout",
        SensitiveCarrier.APP_DATABASE
    ),
    asset(
        "L-002",
        "Matrix state and crypto store",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Vault-wrapped Matrix Store key",
        SensitiveCarrier.APP_DATABASE,
        SensitiveCarrier.APP_PRIVATE_FILE
    ),
    asset(
        "L-003",
        "Matrix text events and relations",
        SensitiveDataKind.MESSAGE_TEXT,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P3_CONTENT_KMS,
        "Local database sanitation plus inner content encryption",
        SensitiveCarrier.APP_DATABASE
    ),
    asset(
        "L-004",
        "Image and media cache",
        SensitiveDataKind.IMAGE,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Encrypted object store and streaming decode",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-005",
        "Voice message cache",
        SensitiveDataKind.VOICE_MESSAGE,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Encrypted audio container and streaming playback",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-006",
        "Notification media cache",
        SensitiveDataKind.NOTIFICATION,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Content-free notification or encrypted notification object",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-007",
        "Upload preprocessing and transcode files",
        SensitiveDataKind.ATTACHMENT,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Encrypted staging or bounded in-memory pipeline",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-008",
        "Image editing, crop, and camera temporary files",
        SensitiveDataKind.IMAGE,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Encrypted scratch space and one-time URI",
        SensitiveCarrier.APP_CACHE,
        SensitiveCarrier.APP_PRIVATE_FILE
    ),
    asset(
        "L-009",
        "Outgoing share temporary files",
        SensitiveDataKind.EXPORTED_COPY,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Pipe-backed one-time content provider and export policy",
        SensitiveCarrier.APP_CACHE,
        SensitiveCarrier.EXTERNAL_OR_OTHER_APP
    ),
    asset(
        "L-010",
        "Coil image disk cache",
        SensitiveDataKind.IMAGE,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Disable plain disk cache and use encrypted fetcher",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-011",
        "Video cover and player cache",
        SensitiveDataKind.VIDEO,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Chunked encrypted data source and independently keyed cover",
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-012",
        "Push history and account mapping",
        SensitiveDataKind.PUSH_METADATA,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Independent push key and content-free payload",
        SensitiveCarrier.APP_DATABASE
    ),
    asset(
        "L-013",
        "Local logs and crash attachments",
        SensitiveDataKind.LOG,
        ProtectionReadiness.PLAINTEXT_RISK,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Redacted encrypted structured logs with short retention",
        SensitiveCarrier.APP_PRIVATE_FILE
    ),
    asset(
        "L-014",
        "Database WAL, SHM, and deleted pages",
        SensitiveDataKind.DATABASE_RESIDUE,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Secure delete, truncate checkpoint, rotation, and key destruction",
        SensitiveCarrier.APP_DATABASE
    ),
    asset(
        "L-015",
        "Key envelopes and version metadata",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P2_HARDWARE_VAULT,
        "DRK to AVK to domain-key hierarchy",
        SensitiveCarrier.APP_PRIVATE_FILE,
        SensitiveCarrier.ANDROID_KEYSTORE
    ),
    asset(
        "L-016",
        "Matrix Key Backup recovery material",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P3_CONTENT_KMS,
        "Outer Megolm recovery only",
        SensitiveCarrier.APP_DATABASE,
        SensitiveCarrier.MATRIX_KEY_BACKUP
    ),
    asset(
        "L-017",
        "Location events and map cache",
        SensitiveDataKind.LOCATION,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Protect coordinates as message content and keep map cache user-agnostic",
        SensitiveCarrier.APP_DATABASE,
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "L-018",
        "Draft, reply, search, and recent state",
        SensitiveDataKind.DRAFT_OR_SEARCH,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Inventory every feature store and bind it to account deletion",
        SensitiveCarrier.APP_DATABASE,
        SensitiveCarrier.APP_CACHE
    ),
    asset(
        "M-001",
        "Decrypted text and Compose state",
        SensitiveDataKind.MEMORY_PLAINTEXT,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Minimize lifetime and purge on lock/account switch",
        SensitiveCarrier.APP_MEMORY
    ),
    asset(
        "M-002",
        "Bitmap, image memory cache, and GPU textures",
        SensitiveDataKind.IMAGE,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Clear cache on lock and release render references",
        SensitiveCarrier.APP_MEMORY,
        SensitiveCarrier.ANDROID_SYSTEM
    ),
    asset(
        "M-003",
        "Audio PCM, encoded frame, and player buffers",
        SensitiveDataKind.VOICE_MESSAGE,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Release buffers and forbid debug PCM dumps",
        SensitiveCarrier.APP_MEMORY,
        SensitiveCarrier.ANDROID_SYSTEM
    ),
    asset(
        "M-004",
        "Video decoder, Surface, and codec buffers",
        SensitiveDataKind.VIDEO,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Release buffers and forbid frame dumps",
        SensitiveCarrier.APP_MEMORY,
        SensitiveCarrier.ANDROID_SYSTEM
    ),
    asset(
        "M-005",
        "Live voice and video call media",
        SensitiveDataKind.CALL_MEDIA,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Audit real call encryption and keep recording disabled",
        SensitiveCarrier.APP_MEMORY,
        SensitiveCarrier.CALL_INFRASTRUCTURE
    ),
    asset(
        "M-006",
        "Call metadata",
        SensitiveDataKind.CALL_METADATA,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Encrypted retention policy without media content in logs",
        SensitiveCarrier.APP_DATABASE,
        SensitiveCarrier.MATRIX_EVENT_STORE
    ),
    asset(
        "M-007",
        "Android lock-screen notification and history",
        SensitiveDataKind.NOTIFICATION,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Content hidden by default",
        SensitiveCarrier.ANDROID_SYSTEM
    ),
    asset(
        "M-008",
        "Android clipboard",
        SensitiveDataKind.EXPORTED_COPY,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Disable or mark sensitive and clear promptly",
        SensitiveCarrier.ANDROID_SYSTEM
    ),
    asset(
        "M-009",
        "Screenshot, recording, and recent-task thumbnail",
        SensitiveDataKind.EXPORTED_COPY,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Secure-window and managed-device policy",
        SensitiveCarrier.ANDROID_SYSTEM,
        SensitiveCarrier.EXTERNAL_OR_OTHER_APP
    ),
    asset(
        "M-010",
        "Downloads, gallery, and third-party share copies",
        SensitiveDataKind.EXPORTED_COPY,
        ProtectionReadiness.SYSTEM_BOUNDARY,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Deny export by default and make authorization explicit",
        SensitiveCarrier.EXTERNAL_OR_OTHER_APP
    ),
    asset(
        "M-011",
        "In-process Vault, CEK, and KMS share",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.PLANNED,
        ProtectionStage.P3_CONTENT_KMS,
        "Do not expose raw Vault keys and minimize key lifetime",
        SensitiveCarrier.APP_MEMORY,
        SensitiveCarrier.ANDROID_KEYSTORE
    ),
    asset(
        "S-001",
        "Matrix event JSON and relations",
        SensitiveDataKind.SERVER_CIPHERTEXT,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P3_CONTENT_KMS,
        "Versioned inner ciphertext and server retention",
        SensitiveCarrier.MATRIX_EVENT_STORE
    ),
    asset(
        "S-002",
        "Matrix media and thumbnails",
        SensitiveDataKind.SERVER_CIPHERTEXT,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P3_CONTENT_KMS,
        "Per-object inner CEK separated from media ciphertext",
        SensitiveCarrier.MATRIX_MEDIA_REPOSITORY
    ),
    asset(
        "S-003",
        "Matrix Room Key Backup",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.EXISTING_ENCRYPTION,
        ProtectionStage.P3_CONTENT_KMS,
        "Outer Megolm keys only",
        SensitiveCarrier.MATRIX_KEY_BACKUP
    ),
    asset(
        "S-004",
        "Matrix and media backups",
        SensitiveDataKind.SERVER_CIPHERTEXT,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Ciphertext-only backup with revocation tombstone replay",
        SensitiveCarrier.SERVER_BACKUP
    ),
    asset(
        "S-005",
        "ntfy topic, endpoint, and push payload",
        SensitiveDataKind.PUSH_METADATA,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Wake-only payload without message content or key shares",
        SensitiveCarrier.PUSH_PROVIDER
    ),
    asset(
        "S-006",
        "TURN and SFU live call packets",
        SensitiveDataKind.CALL_MEDIA,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Audit media encryption, logging, memory, and recording",
        SensitiveCarrier.CALL_INFRASTRUCTURE
    ),
    asset(
        "S-007",
        "Server call recordings",
        SensitiveDataKind.CALL_MEDIA,
        ProtectionReadiness.PLANNED,
        ProtectionStage.DLP_USAGE_CONTROLS,
        "Disabled by default or independently keyed and retained",
        SensitiveCarrier.CALL_INFRASTRUCTURE,
        SensitiveCarrier.SERVER_BACKUP
    ),
    asset(
        "S-008",
        "KMS share",
        SensitiveDataKind.KEY_MATERIAL,
        ProtectionReadiness.PLANNED,
        ProtectionStage.P3_CONTENT_KMS,
        "Separate from device share and destroy at expiry",
        SensitiveCarrier.KMS
    ),
    asset(
        "S-009",
        "Device attestation and trust state",
        SensitiveDataKind.DEVICE_ATTESTATION,
        ProtectionReadiness.PLANNED,
        ProtectionStage.P2_HARDWARE_VAULT,
        "Challenge-based server verification and revocation state",
        SensitiveCarrier.KMS
    ),
    asset(
        "S-010",
        "Offline license",
        SensitiveDataKind.OFFLINE_LEASE,
        ProtectionReadiness.PLANNED,
        ProtectionStage.P3_CONTENT_KMS,
        "Device-bound short lease invalidated by restart or rollback",
        SensitiveCarrier.KMS,
        SensitiveCarrier.APP_PRIVATE_FILE
    ),
    asset(
        "S-011",
        "Deletion and security audit",
        SensitiveDataKind.AUDIT_METADATA,
        ProtectionReadiness.PLANNED,
        ProtectionStage.P1_PLAINTEXT_RESIDUALS,
        "Non-content operation audit",
        SensitiveCarrier.AUDIT_STORE
    ),
    asset(
        "S-012",
        "KMS, Matrix, and proxy access logs",
        SensitiveDataKind.LOG,
        ProtectionReadiness.RUNTIME_AUDIT_REQUIRED,
        ProtectionStage.P3_CONTENT_KMS,
        "Redacted short-retention logs without content or shares",
        SensitiveCarrier.AUDIT_STORE,
        SensitiveCarrier.SERVER_BACKUP
    ),
)

private fun asset(
    id: String,
    displayName: String,
    kind: SensitiveDataKind,
    readiness: ProtectionReadiness,
    stage: ProtectionStage,
    targetControl: String,
    vararg carriers: SensitiveCarrier,
) = SensitiveAssetDescriptor(
    id = id,
    displayName = displayName,
    kind = kind,
    carriers = carriers.toSet(),
    currentReadiness = readiness,
    targetStage = stage,
    owner = ownerFor(id),
    targetControl = targetControl,
)

private fun ownerFor(id: String): SensitiveAssetOwner = when (id) {
    "L-001" -> SensitiveAssetOwner.SESSION_STORAGE
    "L-002", "L-003", "L-016", "S-001", "S-003", "S-004" -> SensitiveAssetOwner.MATRIX_CORE
    "L-004", "L-010", "L-011", "M-002", "M-004", "S-002" -> SensitiveAssetOwner.MEDIA_PIPELINE
    "L-005", "M-003" -> SensitiveAssetOwner.VOICE_PIPELINE
    "L-007", "L-008", "L-009", "M-010" -> SensitiveAssetOwner.UPLOAD_AND_SHARE
    "L-006", "L-012", "M-007", "S-005" -> SensitiveAssetOwner.PUSH_AND_NOTIFICATIONS
    "L-013", "S-011", "S-012" -> SensitiveAssetOwner.LOGGING_AND_AUDIT
    "L-014" -> SensitiveAssetOwner.DATABASE_MAINTENANCE
    "L-015" -> SensitiveAssetOwner.SECURE_STORAGE
    "M-011", "S-008", "S-010" -> SensitiveAssetOwner.CONTENT_PROTECTION
    "L-017" -> SensitiveAssetOwner.LOCATION
    "M-005", "M-006", "S-006", "S-007" -> SensitiveAssetOwner.CALLS
    "L-018", "M-001" -> SensitiveAssetOwner.UI_MEMORY
    "M-008", "M-009" -> SensitiveAssetOwner.PLATFORM_DLP
    "S-009" -> SensitiveAssetOwner.SERVER_PLATFORM
    else -> error("Sensitive asset $id has no accountable owner.")
}
