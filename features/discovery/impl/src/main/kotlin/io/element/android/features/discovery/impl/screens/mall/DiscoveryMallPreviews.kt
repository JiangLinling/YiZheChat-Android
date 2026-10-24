/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.mall

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.discovery.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text

internal enum class MallPreview { LOADING, FAILED, EMPTY, PRODUCT, CHECKOUT, ORDER, AFTER_SALE, PENDING, PRICE_CHANGED }
internal class MallPreviewProvider : PreviewParameterProvider<MallPreview> { override val values = MallPreview.entries.asSequence() }

@PreviewsDayNight
@Composable
private fun MallPreview(@PreviewParameter(MallPreviewProvider::class) state: MallPreview) = ElementPreview {
    MallLayout(R.string.screen_discovery_mall_shop, {}) {
        when (state) {
            MallPreview.LOADING -> item { MallNotice(MallOperationState(), {}, {}) }
            MallPreview.FAILED -> item { MallFailure {} }
            MallPreview.EMPTY -> item { Text("暂无记录") }
            MallPreview.PRODUCT -> item { MallCard { Text("共读手册"); Text("纸质 · ${mallMoney(1200)}"); Text("数量 1") } }
            MallPreview.CHECKOUT -> item { MallCard { Text("共读手册 · 纸质 × 1"); Text("收货人 · 13800000000"); Text("收货地址"); MallAmounts(1200, 300, 1500) } }
            MallPreview.ORDER -> mallOrderDetails(previewMallOrder())
            MallPreview.AFTER_SALE -> item { MallAfterSaleDetails(MallAfterSaleDetail(MallAfterSale(5, "applied", "refund_only", 1500),
                2, "AS-preview", "商品问题", "申请处理中", null, MallOrderStatus.PENDING_SHIPMENT, "2026-10-24 12:00")) }
            MallPreview.PENDING -> item { MallNotice(MallOperationState(MallOperationPhase.UNCERTAIN), {}, {}) }
            MallPreview.PRICE_CHANGED -> item { MallNotice(MallOperationState(MallOperationPhase.READY, "CHECKOUT_CHANGED"), {}, {}) }
        }
    }
}

private fun previewMallOrder() = MallOrderDetail(MallOrder(2, "MO-preview", MallOrderStatus.PENDING_PAYMENT, "unpaid", 1500,
    listOf(MallOrderItem(3, 4, 5, "共读手册", "纸质", 1200, 1, 1200)), "2026-10-24T12:00:00"), 1200, 300,
    "收货人", "13800000000", "收货地址", null, 6, "app", "unavailable", MallPaymentChannel.UNAVAILABLE,
    null, null, null, null, null, null, emptyList(), setOf(MallAction.CANCEL))
