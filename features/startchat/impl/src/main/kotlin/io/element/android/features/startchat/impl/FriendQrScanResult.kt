/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import io.element.android.libraries.matrix.api.core.UserId

internal enum class FriendQrScanResult {
    Invalid,
    Self,
    Handled,
}

internal fun handleFriendQrCode(
    scanned: String,
    isMe: (UserId) -> Boolean,
    openUserProfile: (UserId) -> Unit,
): FriendQrScanResult {
    val userId = runCatching { FriendQrCode.decode(scanned) }.getOrNull() ?: return FriendQrScanResult.Invalid
    if (isMe(userId)) return FriendQrScanResult.Self
    openUserProfile(userId)
    return FriendQrScanResult.Handled
}
