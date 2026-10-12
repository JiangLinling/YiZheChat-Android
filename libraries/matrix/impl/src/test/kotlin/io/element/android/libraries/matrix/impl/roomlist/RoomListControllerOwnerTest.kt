/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RoomListControllerOwnerTest {
    @Test
    fun `UI callbacks after stream release never touch the destroyed controller`() {
        val owner = RoomListControllerOwner()
        val controller = TestController()
        owner.install(controller)
        owner.withController { it.addOnePage() }
        owner.release(controller)
        owner.withController { it.addOnePage() }
        assertThat(controller.calls).isEqualTo(1)
        assertThat(controller.destroyed).isTrue()
    }

    @Test
    fun `closing an old stream leaves its replacement usable`() {
        val owner = RoomListControllerOwner()
        val old = TestController()
        val replacement = TestController()
        owner.install(old)
        owner.install(replacement)
        owner.release(old)
        owner.withController { it.addOnePage() }
        assertThat(old.destroyed).isTrue()
        assertThat(replacement.calls).isEqualTo(1)
        owner.release(replacement)
    }

    @Test
    fun `release waits for an in-flight native operation before destroying its controller`() {
        val owner = RoomListControllerOwner()
        val controller = TestController()
        owner.install(controller)
        val entered = CountDownLatch(1)
        val continueOperation = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val operation = executor.submit {
                owner.withController {
                    entered.countDown()
                    check(continueOperation.await(5, TimeUnit.SECONDS))
                    it.addOnePage()
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val release = executor.submit {
                releasing.countDown()
                owner.release(controller)
            }
            check(releasing.await(5, TimeUnit.SECONDS))
            assertThat(controller.destroyed).isFalse()
            continueOperation.countDown()
            operation.get(5, TimeUnit.SECONDS)
            release.get(5, TimeUnit.SECONDS)
            assertThat(controller.calls).isEqualTo(1)
            assertThat(controller.destroyed).isTrue()
        } finally {
            continueOperation.countDown()
            executor.shutdownNow()
        }
    }

    private class TestController : RoomListDynamicEntriesController(NoHandle) {
        @Volatile var destroyed = false
        var calls = 0
        override fun addOnePage() {
            check(!destroyed)
            calls++
        }
        override fun destroy() {
            destroyed = true
        }
    }
}
