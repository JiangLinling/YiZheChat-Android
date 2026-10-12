/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES

/** Re-entry reuses the same LC-14 authority checks as the composer; no UI result grants send authority. */
internal suspend fun isAuthoritativeGallerySelection(
    coordinator: AppOperationalStateCoordinator,
    selection: MediaPickOperation?,
    roomIdHash: String,
    accountIdHash: String,
    matrixDeviceIdHash: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): Boolean {
    selection ?: return false
    if (selection.source != MediaPickSource.GALLERY || selection.roomIdHash != roomIdHash ||
        nowEpochMillis >= selection.deadlineEpochMillis) {
            return false
        }
    val authority = runCatchingExceptions { coordinator.operation(selection.operationId) }.getOrNull() ?: return false
    return authority.lifecycleId == FullAppLifecycleId.LC_14 &&
        authority.operationId == selection.operationId &&
        authority.subjects.roomIdHash == roomIdHash &&
        authority.subjects.accountIdHash == accountIdHash &&
        authority.subjects.matrixDeviceIdHash == matrixDeviceIdHash &&
        authority.createdAtEpochMillis == selection.createdAtEpochMillis &&
        authority.deadlineEpochMillis == selection.deadlineEpochMillis &&
        authority.status in NON_TERMINAL_RESUMABLE_STATUSES &&
        nowEpochMillis < authority.deadlineEpochMillis
}
