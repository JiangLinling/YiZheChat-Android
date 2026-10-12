/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import org.junit.Test
import java.security.KeyStore

class ReloadingAndroidKeyStoreTest {
    @Test
    fun `reopens the provider when a cached handle temporarily cannot see a key`() {
        var openCount = 0
        val delays = mutableListOf<Long>()
        val access = ReloadingAndroidKeyStore(
            openKeyStore = {
                openCount++
                emptyKeyStore()
            },
            retryDelay = delays::add,
        )

        val result = access.read { if (openCount >= 2) "same-existing-key" else null }

        assertThat(result).isEqualTo("same-existing-key")
        assertThat(openCount).isEqualTo(2)
        assertThat(delays).containsExactly(75L)
    }

    @Test
    fun `retries provider exceptions with a newly opened handle`() {
        var openCount = 0
        val access = ReloadingAndroidKeyStore(
            openKeyStore = {
                openCount++
                emptyKeyStore()
            },
            retryDelay = {},
        )

        val result = access.read { if (openCount == 1) error("stale provider") else "same-existing-key" }

        assertThat(result).isEqualTo("same-existing-key")
        assertThat(openCount).isEqualTo(2)
    }

    @Test
    fun `a key that stays absent remains unavailable without replacement`() {
        var openCount = 0
        val access = ReloadingAndroidKeyStore(
            openKeyStore = {
                openCount++
                emptyKeyStore()
            },
            retryDelay = {},
        )

        val result: String? = access.read { null }

        assertThat(result).isNull()
        assertThat(openCount).isEqualTo(3)
    }

    @Test
    fun `a provider that stays broken fails closed`() {
        val access = ReloadingAndroidKeyStore(
            openKeyStore = { throw IllegalStateException("provider unavailable") },
            retryDelay = {},
        )

        val failure = runCatching { access.read { "unreachable" } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    private fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
    }
}
