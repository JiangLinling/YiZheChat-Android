package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.security.dataprotection.api.DataDeletionCoordinator
import io.element.android.security.dataprotection.api.DeletionRecoveryReport
import io.element.android.security.dataprotection.api.DeletionReport
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionStep
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultNextInternalCutoverMigrationTest {
    @Test
    fun `previous completed cutover does not suppress the formal migration`() = runTest {
        val f = Fixture()
        f.migration().migrate(FORMAL)
        assertThat(f.requests).hasSize(1)
        assertThat(f.sessions).isEmpty()
        assertThat(f.preferences.getValue(FORMAL_PREFERENCES)["complete"]).isTrue()
        verify(exactly = 1) { f.state.resetForNextInternalCutover() }
    }

    @Test
    fun `failed cleanup remains pending and retries the same operation before resetting authority`() = runTest {
        val f = Fixture().apply { deletionCompletes = false }
        check(runCatching { f.migration().migrate(FORMAL) }.isFailure)
        assertThat(f.preferences.getValue(FORMAL_PREFERENCES)["pending"]).isTrue()
        verify(exactly = 0) { f.state.resetForNextInternalCutover() }
        f.deletionCompletes = true
        f.migration().migrate(FORMAL)
        assertThat(f.requests.map { it.operationId }.distinct()).hasSize(1)
        coVerify(exactly = 1) { f.deletion.resumePending(coldStart = true) }
        verify(exactly = 1) { f.state.resetForNextInternalCutover() }
    }

    @Test
    fun `logged out installation clears old signed authority once even with retained signing key`() = runTest {
        val f = Fixture().apply { sessions = emptyList() }
        f.migration().migrate(FORMAL)
        f.migration().migrate(FORMAL)
        coVerify(exactly = 0) { f.deletion.delete(any()) }
        verify(exactly = 1) { f.state.resetForNextInternalCutover() }
    }

    @Test
    fun `formal account survives subsequent cold starts`() = runTest {
        val f = Fixture()
        f.migration().migrate(FORMAL)
        f.sessions = listOf(aSessionData(sessionId = "@alice:yizhesy.cn").copy(homeserverUrl = FORMAL))
        f.migration().migrate(FORMAL)
        assertThat(f.sessions).hasSize(1)
        assertThat(f.requests).hasSize(1)
        verify(exactly = 1) { f.state.resetForNextInternalCutover() }
    }

    @Test
    fun `mixed authority installation fails closed without deleting the valid formal account`() = runTest {
        val f = Fixture()
        f.sessions = f.sessions + aSessionData(sessionId = "@bob:yizhesy.cn").copy(homeserverUrl = FORMAL)
        check(runCatching { f.migration().migrate(FORMAL) }.isFailure)
        assertThat(f.sessions).hasSize(2)
        coVerify(exactly = 0) { f.deletion.delete(any()) }
        verify(exactly = 0) { f.state.resetForNextInternalCutover() }
    }

    private class Fixture {
        var sessions = listOf(
            aSessionData(sessionId = "@alice:matrix-next.yizhesy.cn")
            .copy(homeserverUrl = "https://matrix-next.yizhesy.cn:18443")
        )
        var deletionCompletes = true
        val requests = mutableListOf<DeletionRequest>()
        val preferences = mutableMapOf("next_internal_cutover_migration_v1" to mutableMapOf("complete" to true))
        private val preferencesObjects = mutableMapOf<String, SharedPreferences>()
        val state = mockk<SignedManagementStateRepository> {
            every { hasSignedStateFromAuthority(any()) } returns false
            every { resetForNextInternalCutover() } just Runs
        }
        val deletion = mockk<DataDeletionCoordinator> {
            coEvery { resumePending(coldStart = true) } returns DeletionRecoveryReport(emptyList(), 0)
            coEvery { delete(any()) } answers {
                val request = firstArg<DeletionRequest>()
                requests += request
                if (deletionCompletes) sessions = emptyList()
                DeletionReport(request.operationId, if (deletionCompletes) setOf(DeletionStep.COMPLETE) else emptySet(), !deletionCompletes)
            }
        }
        private val store = mockk<SessionStore> { coEvery { getAllSessions() } answers { sessions } }
        private val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } answers {
                val name = firstArg<String>()
                preferencesObjects.getOrPut(name) {
                    val values = preferences.getOrPut(name) { mutableMapOf() }
                    val editor = mockk<SharedPreferences.Editor>()
                    every { editor.putBoolean(any(), any()) } answers {
                        values[firstArg()] = secondArg()
                        editor
                    }
                    every { editor.commit() } returns true
                    mockk<SharedPreferences> {
                        every { getBoolean(any(), any()) } answers { values[firstArg<String>()] ?: secondArg<Boolean>() }
                        every { edit() } returns editor
                    }
                }
            }
        }
        fun migration() = DefaultNextInternalCutoverMigration(context, store, deletion, state)
    }

    companion object {
        private const val FORMAL = "https://matrix.yizhesy.cn"
        private const val FORMAL_PREFERENCES = "formal_internal_cutover_migration_v1"
    }
}
