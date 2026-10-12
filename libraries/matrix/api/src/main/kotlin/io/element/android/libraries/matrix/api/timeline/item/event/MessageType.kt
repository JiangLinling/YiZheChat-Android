/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.timeline.item.event

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.media.AudioDetails
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.room.location.AssetType

@Immutable
sealed interface MessageType

@Immutable
sealed interface MessageTypeWithAttachment : MessageType {
    val filename: String
    val caption: String?
    val formattedCaption: FormattedBody?

    val bestDescription: String
        get() = caption ?: filename
}

data class EmoteMessageType(
    val body: String,
    val formatted: FormattedBody?
) : MessageType

data class ImageMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: ImageInfo?
) : MessageTypeWithAttachment

// FIXME This is never used in production code.
data class StickerMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: ImageInfo?
) : MessageTypeWithAttachment

data class LocationMessageType(
    val body: String,
    val geoUri: String,
    val description: String?,
    val assetType: AssetType?,
) : MessageType

data class AudioMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: AudioInfo?,
) : MessageTypeWithAttachment

data class VoiceMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: AudioInfo?,
    val details: AudioDetails?,
) : MessageTypeWithAttachment

data class VideoMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: VideoInfo?,
    val segmentedVideo: SegmentedVideoCandidate? = null,
) : MessageTypeWithAttachment

data class SegmentedVideoCandidate(
    val schema: String,
    val assetId: String,
    val mediaVersion: Int,
    val roomIdSha256Hex: String,
    val contentContractId: String,
    val contentPolicyVersion: Long,
    val expiresAtEpochMillis: Long,
    val container: String,
    val codecs: List<String>,
    val durationUs: Long,
    val totalPlainBytes: Long,
    val init: SegmentedVideoResource,
    val segments: List<SegmentedVideoResource>,
    val resourceCount: Int,
)

data class SegmentedVideoResource(
    val resourceId: String,
    val contentId: String,
    val kind: String,
    val protectedContentKind: String,
    val index: Int,
    val startUs: Long,
    val durationUs: Long,
    val plainLength: Long,
    val immutableContextSha256Hex: String,
    val locator: SegmentedVideoLocator,
    val declaredIndependentRandomAccess: Boolean,
)

data class SegmentedVideoLocator(
    val authority: String,
    val mxcUri: String,
    val outerCipherSha256Hex: String,
    val outerCipherLength: Long,
    val encryptedFile: SegmentedVideoEncryptedFile,
)

data class SegmentedVideoEncryptedFile(
    val version: String,
    val algorithm: String,
    val keyType: String,
    val keyOperations: Set<String>,
    val extractable: Boolean,
    val keyBase64Url: String,
    val ivBase64: String,
) {
    override fun toString(): String =
        "SegmentedVideoEncryptedFile(version=$version, algorithm=$algorithm, keyType=$keyType, " +
            "keyOperations=$keyOperations, extractable=$extractable, keyBase64Url=<redacted>, ivBase64=<redacted>)"
}

data class FileMessageType(
    override val filename: String,
    override val caption: String?,
    override val formattedCaption: FormattedBody?,
    val source: MediaSource,
    val info: FileInfo?
) : MessageTypeWithAttachment

data class NoticeMessageType(
    val body: String,
    val formatted: FormattedBody?
) : MessageType

data class TextMessageType(
    val body: String,
    val formatted: FormattedBody?
) : MessageType

data class OtherMessageType(
    val msgType: String,
    val body: String,
) : MessageType
