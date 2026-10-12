/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.api.local

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.Immutable
import io.element.android.libraries.mediaviewer.api.MediaInfo
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.TypeParceler
import kotlinx.parcelize.parcelableCreator
import kotlinx.parcelize.IgnoredOnParcel
import io.element.android.libraries.matrix.api.media.ProgressiveMediaFile
import java.util.UUID

@Parcelize
@TypeParceler<MediaInfo, StableMediaInfoParceler>
@Immutable
data class LocalMedia(
    val uri: Uri,
    val info: MediaInfo,
    /** Stable identity carried from media selection through preview and upload. */
    val lifecycleOperationId: String = UUID.randomUUID().toString(),
    /** Room subject inherited by preview/upload so one operation cannot be rebound to another room. */
    val lifecycleRoomIdHash: String? = null,
    /**
     * Oriented decode target established by image preflight. Image renderers must use this bounded size instead of
     * requesting the original dimensions. Null for non-image media.
     */
    val imageDecodeWidth: Int? = null,
    val imageDecodeHeight: Int? = null,
    /** Validated EXIF orientation (1..8), or null for non-image media. */
    val imageExifOrientation: Int? = null,
    /** Exact file-only host lease identity. Null for image, video, audio, voice, and non-preview inputs. */
    val filePreviewLeaseToken: String? = null,
    /** Process-local viewer-owned capability. Never serialized, exported, or restored after process death. */
    @IgnoredOnParcel val progressiveVideo: ProgressiveMediaFile? = null,
) : Parcelable

/** Preserve the existing inline MediaInfo wire format across full and incremental Kotlin compilation. */
internal object StableMediaInfoParceler : Parceler<MediaInfo> {
    override fun create(parcel: Parcel): MediaInfo = parcelableCreator<MediaInfo>().createFromParcel(parcel)
    override fun MediaInfo.write(parcel: Parcel, flags: Int) = writeToParcel(parcel, flags)
}
