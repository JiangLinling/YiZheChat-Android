/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import java.security.KeyStore

/**
 * Opens a fresh Android Keystore view for every attempt.
 *
 * Some Android compatibility containers keep a stale provider handle after process freeze or lock-screen resume.
 * Reopening is safe because it neither creates nor deletes key material. A missing key is retried as well as a
 * provider exception so a transient empty view cannot trigger accidental key replacement.
 */
internal class ReloadingContentKeyStore(
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val retryDelayMillis: (Int) -> Long = ::defaultRetryDelayMillis,
    private val openKeyStore: () -> KeyStore = {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    },
    private val retryDelay: (Long) -> Unit = Thread::sleep,
) {
    init {
        require(attempts > 0)
    }

    fun <T> read(block: (KeyStore) -> T): T {
        var failure: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block(open())
            } catch (current: Exception) {
                failure = current
                waitBeforeRetry(attempt)
            }
        }
        throw requireNotNull(failure)
    }

    fun <T : Any> readPresent(block: (KeyStore) -> T?): T? {
        var failure: Exception? = null
        repeat(attempts) { attempt ->
            try {
                block(open())?.let { return it }
            } catch (current: Exception) {
                failure = current
            }
            waitBeforeRetry(attempt)
        }
        if (failure != null) throw requireNotNull(failure)
        return null
    }

    private fun open(): KeyStore = openKeyStore()

    private fun waitBeforeRetry(attempt: Int) {
        if (attempt >= attempts - 1) return
        val delay = retryDelayMillis(attempt)
        if (delay > 0) retryDelay(delay)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ATTEMPTS = 3

        fun defaultRetryDelayMillis(attempt: Int): Long = when (attempt) {
            0 -> 75L
            else -> 225L
        }
    }
}
