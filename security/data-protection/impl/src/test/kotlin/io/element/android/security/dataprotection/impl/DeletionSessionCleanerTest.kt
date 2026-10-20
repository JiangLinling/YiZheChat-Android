/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.security.dataprotection.api.DeletionScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeletionSessionCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `managed session row and both direct child directories are deleted`() = runTest {
        val sessionsRoot = temporaryFolder.newFolder("sessions")
        val cacheRoot = temporaryFolder.newFolder("cache")
        val sessionFiles = sessionsRoot.resolve("random-session").apply { mkdirs() }
        val sessionCache = cacheRoot.resolve("random-session").apply { mkdirs() }
        sessionFiles.resolve("store.db").writeText("ciphertext")
        sessionCache.resolve("media.bin").writeText("ciphertext")
        val sessionStore = InMemorySessionStore(initialList = listOf(aSessionData(sessionFiles.path, sessionCache.path)))
        val cleaner = DefaultDeletionSessionCleaner(sessionStore, sessionsRoot, cacheRoot)

        val report = cleaner.delete(aScope(sessionFiles.path, sessionCache.path))

        assertThat(report.sessionRecordRemoved).isTrue()
        assertThat(report.sessionFilesDeleted).isTrue()
        assertThat(sessionStore.getSession(SESSION_ID)).isNull()
        assertThat(sessionFiles.exists()).isFalse()
        assertThat(sessionCache.exists()).isFalse()
    }

    @Test
    fun `path outside managed roots fails closed before removing session row`() = runTest {
        val sessionsRoot = temporaryFolder.newFolder("sessions")
        val cacheRoot = temporaryFolder.newFolder("cache")
        val outside = temporaryFolder.newFolder("outside")
        val sessionCache = cacheRoot.resolve("random-session").apply { mkdirs() }
        val sessionStore = InMemorySessionStore(initialList = listOf(aSessionData(outside.path, sessionCache.path)))
        val cleaner = DefaultDeletionSessionCleaner(sessionStore, sessionsRoot, cacheRoot)

        val report = cleaner.delete(aScope(outside.path, sessionCache.path))

        assertThat(report.sessionRecordRemoved).isFalse()
        assertThat(report.sessionFilesDeleted).isFalse()
        assertThat(sessionStore.getSession(SESSION_ID)).isNotNull()
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `delayed cleanup deletes old files but preserves a new login for the same account`() = runTest {
        val sessionsRoot = temporaryFolder.newFolder("sessions")
        val cacheRoot = temporaryFolder.newFolder("cache")
        val oldFiles = sessionsRoot.resolve("old").apply { mkdirs() }
        val oldCache = cacheRoot.resolve("old").apply { mkdirs() }
        val newFiles = sessionsRoot.resolve("new").apply { mkdirs() }
        val newCache = cacheRoot.resolve("new").apply { mkdirs() }
        val newSession = aSessionData(newFiles.path, newCache.path).copy(deviceId = "NEW-DEVICE")
        val store = InMemorySessionStore(initialList = listOf(newSession))
        val cleaner = DefaultDeletionSessionCleaner(store, sessionsRoot, cacheRoot)
        assertThat(cleaner.hasReplacement(aScope(oldFiles.path, oldCache.path))).isTrue()
        repeat(2) {
            val report = cleaner.delete(aScope(oldFiles.path, oldCache.path))
            assertThat(report.sessionRecordRemoved).isTrue()
            assertThat(report.sessionFilesDeleted).isTrue()
            assertThat(store.getSession(SESSION_ID)).isEqualTo(newSession)
            assertThat(newFiles.exists()).isTrue()
            assertThat(newCache.exists()).isTrue()
        }
    }

    private fun aScope(filePath: String, cachePath: String) = DeletionScope.Account(
        accountIdHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        vaultId = "account-vault",
        localSessionId = SESSION_ID,
        sessionFilePath = filePath,
        sessionCachePath = cachePath,
    )

    private fun aSessionData(filePath: String, cachePath: String) = SessionData(
        userId = SESSION_ID,
        deviceId = "DEVICE",
        accessToken = "test-token",
        refreshToken = null,
        homeserverUrl = "https://example.org",
        oAuthData = null,
        loginTimestamp = null,
        isTokenValid = true,
        loginType = LoginType.PASSWORD,
        passphrase = "test-passphrase",
        sessionPath = filePath,
        cachePath = cachePath,
        position = 0,
        lastUsageIndex = 0,
        userDisplayName = null,
        userAvatarUrl = null,
    )

    private companion object {
        const val SESSION_ID = "@alice:example.org"
    }
}
