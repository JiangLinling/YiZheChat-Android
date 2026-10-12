/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.cryptography.api.EncryptionDecryptionService
import io.element.android.libraries.cryptography.api.EncryptionResult
import io.element.android.libraries.cryptography.api.SecretKeyRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface EmbeddedNtfyRegistrationStore {
    suspend fun getAll(): List<EmbeddedNtfyRegistration>
    suspend fun get(clientSecret: String): EmbeddedNtfyRegistration?
    suspend fun upsert(registration: EmbeddedNtfyRegistration)
    suspend fun remove(clientSecret: String)
    suspend fun updateLastMessageId(clientSecret: String, messageId: String)
}

@ContributesBinding(AppScope::class)
class EncryptedEmbeddedNtfyRegistrationStore(
    private val sharedPreferences: SharedPreferences,
    private val secretKeyRepository: SecretKeyRepository,
    private val encryptionDecryptionService: EncryptionDecryptionService,
    private val jsonProvider: JsonProvider,
    private val dispatchers: CoroutineDispatchers,
) : EmbeddedNtfyRegistrationStore {
    private val mutex = Mutex()

    override suspend fun getAll(): List<EmbeddedNtfyRegistration> = withContext(dispatchers.io) {
        mutex.withLock { loadState().registrations }
    }

    override suspend fun get(clientSecret: String): EmbeddedNtfyRegistration? = withContext(dispatchers.io) {
        mutex.withLock { loadState().registrations.find { it.clientSecret == clientSecret } }
    }

    override suspend fun upsert(registration: EmbeddedNtfyRegistration) = withContext(dispatchers.io) {
        mutex.withLock {
            val registrations = loadState().registrations
                .filterNot { it.clientSecret == registration.clientSecret } + registration
            saveState(EmbeddedNtfyRegistrationState(registrations))
        }
    }

    override suspend fun remove(clientSecret: String) = withContext(dispatchers.io) {
        mutex.withLock {
            val state = loadState()
            val registrations = state.registrations.filterNot { it.clientSecret == clientSecret }
            if (registrations.size != state.registrations.size) {
                saveState(EmbeddedNtfyRegistrationState(registrations))
            }
        }
    }

    override suspend fun updateLastMessageId(clientSecret: String, messageId: String) = withContext(dispatchers.io) {
        mutex.withLock {
            val state = loadState()
            val registrations = state.registrations.map {
                if (it.clientSecret == clientSecret) it.copy(lastMessageId = messageId) else it
            }
            saveState(EmbeddedNtfyRegistrationState(registrations))
        }
    }

    private suspend fun loadState(): EmbeddedNtfyRegistrationState {
        val encrypted = sharedPreferences.getString(PREFERENCE_KEY, null) ?: return EmbeddedNtfyRegistrationState()
        return try {
            val key = secretKeyRepository.getOrCreateKey(KEYSTORE_ALIAS, requiresUserAuthentication = false)
            val plaintext = encryptionDecryptionService.decrypt(key, EncryptionResult.fromBase64(encrypted))
            jsonProvider().decodeFromString<EmbeddedNtfyRegistrationState>(plaintext.decodeToString())
        } catch (failure: Exception) {
            throw IllegalStateException("Unable to decrypt built-in push credentials", failure)
        }
    }

    private suspend fun saveState(state: EmbeddedNtfyRegistrationState) {
        val key = secretKeyRepository.getOrCreateKey(KEYSTORE_ALIAS, requiresUserAuthentication = false)
        val plaintext = jsonProvider().encodeToString(state).encodeToByteArray()
        val encrypted = encryptionDecryptionService.encrypt(key, plaintext).toBase64()
        sharedPreferences.edit(commit = true) {
            putString(PREFERENCE_KEY, encrypted)
        }
    }

    private companion object {
        const val PREFERENCE_KEY = "embedded_ntfy_registrations_encrypted_v1"
        const val KEYSTORE_ALIAS = "yizhe_embedded_ntfy_credentials_v1"
    }
}
