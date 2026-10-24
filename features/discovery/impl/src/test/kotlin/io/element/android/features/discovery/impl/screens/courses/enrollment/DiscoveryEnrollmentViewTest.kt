/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package io.element.android.features.discovery.impl.screens.courses.enrollment

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.libraries.designsystem.preview.ElementPreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh")
internal class DiscoveryEnrollmentViewTest {
    @Test fun `person selection returns child ids without changing host identity`() = runAndroidComposeUiTest<ComponentActivity> {
        var selected: Set<Long>? = null
        setContent { ElementPreview {
            DiscoveryEnrollmentPersonView(enrollmentOptions(EnrollmentMode.PARENT_CHILD), emptySet(), { selected = it }, {}, {}, {})
        } }
        onNodeWithText("确认报名人（0 人）").assertIsNotEnabled()
        onNodeWithText("Child A").performClick()
        onNodeWithText("Child B").performClick()
        onNodeWithText("确认报名人（2 人）").performClick()
        runOnIdle { assertEquals(setOf(2L, 3L), selected) }
        onNodeWithText("Parent").assertDoesNotExist()
    }

    @Test fun `pending payment result retains real order and does not show enrollment success`() = runAndroidComposeUiTest<ComponentActivity> {
        var order: String? = null
        setContent { ElementPreview {
            DiscoveryEnrollmentResultView(EnrollmentReceipt(1, 10, "enrollment-1", "order-1", "Course",
                EnrollmentReceiptStatus.PENDING_PAYMENT, 12345, listOf("Parent")), {}, { order = it }, {}, {})
        } }
        onNodeWithText("待付款").assertIsDisplayed()
        onNodeWithText("报名已确认").assertDoesNotExist()
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText("查看订单并付款"))
        onNodeWithText("查看订单并付款").assertIsDisplayed().performClick()
        runOnIdle { assertEquals("order-1", order) }
    }

    @Test fun `uncertain submission only exposes result refresh`() = runAndroidComposeUiTest<ComponentActivity> {
        var checks = 0
        setContent { ElementPreview {
            DiscoveryEnrollmentView(DiscoveryEnrollmentState(phase = EnrollmentPhase.UNCERTAIN, notice = EnrollmentNotice.CHECK_RESULT),
                {}, { fail("quote must not be offered") }, { fail("submit must not be offered") }, { checks++ }, {}, {}, {}, {}, {}, {})
        } }
        onNodeWithText("刷新结果").performClick()
        onNodeWithText("确认报名").assertDoesNotExist()
        onNodeWithText("查看费用明细").assertDoesNotExist()
        runOnIdle { assertEquals(1, checks) }
    }
}
