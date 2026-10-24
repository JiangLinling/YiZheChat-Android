/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun DiscoveryEnrollmentResultView(
    receipt: EnrollmentReceipt,
    onRefresh: () -> Unit,
    onOrder: ((String) -> Unit)?,
    onCourses: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    refreshFailed: Boolean = false,
) {
    LazyColumn(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.screen_discovery_back)) } }
        item { Text(stringResource(receipt.status.title()), style = ElementTheme.typography.fontHeadingLgBold) }
        item { Text(stringResource(receipt.status.description()), color = ElementTheme.colors.textSecondary) }
        item { EnrollmentSection(R.string.screen_discovery_enrollment_summary) {
            Text(receipt.title, style = ElementTheme.typography.fontHeadingMdBold)
            if (receipt.participantNames.isNotEmpty()) Text(receipt.participantNames.joinToString("、"))
            Text(stringResource(R.string.screen_discovery_enrollment_number, receipt.enrollmentId))
            receipt.orderId?.let { Text(stringResource(R.string.screen_discovery_enrollment_order_number, it)) }
            Text(stringResource(R.string.screen_discovery_enrollment_total, formatEnrollmentAmount(receipt.amountFen)))
        } }
        if (refreshFailed) item { Text(stringResource(R.string.screen_discovery_enrollment_refresh_failed)) }
        if (receipt.paymentChannel == EnrollmentPaymentChannel.UNAVAILABLE && receipt.status == EnrollmentReceiptStatus.PENDING_PAYMENT) item {
            Text(stringResource(R.string.screen_discovery_enrollment_payment_channel_pending), color = ElementTheme.colors.textSecondary)
        }
        if (onOrder != null) receipt.orderId?.let { orderId -> item {
            Button(onClick = { onOrder(orderId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (receipt.status == EnrollmentReceiptStatus.PENDING_PAYMENT && receipt.paymentChannel == EnrollmentPaymentChannel.READY)
                    R.string.screen_discovery_enrollment_view_payment
                    else R.string.screen_discovery_enrollment_view_order))
            }
        } }
        item { TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_discovery_enrollment_check)) } }
        item { TextButton(onClick = onCourses, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.screen_discovery_enrollment_back_courses)) } }
    }
}

internal fun EnrollmentReceiptStatus.title(): Int = when (this) {
    EnrollmentReceiptStatus.PENDING_PAYMENT -> R.string.screen_discovery_enrollment_pending_payment
    EnrollmentReceiptStatus.PROCESSING -> R.string.screen_discovery_enrollment_processing
    EnrollmentReceiptStatus.ENROLLED -> R.string.screen_discovery_enrollment_enrolled
    EnrollmentReceiptStatus.WAITLISTED -> R.string.screen_discovery_enrollment_waitlisted
    EnrollmentReceiptStatus.CLOSED -> R.string.screen_discovery_enrollment_closed
    EnrollmentReceiptStatus.REFUNDING -> R.string.screen_discovery_enrollment_refunding
    EnrollmentReceiptStatus.REFUNDED -> R.string.screen_discovery_enrollment_refunded
}
internal fun EnrollmentReceiptStatus.description(): Int = when (this) {
    EnrollmentReceiptStatus.PENDING_PAYMENT -> R.string.screen_discovery_enrollment_pending_payment_body
    EnrollmentReceiptStatus.PROCESSING -> R.string.screen_discovery_enrollment_processing_body
    EnrollmentReceiptStatus.ENROLLED -> R.string.screen_discovery_enrollment_enrolled_body
    EnrollmentReceiptStatus.WAITLISTED -> R.string.screen_discovery_enrollment_waitlisted_body
    EnrollmentReceiptStatus.CLOSED -> R.string.screen_discovery_enrollment_closed_body
    EnrollmentReceiptStatus.REFUNDING -> R.string.screen_discovery_enrollment_refunding_body
    EnrollmentReceiptStatus.REFUNDED -> R.string.screen_discovery_enrollment_refunded_body
}
