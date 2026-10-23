package io.element.android.features.networkmonitor.api.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StableConnectivityTest {
    @Test
    fun `rapid failed reconnects keep the offline indicator continuously visible`() = runTest {
        val online = MutableStateFlow(false)
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            online.stableIndicatorConnectivity().collect(observed::add)
        }
        repeat(100) {
            online.value = true
            advanceTimeBy(40)
            online.value = false
            advanceTimeBy(20)
        }
        runCurrent()
        assertThat(observed).containsExactly(false)
        online.value = true
        advanceTimeBy(1999)
        assertThat(observed).containsExactly(false)
        advanceTimeBy(1)
        runCurrent()
        assertThat(observed).containsExactly(false, true).inOrder()
    }

    @Test
    fun `disconnect is immediate and interrupts a pending recovery`() = runTest {
        val online = MutableStateFlow(true)
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            online.stableIndicatorConnectivity().collect(observed::add)
        }
        advanceTimeBy(2000)
        runCurrent()
        online.value = false
        runCurrent()
        assertThat(observed).containsExactly(true, false).inOrder()
        online.value = true
        advanceTimeBy(1000)
        online.value = false
        advanceTimeBy(2000)
        runCurrent()
        assertThat(observed).containsExactly(true, false).inOrder()
    }
}
