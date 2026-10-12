/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.KeyStore

class ReloadingContentKeyStoreTest {
    @Test
    fun `missing key is retried with fresh provider views`() {
        var opens = 0
        val delays = mutableListOf<Long>()
        val keyStores = ReloadingContentKeyStore(
            openKeyStore = { emptyKeyStore().also { opens++ } },
            retryDelay = delays::add,
        )

        val result = keyStores.readPresent { if (opens >= 2) "existing-key" else null }

        assertThat(result).isEqualTo("existing-key")
        assertThat(opens).isEqualTo(2)
        assertThat(delays).containsExactly(75L)
    }

    @Test
    fun `key that remains absent is not synthesized`() {
        var opens = 0
        val keyStores = ReloadingContentKeyStore(
            openKeyStore = { emptyKeyStore().also { opens++ } },
            retryDelay = {},
        )

        val result: String? = keyStores.readPresent { null }

        assertThat(result).isNull()
        assertThat(opens).isEqualTo(3)
    }

    @Test
    fun `provider exceptions are retried with a new handle`() {
        var opens = 0
        val keyStores = ReloadingContentKeyStore(
            openKeyStore = {
                opens++
                if (opens == 1) error("stale provider")
                emptyKeyStore()
            },
            retryDelay = {},
        )

        val result = keyStores.readPresent { "existing-key" }

        assertThat(result).isEqualTo("existing-key")
        assertThat(opens).isEqualTo(2)
    }

    private fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
    }
}
