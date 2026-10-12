/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

/** Security cutover steps for plaintext left by versions which predate the encrypted media pipeline. */
enum class PlaintextMigrationStep {
    SECURE_MEDIA_CUTOVER,
    PURGED_RETIRED_MEDIA_CACHE,
    PURGED_RETIRED_VOICE_CACHE,
    PURGED_RETIRED_NOTIFICATION_CACHE,
    PURGED_UPLOAD_STAGING,
    PURGED_EDITING_STAGING,
    PURGED_VOICE_RECORDING_STAGING,
    PURGED_LEGACY_ROOT_CACHE_FILES,
    PURGED_LEGACY_LOGS,
    PURGED_LEGACY_SCREENSHOT,
    MAINTAINED_SECURE_STORE,
    COMPLETE,
}

data class PlaintextMigrationReport(
    val migrationVersion: Int,
    val completedSteps: Set<PlaintextMigrationStep>,
    val attemptCount: Int,
    val unreadableJournalEntries: Int,
    val retryRequired: Boolean,
    val errorCode: String? = null,
)

/**
 * Removes retired plaintext which cannot be mapped safely to an account Vault and seals the secure-media cutover.
 *
 * The operation is durable and idempotent. A successful return means every registered legacy plaintext target is
 * absent; it does not claim that upload/edit/recording code can no longer create new plaintext during this process.
 */
interface PlaintextMigrationCoordinator {
    suspend fun migrate(): PlaintextMigrationReport
}
