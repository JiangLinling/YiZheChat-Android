/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.crash

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VectorUncaughtExceptionHandlerTest {
    @Test
    fun `secure fatal path records complete throwable flushes boundedly and keeps plaintext marker generic`() = runTest {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val error = IllegalStateException("password=SHOULD_NOT_PERSIST")
        var captured: Throwable? = null
        var timeout: Long? = null
        var delegated = 0
        val fake = io.element.android.features.rageshake.impl.reporter.FakeDiagnosticLogStore()
        val logs = object : io.element.android.security.securestorage.api.SecureDiagnosticLogStore by fake {
            override fun append(priority: Int, source: String?, message: String, throwable: Throwable?, location: String?) {
                assertThat(priority).isEqualTo(7)
                captured = throwable
            }
            override fun flushBlocking(timeoutMillis: Long): Boolean {
                timeout = timeoutMillis
                return true
            }
        }
        val crashes = FakeCrashDataStore()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
            VectorUncaughtExceptionHandler(crashes, diagnostics = logs).also { it.activate() }.uncaughtException(Thread(), error)
            assertThat(captured).isSameInstanceAs(error)
            assertThat(timeout).isEqualTo(1500)
            assertThat(crashes.crashInfo().first()).doesNotContain("SHOULD_NOT_PERSIST")
            assertThat(delegated).isEqualTo(1)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `fatal collector write or flush failure always delegates to previous handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            for (failAppend in listOf(true, false)) {
                var delegated = 0
                val fake = io.element.android.features.rageshake.impl.reporter.FakeDiagnosticLogStore()
                val logs = object : io.element.android.security.securestorage.api.SecureDiagnosticLogStore by fake {
                    override fun append(priority: Int, source: String?, message: String, throwable: Throwable?, location: String?) {
                        if (failAppend) throw IllegalStateException("collector unavailable")
                    }
                    override fun flushBlocking(timeoutMillis: Long): Boolean = error("flush unavailable")
                }
                Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
                val handler = VectorUncaughtExceptionHandler(FakeCrashDataStore(), diagnostics = logs).also { it.activate() }
                runCatching { handler.uncaughtException(Thread(), IllegalStateException("original")) }
                assertThat(delegated).isEqualTo(1)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
    @Test
    fun `activate should change the default handler`() {
        val sut = VectorUncaughtExceptionHandler(FakeCrashDataStore())
        sut.activate()
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isInstanceOf(VectorUncaughtExceptionHandler::class.java)
    }

    @Test
    fun `uncaught exception`() = runTest {
        val crashDataStore = FakeCrashDataStore()
        assertThat(crashDataStore.appHasCrashed().first()).isFalse()
        assertThat(crashDataStore.crashInfo().first()).isEmpty()
        val sut = VectorUncaughtExceptionHandler(crashDataStore)
        sut.uncaughtException(Thread(), AN_EXCEPTION)
        assertThat(crashDataStore.appHasCrashed().first()).isTrue()
        val crashInfo = crashDataStore.crashInfo().first()
        assertThat(crashInfo).isNotEmpty()
        assertThat(crashInfo).contains("Memory statuses")
        crashDataStore.resetAppHasCrashed()
        assertThat(crashDataStore.appHasCrashed().first()).isFalse()
    }

    @Test
    fun `production policy records crash occurrence without diagnostic details`() = runTest {
        val crashDataStore = FakeCrashDataStore()
        val sut = VectorUncaughtExceptionHandler(
            crashDataStore = crashDataStore,
            persistCrashDetails = false,
        )

        sut.uncaughtException(Thread("sensitive-thread-name"), AN_EXCEPTION)

        assertThat(crashDataStore.appHasCrashed().first()).isTrue()
        assertThat(crashDataStore.crashInfo().first()).isEmpty()
    }
}
