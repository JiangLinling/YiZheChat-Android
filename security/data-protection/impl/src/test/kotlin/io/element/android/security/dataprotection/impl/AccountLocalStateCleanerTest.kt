/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureMediaLease
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.SecureMediaLeaseRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AccountLocalStateCleanerTest {
    @Test
    fun `account cleanup revokes vault and clears every registered local residue`() {
        val context = RuntimeEnvironment.getApplication()
        val scope = aScope()
        val sessionPreferences = sessionPreferencesFile(context, scope.localSessionId).apply {
            parentFile?.mkdirs()
            writeText("sensitive-session-preferences")
        }
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString(friendProfileReferenceKey(scope.vaultId), "encrypted-reference")
            .putBoolean("friend_profiles_sync_pending_${scope.accountIdHash}", true)
            .commit()
        val officePreviewPreferences = context.getSharedPreferences("encrypted_office_preview_cache_v1", Context.MODE_PRIVATE)
        officePreviewPreferences
            .edit()
            .putString("target-document", """{"vault_id":"${scope.vaultId}","object_id":"target"}""")
            .putString("other-account-document", """{"vault_id":"other-account-vault","object_id":"other"}""")
            .commit()
        val plaintext = RecordingPlaintextCleaner()
        val leases = RecordingLeaseManager()

        val completed = DefaultAccountLocalStateCleaner(context, plaintext, leases, AtomicRecoveryIntentJournal(context)).clear(scope)

        assertThat(completed).isTrue()
        assertThat(leases.revokedVault).isEqualTo(AccountVaultId(scope.vaultId))
        assertThat(plaintext.targets).containsExactlyElementsIn(LegacyPlaintextTarget.entries)
        assertThat(sessionPreferences.exists()).isFalse()
        val defaultPreferences = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        assertThat(defaultPreferences.contains(friendProfileReferenceKey(scope.vaultId))).isFalse()
        assertThat(defaultPreferences.contains("friend_profiles_sync_pending_${scope.accountIdHash}")).isFalse()
        assertThat(officePreviewPreferences.contains("target-document")).isFalse()
        assertThat(officePreviewPreferences.contains("other-account-document")).isTrue()
    }

    @Test
    fun `old vault cleanup preserves preferences belonging to a replacement login`() {
        val context = RuntimeEnvironment.getApplication()
        val scope = aScope()
        val preferences = sessionPreferencesFile(context, scope.localSessionId).apply {
            parentFile?.mkdirs()
            writeText("replacement-session-preferences")
        }
        val plaintext = RecordingPlaintextCleaner()
        val leases = RecordingLeaseManager()
        assertThat(
            DefaultAccountLocalStateCleaner(context, plaintext, leases, AtomicRecoveryIntentJournal(context))
            .clear(scope, preserveAccountState = true)
        ).isTrue()
        assertThat(leases.revokedVault).isEqualTo(AccountVaultId(scope.vaultId))
        assertThat(plaintext.targets).isEmpty()
        assertThat(preferences.readText()).isEqualTo("replacement-session-preferences")
    }

    private fun aScope() = DeletionScope.Account(
        accountIdHash = "a".repeat(64),
        vaultId = "account-local-cleaner-vault",
        localSessionId = "@account-local-cleaner:yizhe.test",
        sessionFilePath = "unused",
        sessionCachePath = "unused",
    )

    private fun sessionPreferencesFile(context: Context, sessionId: String): File {
        val hash = MessageDigest.getInstance("SHA-512")
            .digest(sessionId.toByteArray())
            .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }
            .take(16)
        return File(context.filesDir, "datastore/session_${hash}_preferences.preferences_pb")
    }

    private fun friendProfileReferenceKey(vaultId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(vaultId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "friend_profiles_ref_$hash"
    }
}

private class RecordingPlaintextCleaner : LegacyPlaintextCleaner {
    val targets = mutableListOf<LegacyPlaintextTarget>()

    override fun clean(target: LegacyPlaintextTarget): LegacyPlaintextCleanupResult {
        targets += target
        return LegacyPlaintextCleanupResult(completed = true, deletedFiles = 0)
    }
}

private class RecordingLeaseManager : SecureMediaLeaseManager {
    var revokedVault: AccountVaultId? = null

    override fun issue(request: SecureMediaLeaseRequest): SecureMediaLease = error("Not used")

    override fun revokeVault(vaultId: AccountVaultId) {
        revokedVault = vaultId
    }
}
