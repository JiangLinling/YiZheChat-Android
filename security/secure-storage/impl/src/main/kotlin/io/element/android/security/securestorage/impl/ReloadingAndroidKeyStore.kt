/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import java.security.KeyStore

/**
 * Keeps Android Keystore access resilient to an invalidated provider handle after an app/container resume.
 *
 * Every retry opens the same system keystore again. It never generates or replaces key material, and a key that
 * remains absent after the bounded retries is still reported as unavailable by the caller.
 */
internal class ReloadingAndroidKeyStore(
    private val openKeyStore: () -> KeyStore = {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    },
    private val retryDelay: (Long) -> Unit = Thread::sleep,
) {
    @Volatile
    private var cachedKeyStore: KeyStore? = null

    fun <T : Any> read(operation: (KeyStore) -> T?): T? {
        var lastFailure: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val keyStore = if (attempt == 0) currentOrOpen() else reopen()
                operation(keyStore)?.let { return it }
            } catch (failure: Exception) {
                lastFailure = failure
            }
            if (attempt < MAX_ATTEMPTS - 1) retryDelay(RETRY_DELAYS_MILLIS[attempt])
        }
        if (lastFailure != null) throw SecureStorageKeyUnavailableException(lastFailure)
        return null
    }

    private fun currentOrOpen(): KeyStore = cachedKeyStore ?: synchronized(this) {
        cachedKeyStore ?: openKeyStore().also { cachedKeyStore = it }
    }

    private fun reopen(): KeyStore = synchronized(this) {
        openKeyStore().also { cachedKeyStore = it }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MILLIS = longArrayOf(75L, 225L)
    }
}
