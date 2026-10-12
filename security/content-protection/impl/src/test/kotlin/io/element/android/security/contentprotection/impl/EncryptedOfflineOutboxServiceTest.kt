/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.security.contentprotection.api.OfflineTextOutboxMessage
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedOfflineOutboxServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `outbox survives service recreation and index never contains message plaintext`() = runTest {
        val persistent = temporaryFolder.newFolder("persistent")
        val roots = TestRoots(persistent, temporaryFolder.newFolder("runtime"))
        val sensitiveStore = InMemorySensitiveFileStore()
        val sessions = InMemorySessionStore(listOf(session()))
        val message = message()

        val first = EncryptedOfflineOutboxService(sensitiveStore, roots, sessions)
        assertThat(first.stage(message)).isTrue()
        val indexBytes = persistent.walkTopDown().filter(File::isFile).flatMap { it.readBytes().asSequence() }.toList()
        assertThat(indexBytes.toByteArray().decodeToString()).doesNotContain(message.body)

        val restarted = EncryptedOfflineOutboxService(sensitiveStore, roots, sessions)
        assertThat(restarted.messages(ACCOUNT_ID, ROOM_ID)).containsExactly(message)
        assertThat(restarted.remove(ACCOUNT_ID, ROOM_ID, TRANSACTION_ID)).isTrue()
        assertThat(restarted.messages(ACCOUNT_ID, ROOM_ID)).isEmpty()
    }

    private fun message() = OfflineTextOutboxMessage(
        transactionId = TRANSACTION_ID,
        accountId = ACCOUNT_ID,
        roomId = ROOM_ID,
        body = "offline confidential draft",
        htmlBody = "<strong>offline confidential draft</strong>",
        mentionedUserIds = listOf("@bob:yizhe.test"),
        mentionsRoom = false,
        repliedToEventId = "\$event:yizhe.test",
        createdAtEpochMillis = 1_000,
    )

    private fun session() = SessionData(
        userId = ACCOUNT_ID,
        deviceId = "DEVICE-OUTBOX-1",
        accessToken = "test-token",
        refreshToken = null,
        homeserverUrl = "https://yizhe.test",
        oAuthData = null,
        loginTimestamp = null,
        isTokenValid = true,
        loginType = LoginType.PASSWORD,
        passphrase = null,
        sessionPath = "session",
        cachePath = "cache",
        position = 0,
        lastUsageIndex = 0,
        userDisplayName = null,
        userAvatarUrl = null,
    )

    private data class TestRoots(private val persistent: File, private val runtime: File) : SecureStorageRoots {
        override fun persistentRoot(): File = persistent
        override fun runtimeRoot(): File = runtime
    }

    private companion object {
        const val ACCOUNT_ID = "@alice:yizhe.test"
        const val ROOM_ID = "!offline-outbox:yizhe.test"
        const val TRANSACTION_ID = "optimistic:offline-outbox-0001"
    }
}
