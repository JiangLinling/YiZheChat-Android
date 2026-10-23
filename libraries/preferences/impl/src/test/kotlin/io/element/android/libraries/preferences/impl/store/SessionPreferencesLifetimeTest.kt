/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.preferences.impl.store

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SessionPreferencesLifetimeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun context(): Context = mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.filesDir } returns temporaryFolder.root
    }

    @Test fun `deletion releases the old file before immediate same-account login without stopping other sessions`() = runTest {
        val listener = slot<SessionListener>()
        val observer = mockk<SessionObserver>()
        every { observer.addListener(capture(listener)) } just Runs
        val factory = DefaultSessionPreferencesStoreFactory(context(), observer, backgroundScope)
        val oldMatrixScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val account = SessionId("@clear-c:example.org")
        val otherAccount = SessionId("@preserved-a:example.org")
        val old = factory.get(account, oldMatrixScope)
        val other = factory.get(otherAccount, backgroundScope)
        assertThat(old.isSessionVerificationSkipped().first()).isFalse()
        assertThat(other.isSessionVerificationSkipped().first()).isFalse()

        // Start a replacement while the deletion listener is still waiting for file closure.
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            listener.captured.onSessionDeleted(account.value, wasLastSession = false)
        }
        val restored = factory.get(account, backgroundScope)
        assertThat(restored).isNotSameInstanceAs(old)
        assertThat(restored.isSessionVerificationSkipped().first()).isFalse()
        deletion.await()
        assertThat(oldMatrixScope.isActive).isTrue()
        assertThat(factory.get(otherAccount, backgroundScope)).isSameInstanceAs(other)
        assertThat(other.isSessionVerificationSkipped().first()).isFalse()
    }

    @Test fun `parallel readers share one file owner and cache replacement waits for its closure`() = runTest {
        val observer = mockk<SessionObserver>(relaxed = true)
        val factory = DefaultSessionPreferencesStoreFactory(context(), observer, backgroundScope)
        val account = SessionId("@parallel:example.org")
        val owners = (1..24).map {
            async(Dispatchers.Default) { factory.get(account, backgroundScope) }
        }.awaitAll()
        owners.forEach { assertThat(it).isSameInstanceAs(owners.first()) }
        assertThat(owners.first().isSessionVerificationSkipped().first()).isFalse()
        factory.remove(account)
        val replacement = factory.get(account, backgroundScope)
        assertThat(replacement).isNotSameInstanceAs(owners.first())
        assertThat(replacement.isSessionVerificationSkipped().first()).isFalse()
        assertThat(backgroundScope.isActive).isTrue()
    }

    @Test fun `failed previous closure blocks both reads and writes without opening the replacement file`() = runTest {
        val context = context()
        val account = SessionId("@failed-close:example.org")
        val closed = CompletableDeferred<Unit>()
        val replacement = DefaultSessionPreferencesStore(context, account, backgroundScope, closed)
        val read = async { runCatching { replacement.isSessionVerificationSkipped().first() } }
        val write = async { runCatching { replacement.setSkipSessionVerification(true) } }
        runCurrent()
        assertThat(read.isCompleted).isFalse()
        assertThat(write.isCompleted).isFalse()
        val failure = IOException("Previous DataStore did not close")
        closed.completeExceptionally(failure)
        assertThat(read.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(write.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(read.await().exceptionOrNull()).hasMessageThat().isEqualTo(failure.message)
        assertThat(write.await().exceptionOrNull()).hasMessageThat().isEqualTo(failure.message)
        assertThat(DefaultSessionPreferencesStore.storeFile(context, account).exists()).isFalse()
    }

    @Test fun `actual deletion failure remains a barrier to a replacement login`() = runTest {
        val context = context()
        val listener = slot<SessionListener>()
        val observer = mockk<SessionObserver>()
        every { observer.addListener(capture(listener)) } just Runs
        // Own and await this deliberately failing async task, instead of TestScope's background
        // failure reporter treating the expected failure as an unhandled test error.
        val appScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val factory = DefaultSessionPreferencesStoreFactory(context, observer, appScope)
        val account = SessionId("@undeletable:example.org")
        val directory = DefaultSessionPreferencesStore.storeFile(context, account)
        assertThat(directory.mkdirs()).isTrue()
        val retained = java.io.File(directory, "retained")
        retained.writeText("fixture")
        val deletion = runCatching { listener.captured.onSessionDeleted(account.value, true) }
        assertThat(deletion.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        val replacement = factory.get(account, backgroundScope)
        assertThat(runCatching { replacement.isSessionVerificationSkipped().first() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { replacement.isSessionVerificationSkipped().first() }.exceptionOrNull())
            .hasMessageThat().isEqualTo("Session preferences deletion failed")
        assertThat(retained.exists()).isTrue()
        appScope.cancel()
    }
}
