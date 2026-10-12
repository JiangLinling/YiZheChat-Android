/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.HistoricalMediaSendPrepared
import io.element.android.security.contentprotection.api.MediaSendJournalCas
import io.element.android.security.contentprotection.api.MediaSendJournalState
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.deriveAccountVaultId
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedMediaSendJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `authenticated prepared survives restart and cross-instance CAS has one winner`() = runTest {
        val session = session(); val sessions = mockk<SessionStore>()
        coEvery { sessions.getSession(session.userId) } returns session; coEvery { sessions.getLatestSession() } returns session
        val sensitive = InMemorySensitiveFileStore(); val roots = roots()
        val first = EncryptedMediaSendJournal(sensitive, sessions, durable(roots))
        val second = EncryptedMediaSendJournal(sensitive, sessions, durable(roots))
        val prepared = prepared(session)
        val initial = first.persistPrepared(prepared).getOrThrow()
        assertThat(second.load(prepared.operationId, prepared.requestId, prepared.transactionId).getOrThrow().prepared).isEqualTo(prepared)
        val results = listOf(first, second).map { journal -> async {
            journal.compareAndSet(MediaSendJournalCas(
                prepared.operationId, prepared.requestId, prepared.transactionId,
                initial.revision, initial.recordDigestSha256, MediaSendJournalState.PREPARED,
                MediaSendJournalState.ALLOW_VERIFIED, allowEnvelope = "allow".encodeToByteArray(),
            )).isSuccess
        } }.awaitAll()
        assertThat(results.count { it }).isEqualTo(1)
    }

    @Test fun `logout vault destruction makes historical journal unreadable`() = runTest {
        val session = session(); val sessions = mockk<SessionStore>(); coEvery { sessions.getSession(session.userId) } returns session; coEvery { sessions.getLatestSession() } returns session
        val sensitive = InMemorySensitiveFileStore(); val journal = EncryptedMediaSendJournal(sensitive, sessions, durable(roots()))
        val prepared = prepared(session); journal.persistPrepared(prepared).getOrThrow()
        sensitive.deleteVault(deriveAccountVaultId(session.userId, session.deviceId))
        assertThat(journal.load(prepared.operationId, prepared.requestId, prepared.transactionId).isFailure).isTrue()
    }

    private fun prepared(session: SessionData): HistoricalMediaSendPrepared {
        val event = "{}"; val account = sha256Hex(session.userId.encodeToByteArray())
        return HistoricalMediaSendPrepared(
            "op-1", "req-1", "txn-1", "!room:example.org", "asset-1", 1,
            "a".repeat(64), "b".repeat(64), "c".repeat(64), account, "install-1", session.userId, session.deviceId,
            7, 9, "contract-1", "GLOBAL", "rev-1", 4, "e".repeat(64), 11, 2,
            sha256Hex(event.encodeToByteArray()), event,
        )
    }
    private fun session() = SessionData("@alice:example.org", "DEVICE_A", "token", null, "https://matrix.example", null, null,
        true, LoginType.PASSWORD, null, "session", "cache", 0, 1, null, null)
    private fun roots() = object : SecureStorageRoots { override fun persistentRoot() = temporary.root; override fun runtimeRoot() = temporary.root }
    private fun durable(roots: SecureStorageRoots) = DurableMediaSendStateStore(roots) { }
}
