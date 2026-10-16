/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.troubleshoot

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.push.impl.test.DefaultTestPush
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiagnosticPushHandlerTest {
    @Test
    fun `only current response can complete once and expired or unsolicited probes cannot report success`() = runTest {
        val handler = DiagnosticPushHandler()
        handler.state.test {
            handler.handlePush(DefaultTestPush.TEST_EVENT_ID)
            expectNoEvents()
            val old = handler.beginTest()
            val current = handler.beginTest()
            handler.handlePush(old)
            expectNoEvents()
            handler.handlePush(current)
            assertThat(awaitItem()).isEqualTo(current)
            handler.handlePush(current)
            expectNoEvents()
            val cancelled = handler.beginTest()
            handler.clearPendingTest()
            handler.handlePush(cancelled)
            expectNoEvents()
            assertThat(handler.isCurrentTest(current)).isFalse()
        }
    }

    @Test
    fun `diagnostic namespace is exact and cannot classify ordinary event IDs by a loose prefix`() {
        assertThat(DiagnosticPushHandler.isDiagnosticEvent(DiagnosticPushHandler().beginTest())).isTrue()
        assertThat(DiagnosticPushHandler.isDiagnosticEvent(DefaultTestPush.TEST_EVENT_ID)).isTrue()
        assertThat(DiagnosticPushHandler.isDiagnosticEvent(EventId("\$YIZHE_PUSH_DIAGNOSTIC_invalid"))).isFalse()
        assertThat(DiagnosticPushHandler.isDiagnosticEvent(EventId("\$ordinary_event"))).isFalse()
    }
}
