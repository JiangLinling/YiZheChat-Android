/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.element.android.libraries.localofficeviewer.api.FilePreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import java.security.MessageDigest

internal data class FilePreviewLeaseBinding(
    val operationId: String,
    val roomIdHash: String?,
    val resourceFingerprint: String,
)

internal fun LocalMedia.filePreviewLeaseBinding(): FilePreviewLeaseBinding = FilePreviewLeaseBinding(
    operationId = lifecycleOperationId,
    roomIdHash = lifecycleRoomIdHash,
    resourceFingerprint = sha256Hex(
        buildString {
            append("file-preview-resource-v1\u0000")
            append(uri)
            append('\u0000')
            append(info.mimeType)
            append('\u0000')
            append(info.filename)
            append('\u0000')
            append(info.fileSize ?: -1)
        }.encodeToByteArray()
    ),
)

@Composable
internal fun rememberBoundFilePreviewLease(
    localMedia: LocalMedia,
    expectedSecureMediaAuthority: String,
): FilePreviewLease? {
    val binding = localMedia.filePreviewLeaseBinding()
    return remember(localMedia.filePreviewLeaseToken, binding, expectedSecureMediaAuthority) {
        localMedia.filePreviewLeaseToken?.let { token ->
            FilePreviewLeaseRegistry.acquire(token, binding, expectedSecureMediaAuthority)
        }
    }
}

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
