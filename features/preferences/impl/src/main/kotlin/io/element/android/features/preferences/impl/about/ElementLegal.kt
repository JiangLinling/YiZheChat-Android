/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2021-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import androidx.annotation.StringRes
import io.element.android.features.preferences.impl.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

sealed class ElementLegal(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val documentId: String,
) {
    data object Copyright : ElementLegal(R.string.screen_yizhe_copyright_title, R.string.screen_yizhe_copyright_body, "copyright")
    data object AcceptableUsePolicy : ElementLegal(R.string.screen_yizhe_usage_title, R.string.screen_yizhe_usage_body, "usage")
    data object PrivacyPolicy : ElementLegal(R.string.screen_yizhe_privacy_title, R.string.screen_yizhe_privacy_body, "privacy")
}

fun getAllLegals(): ImmutableList<ElementLegal> {
    return persistentListOf(
        ElementLegal.Copyright,
        ElementLegal.AcceptableUsePolicy,
        ElementLegal.PrivacyPolicy,
    )
}
