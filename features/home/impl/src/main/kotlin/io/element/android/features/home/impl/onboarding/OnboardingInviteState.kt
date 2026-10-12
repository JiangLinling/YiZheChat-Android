/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.onboarding

import io.element.android.libraries.architecture.AsyncData
import io.element.android.security.dataprotection.api.OnboardingInvitation

data class OnboardingInviteState(
    val invitation: AsyncData<OnboardingInvitation>,
    val eventSink: (OnboardingInviteEvent) -> Unit,
)

sealed interface OnboardingInviteEvent {
    data object Generate : OnboardingInviteEvent
    data object Revoke : OnboardingInviteEvent
}
