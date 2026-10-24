/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

private class AddressPreviewStates : PreviewParameterProvider<AddressState> {
    private val draft = MemberAddressDraft("林禾", "13800000000", "上海市", "上海市", "浦东新区", "示例路 1 号")
    private val book = MemberAddressBook(1, listOf(MemberAddress(10, draft)), listOf(AddressNameSuggestion(1, "林禾", "13800000000")))
    override val values = sequenceOf(
        AddressState(), AddressState(phase = AddressPhase.FAILED),
        AddressState(phase = AddressPhase.READY, book = book.copy(rows = emptyList())),
        AddressState(phase = AddressPhase.READY, book = book),
        AddressState(phase = AddressPhase.EDITING, book = book, draft = MemberAddressDraft(), showIssues = true),
        AddressState(phase = AddressPhase.SAVING, book = book, draft = draft),
        AddressState(phase = AddressPhase.UNCERTAIN, book = book, draft = draft),
        AddressState(phase = AddressPhase.SAVED, savedId = 10),
        AddressState(phase = AddressPhase.FAILED, savedId = 10),
    )
}

@PreviewsDayNight
@Composable
private fun AddressPreview(@PreviewParameter(AddressPreviewStates::class) state: AddressState) = ElementPreview {
    DiscoveryAddressView(state, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
