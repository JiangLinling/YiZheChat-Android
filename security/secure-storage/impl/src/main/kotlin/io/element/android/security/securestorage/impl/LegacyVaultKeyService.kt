/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface LegacyVaultKeyService {
    fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey

    fun destroy(vaultId: AccountVaultId): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidLegacyVaultKeyService : LegacyVaultKeyService {
    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey {
        val key = keyStore.getKey(aliasFor(vaultId), null) as? SecretKey ?: throw SecureStorageKeyUnavailableException()
        val plaintext = Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedObjectKey.initializationVector))
            updateAAD(associatedData)
            doFinal(wrappedObjectKey.ciphertext)
        }
        return javax.crypto.spec.SecretKeySpec(plaintext, AES_ALGORITHM).also { plaintext.fill(0) }
    }

    override fun destroy(vaultId: AccountVaultId): Boolean = try {
        val alias = aliasFor(vaultId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        !keyStore.containsAlias(alias)
    } catch (_: Exception) {
        false
    }

    private fun aliasFor(vaultId: AccountVaultId): String = "$LEGACY_VAULT_KEY_ALIAS_PREFIX${sha256Hex(vaultId.value).take(32)}"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val LEGACY_VAULT_KEY_ALIAS_PREFIX = "yizhe.secure-file.v1."
    }
}
