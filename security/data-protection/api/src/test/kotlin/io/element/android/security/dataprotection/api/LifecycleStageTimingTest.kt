package io.element.android.security.dataprotection.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class LifecycleStageTimingTest {
    private val lines = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines += message }
    }
    @Before fun setUp() { Timber.plant(tree) }
    @After fun tearDown() { Timber.uproot(tree) }

    @Test fun `nested spans retain actual lifecycle and distinct spans without changing results`() = runTest {
        withContext(LifecycleOperationContext("test-operation-012345", FullAppLifecycleId.LC_13)) {
            assertEquals(42, LifecycleStageTiming.measure("outer", "opaque-id") {
                LifecycleStageTiming.measure("inner", "opaque-id") { 42 }
            })
        }
        assertEquals(4, lines.size)
        assertTrue(lines.all { "operation=test-operation-012345" in it })
        val outerSpan = Regex(" span=(\\d+)").find(lines[0])!!.groupValues[1]
        val innerSpan = Regex(" span=(\\d+)").find(lines[1])!!.groupValues[1]
        assertNotEquals(outerSpan, innerSpan)
        assertTrue(lines.all { "parent=0" in it })
        assertTrue("outcome=ok" in lines.last())
    }

    @Test fun `failure and cancellation preserve their original exception and close spans`() = runTest {
        val failures = listOf(IllegalStateException("private failure"), CancellationException("private cancellation"))
        for (failure in failures) {
            try {
                LifecycleStageTiming.measure("test_failure") { throw failure }
                fail("Exception was swallowed")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
        assertEquals(4, lines.size)
        assertTrue(lines.any { "outcome=cancelled" in it })
        assertTrue(lines.any { "outcome=error" in it })
        assertFalse(lines.any { "private" in it })
    }

    @Test fun `unsafe metadata is replaced and sibling lifecycle context does not leak`() = runTest {
        LifecycleStageTiming.measure("not a stage", "message body with spaces", "scope\nsecret", "bearer token") { Unit }
        assertTrue(lines.all { "stage=invalid identity=unbound scope=unbound operation=unbound" in it })
        lines.clear()
        LifecycleStageTiming.point("outside_operation")
        assertTrue("operation=unbound" in lines.single())
    }
}
