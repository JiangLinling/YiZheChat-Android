/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

import kotlinx.coroutines.flow.StateFlow

/**
 * One fail-closed authorization gate for every process-memory plaintext preview.
 *
 * A successful content-envelope decryption is necessary but not sufficient: the platform, the
 * application lock and device trust must still allow presentation at the moment a consumer reads
 * the value. Implementations must never persist preview plaintext.
 */
interface ProtectedPreviewAuthorizationGate {
    val accessState: StateFlow<ProtectedPreviewAccessState>

    /**
     * Monotonic in-process generation for policy, vault and authorization changes.
     *
     * Consumers that cache an authorized projection must rebuild it whenever this changes, even
     * when the platform/app/trust booleans themselves did not change.
     */
    val authorizationRevision: StateFlow<Long>

    /** True when the device/app/trust boundary permits attempting a protected preview. */
    fun canAttemptPreview(): Boolean

    /** True only for a currently authorized decrypted projection. */
    fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean): Boolean
}

data class ProtectedPreviewAccessState(
    val platformUnlocked: Boolean,
    val applicationUnlocked: Boolean,
    val deviceTrusted: Boolean,
) {
    val allowsPreviewAttempt: Boolean
        get() = platformUnlocked && applicationUnlocked && deviceTrusted
}
