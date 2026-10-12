/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableMediaSendStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `two instances racing absent predecessor have exactly one winner`() = runTest {
        val roots = roots(); val first = testStore(roots); val second = testStore(roots)
        val payload = "committed".encodeToByteArray()
        val results = listOf(first, second).map { store -> async {
            runCatching {
                store.withLockedState("journal-test", "same-operation") { current, commit ->
                    require(current == null)
                    commit(DurableMediaSendStateStore.StoredState(1, sha256Hex(payload), payload))
                }
            }.isSuccess
        } }.awaitAll()
        assertThat(results.count { it }).isEqualTo(1)
        assertThat(first.read("journal-test", "same-operation")?.payload).isEqualTo(payload)
        assertThat(temporary.root.walkTopDown().filter { it.name.endsWith(".lock") }.count()).isEqualTo(1)
    }

    @Test fun `stable lock file is distinct from atomically replaced state file`() = runTest {
        var syncedDirectory: File? = null
        val store = DurableMediaSendStateStore(roots()) { syncedDirectory = it }
        val one = "one".encodeToByteArray(); val two = "two".encodeToByteArray()
        store.withLockedState("registry-test", "current") { current, commit ->
            assertThat(current).isNull(); commit(DurableMediaSendStateStore.StoredState(1, sha256Hex(one), one))
        }
        store.withLockedState("registry-test", "current") { current, commit ->
            assertThat(current?.revision).isEqualTo(1); commit(DurableMediaSendStateStore.StoredState(2, sha256Hex(two), two))
        }
        val files = temporary.root.walkTopDown().filter(File::isFile).map(File::getName).toList()
        assertThat(files).containsAtLeast("current.lock", "current.state")
        assertThat(store.read("registry-test", "current")?.revision).isEqualTo(2)
        assertThat(syncedDirectory?.name).isEqualTo("registry-test")
    }

    private fun roots() = object : SecureStorageRoots {
        override fun persistentRoot() = temporary.root
        override fun runtimeRoot() = temporary.root
    }
    private fun testStore(roots: SecureStorageRoots) = DurableMediaSendStateStore(roots) { }
}
