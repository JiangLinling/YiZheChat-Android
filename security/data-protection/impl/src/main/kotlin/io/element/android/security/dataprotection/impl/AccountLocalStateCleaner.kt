/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.securestorage.api.SecureMediaLeaseManager
import io.element.android.security.securestorage.api.AccountVaultId
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

interface AccountLocalStateCleaner {
    /** Clears non-session-directory state only after the caller has stopped sync/UI and closed account databases. */
    fun clear(scope: DeletionScope.Account, preserveAccountState: Boolean = false): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultAccountLocalStateCleaner(
    @ApplicationContext private val context: Context,
    private val plaintextCleaner: LegacyPlaintextCleaner,
    private val secureMediaLeaseManager: SecureMediaLeaseManager,
    private val recoveryIntentJournal: io.element.android.security.dataprotection.api.RecoveryIntentJournal,
) : AccountLocalStateCleaner {
    override fun clear(scope: DeletionScope.Account, preserveAccountState: Boolean): Boolean {
        secureMediaLeaseManager.revokeVault(AccountVaultId(scope.vaultId))
        if (!preserveAccountState) {
            if (LegacyPlaintextTarget.entries.any { !plaintextCleaner.clean(it).completed }) return false
            if (!recoveryIntentJournal.clearAccount(scope.accountIdHash)) return false
            if (!deleteSessionPreferences(scope.localSessionId)) return false
        }
        if (!clearAccountPreferenceReferences(scope, preserveAccountState)) return false
        if (!clearAttachmentSources(scope.vaultId)) return false
        return clearAccountOfficePreviewCache(scope.vaultId)
    }

    /** Revokes this account's external capabilities without revoking a grant shared by another account. */
    private fun clearAttachmentSources(vaultId: String): Boolean {
        val preferences = context.getSharedPreferences("encrypted_attachment_sources_v1", Context.MODE_PRIVATE)
        val entries = preferences.all.mapValues { (_, raw) -> JSONObject(raw as String) }
        val owned = entries.filterValues { it.getString("vault_id") == vaultId }
        if (owned.isEmpty()) return true
        fun hashes(entry: JSONObject): Set<String> {
            val array = entry.getJSONArray("uri_hashes")
            return (0 until array.length()).map { array.getString(it) }.toSet()
        }
        val shared = entries.filterKeys { it !in owned }.values.flatMap(::hashes).toSet()
        val releasing = owned.values.flatMap(::hashes).toSet() - shared
        context.contentResolver.persistedUriPermissions.filter { grant ->
            val hash = MessageDigest.getInstance(SHA_256).digest(grant.uri.toString().encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            hash in releasing
        }.forEach { grant ->
            context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val editor = preferences.edit()
        owned.keys.forEach(editor::remove)
        return editor.commit()
    }

    private fun deleteSessionPreferences(localSessionId: String): Boolean {
        val hashedSessionId = MessageDigest.getInstance(SHA_512)
            .digest(localSessionId.toByteArray())
            .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }
            .take(16)
        val dataStoreRoot = File(context.filesDir, DATASTORE_DIRECTORY).canonicalFile
        val storeFile = File(dataStoreRoot, "session_${hashedSessionId}_preferences.preferences_pb").canonicalFile
        require(storeFile.parentFile == dataStoreRoot) { "Session preference path is outside its managed root." }
        return deleteIfPresent(storeFile) && deleteIfPresent(File(storeFile.path + BACKUP_SUFFIX))
    }

    private fun clearAccountPreferenceReferences(scope: DeletionScope.Account, preserveAccountState: Boolean): Boolean {
        val vaultHash = MessageDigest.getInstance(SHA_256)
            .digest(scope.vaultId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            .edit()
            .remove("friend_profiles_ref_$vaultHash")
            .apply { if (!preserveAccountState) remove("friend_profiles_sync_pending_${scope.accountIdHash}") }
            .commit()
    }

    private fun clearAccountOfficePreviewCache(vaultId: String): Boolean {
        val preferences = context.getSharedPreferences(OFFICE_PREVIEW_CACHE_NAME, Context.MODE_PRIVATE)
        val keys = preferences.all.mapNotNull { (key, rawValue) ->
            val encodedReference = rawValue as? String ?: return@mapNotNull null
            val entryVaultId = runCatching { JSONObject(encodedReference).optString(VAULT_ID_FIELD) }.getOrNull()
            key.takeIf { entryVaultId == vaultId }
        }
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()

    private companion object {
        const val SHA_256 = "SHA-256"
        const val SHA_512 = "SHA-512"
        const val DATASTORE_DIRECTORY = "datastore"
        const val BACKUP_SUFFIX = ".bak"
        const val OFFICE_PREVIEW_CACHE_NAME = "encrypted_office_preview_cache_v1"
        const val VAULT_ID_FIELD = "vault_id"
    }
}
