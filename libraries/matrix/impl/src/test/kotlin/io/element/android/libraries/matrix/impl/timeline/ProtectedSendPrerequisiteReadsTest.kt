/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.IOException

class ProtectedSendPrerequisiteReadsTest {
    @Test
    fun `independent slow reads overlap and neither partial result can proceed`() = runTest {
        val started = mutableSetOf<String>()
        val result = async {
            readProtectedSendPrerequisites(
                resolveRoom = { started += "room"; delay(50); "signed-room-policy" },
                resolveRecipients = { started += "recipients"; delay(80); "approved-installations" },
            )
        }
        runCurrent()
        assertThat(started).containsExactly("room", "recipients")
        advanceTimeBy(79)
        runCurrent()
        assertThat(result.isCompleted).isFalse()
        advanceTimeBy(1)
        runCurrent()
        assertThat(result.await()).isEqualTo("signed-room-policy" to "approved-installations")
        assertThat(testScheduler.currentTime).isEqualTo(80L)
    }

    @Test
    fun `room rejection cancels recipient read and never returns prerequisites`() = runTest {
        supervisorScope {
            var recipientStopped = false
            var returned = false
            val result = async {
                readProtectedSendPrerequisites<String, String>(
                    resolveRoom = { delay(1); throw IOException("room rejected") },
                    resolveRecipients = { try { awaitCancellation() } finally { recipientStopped = true } },
                ).also { returned = true }
            }
            assertThat(runCatching { result.await() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(recipientStopped).isTrue()
            assertThat(returned).isFalse()
        }
    }

    @Test
    fun `recipient rejection cancels room read even while caller awaits room first`() = runTest {
        supervisorScope {
            var roomStopped = false
            var returned = false
            val result = async {
                readProtectedSendPrerequisites<String, String>(
                    resolveRoom = { try { awaitCancellation() } finally { roomStopped = true } },
                    resolveRecipients = { delay(1); throw IOException("registry rejected") },
                ).also { returned = true }
            }
            assertThat(runCatching { result.await() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(roomStopped).isTrue()
            assertThat(returned).isFalse()
        }
    }

    @Test
    fun `cancelling original send cancels both in progress reads without a result`() = runTest {
        val started = mutableSetOf<String>()
        val stopped = mutableSetOf<String>()
        var returned = false
        val result = async {
            readProtectedSendPrerequisites<String, String>(
                resolveRoom = {
                    started += "room"
                    try { awaitCancellation() } finally { stopped += "room" }
                },
                resolveRecipients = {
                    started += "recipients"
                    try { awaitCancellation() } finally { stopped += "recipients" }
                },
            ).also { returned = true }
        }
        runCurrent()
        assertThat(started).containsExactly("room", "recipients")
        result.cancelAndJoin()
        assertThat(stopped).containsExactly("room", "recipients")
        assertThat(returned).isFalse()
    }

    @Test
    fun `both reads keep the original send lifecycle context`() = runTest {
        val original = LifecycleOperationContext("original-send", FullAppLifecycleId.LC_13)
        val observed = mutableListOf<LifecycleOperationContext?>()
        val result = withContext(original) {
            readProtectedSendPrerequisites(
                resolveRoom = { observed += currentLifecycleOperationContext(); delay(10); "policy" },
                resolveRecipients = { observed += currentLifecycleOperationContext(); delay(20); "devices" },
            )
        }
        assertThat(result).isEqualTo("policy" to "devices")
        assertThat(observed).hasSize(2)
        observed.forEach { assertThat(it).isSameInstanceAs(original) }
        assertThat(currentLifecycleOperationContext()).isNull()
    }
}
