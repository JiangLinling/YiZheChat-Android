package io.element.android.features.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.ui.awaitProtectedCallFinalization
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedCallFinalizationTest {
    @Test
    fun `Vault lock between authorization and checkpoint ends media through the failure boundary`() = runTest {
        var ended = 0
        val failure = io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException()
        io.element.android.features.call.impl.ui.protectCallStateUpdate({ caught ->
            assertThat(caught).isSameInstanceAs(failure)
            ended++
        }) {
            throw failure
        }
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun `call state cancellation propagates without a second termination`() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("Call already ending")
        val result = runCatching {
            io.element.android.features.call.impl.ui.protectCallStateUpdate({ error("Unexpected duplicate termination") }) {
                throw cancellation
            }
        }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test
    fun `locked Vault delays protected write until recovery and writes once`() = runTest {
        val access = MutableStateFlow(false)
        var writes = 0
        val job = launch { awaitProtectedCallFinalization(access) { writes++ } }
        runCurrent()
        assertThat(writes).isEqualTo(0)
        access.value = true
        job.join()
        access.value = false
        access.value = true
        runCurrent()
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `cancelled pending finalization does not manufacture a write`() = runTest {
        var writes = 0
        val job = launch { awaitProtectedCallFinalization(MutableStateFlow(false)) { writes++ } }
        runCurrent()
        job.cancelAndJoin()
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `failed write is propagated and never automatically repeated`() = runTest {
        val failure = IllegalStateException("acknowledgement unavailable")
        val result = CompletableDeferred<Throwable?>()
        var writes = 0
        launch {
            result.complete(runCatching {
                awaitProtectedCallFinalization(MutableStateFlow(true)) { writes++; throw failure }
            }.exceptionOrNull())
        }
        assertThat(result.await()).isSameInstanceAs(failure)
        assertThat(writes).isEqualTo(1)
    }
}
