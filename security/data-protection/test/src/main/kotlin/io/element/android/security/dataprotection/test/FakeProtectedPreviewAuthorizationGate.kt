/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.ProtectedPreviewAccessState
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeProtectedPreviewAuthorizationGate(
    allowsPreview: Boolean = true,
) : ProtectedPreviewAuthorizationGate {
    private val mutableState = MutableStateFlow(
        ProtectedPreviewAccessState(
            platformUnlocked = allowsPreview,
            applicationUnlocked = allowsPreview,
            deviceTrusted = allowsPreview,
        )
    )
    override val accessState: StateFlow<ProtectedPreviewAccessState> = mutableState
    private val mutableAuthorizationRevision = MutableStateFlow(0L)
    override val authorizationRevision: StateFlow<Long> = mutableAuthorizationRevision

    override fun canAttemptPreview(): Boolean = accessState.value.allowsPreviewAttempt

    override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean): Boolean =
        contentProtectionAuthorized && canAttemptPreview()

    fun requestAuthorizationRefresh() {
        mutableAuthorizationRevision.value += 1L
    }
}
