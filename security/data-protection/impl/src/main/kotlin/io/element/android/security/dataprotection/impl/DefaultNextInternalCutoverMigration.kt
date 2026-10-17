/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
import io.element.android.security.dataprotection.api.NextInternalCutoverMigration
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import java.net.URI
import java.security.MessageDigest

/** One-time, journalled removal of old test identities before Next startup services can read them. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultNextInternalCutoverMigration(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val deletionCoordinator: DataDeletionCoordinator,
    private val managementState: SignedManagementStateRepository,
) : NextInternalCutoverMigration {
    override suspend fun migrate(nextHomeserverUrl: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val sessions = sessionStore.getAllSessions()
        val nextServerName = checkNotNull(URI(nextHomeserverUrl).host) { "Next homeserver URL is invalid." }
        val oldSessions = sessions.filterNot { session ->
            sameHomeserver(session.homeserverUrl, nextHomeserverUrl) ||
                session.userId.substringAfterLast(':', "").equals(nextServerName, ignoreCase = true)
        }
        val pending = preferences.getBoolean(PENDING, false)
        val hasOldAuthorityState = managementState.hasSignedStateFromAuthority(OLD_CONTROL_KEY_ID)
        if (preferences.getBoolean(COMPLETE, false)) {
            check(!pending && oldSessions.isEmpty() && !hasOldAuthorityState) {
                "Old internal authority state reappeared after cutover."
            }
            return
        }
        if (oldSessions.isEmpty() && !pending && !hasOldAuthorityState) return

        // A mixed installation cannot prove which authority owns the persisted policy and tombstone cursor.
        // Keep both identities closed instead of resetting security state for a valid Next account.
        check(sessions.size == oldSessions.size) { "Mixed internal and Next sessions require manual recovery." }
        check(preferences.edit().putBoolean(PENDING, true).commit()) { "Cutover intent was not persisted." }
        if (pending) {
            val recovery = deletionCoordinator.resumePending(coldStart = true)
            check(!recovery.retryRequired) { "Old internal deletion recovery requires retry." }
        }

        oldSessions.forEach { session ->
            val vaultId = deriveAccountVaultId(session.userId, session.deviceId)
            val identity = MessageDigest.getInstance("SHA-256")
                .digest("${session.userId}:${session.deviceId}".encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val report = deletionCoordinator.delete(
                DeletionRequest(
                    operationId = DeletionOperationId("next-internal-cutover-${identity.take(32)}"),
                    scope = DeletionScope.Account(
                        accountIdHash = accountIdHash(session.userId),
                        vaultId = vaultId.value,
                        localSessionId = session.userId,
                        sessionFilePath = session.sessionPath,
                        sessionCachePath = session.cachePath,
                    ),
                    reason = DeletionReason.MIGRATION_CLEANUP,
                    policyVersion = 0,
                    requestedAtEpochMillis = 0,
                    confirmedPreconditions = setOf(
                        DeletionStep.STOPPED_SYNC_AND_UI,
                        DeletionStep.PURGED_MEMORY,
                        DeletionStep.CLOSED_DATABASES,
                    ),
                )
            )
            check(report.isComplete) { "Old internal session deletion requires retry: ${report.errorCode}" }
        }
        check(sessionStore.getAllSessions().isEmpty()) { "An old internal session survived cutover cleanup." }
        managementState.resetForNextInternalCutover()
        check(preferences.edit().putBoolean(PENDING, false).putBoolean(COMPLETE, true).commit()) {
            "Cutover completion was not persisted."
        }
    }

    private fun sameHomeserver(stored: String, expected: String): Boolean {
        val left = runCatching { URI(stored) }.getOrNull() ?: return false
        val right = runCatching { URI(expected) }.getOrNull() ?: return false
        fun port(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        return left.scheme.equals("https", true) && right.scheme.equals("https", true) &&
            left.host != null && left.host.equals(right.host, true) && port(left) == port(right)
    }

    private companion object {
        const val PREFERENCES = "next_internal_cutover_migration_v1"
        const val PENDING = "pending"
        const val COMPLETE = "complete"
        // Public key ID read from the old internal-test control plane during the 2026-10-17 cutover.
        const val OLD_CONTROL_KEY_ID = "5e347cd737ddf238"
    }
}
