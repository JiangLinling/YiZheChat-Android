/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local

import android.net.Uri
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.api.aVideoMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import kotlinx.parcelize.parcelableCreator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalMediaParcelCompatibilityTest {
    private fun sample(ownedPreview: Boolean) = LocalMedia(
        uri = Uri.parse("content://qa/owned-video"), info = aVideoMediaInfo(duration = "00:30"),
        lifecycleOperationId = "original-media-operation", lifecycleRoomIdHash = "original-room-subject",
        imageDecodeWidth = if (ownedPreview) 720 else null, imageDecodeHeight = if (ownedPreview) 480 else null,
        imageExifOrientation = if (ownedPreview) 6 else null, filePreviewLeaseToken = if (ownedPreview) "original-file-lease" else null,
    )
    // Independent reproduction of the actual R6 generated CREATOR/writeToParcel layout, not the new serializer.
    private fun writeLegacy(parcel: Parcel, media: LocalMedia) {
        parcel.writeParcelable(media.uri, 0)
        media.info.writeToParcel(parcel, 0)
        parcel.writeString(media.lifecycleOperationId)
        parcel.writeString(media.lifecycleRoomIdHash)
        for (value in listOf(media.imageDecodeWidth, media.imageDecodeHeight, media.imageExifOrientation)) {
            parcel.writeInt(if (value == null) 0 else 1)
            if (value != null) parcel.writeInt(value)
        }
        parcel.writeString(media.filePreviewLeaseToken)
    }
    @Test fun `existing draft fields load unchanged without a new playback capability`() {
        for (ownedPreview in listOf(false, true)) {
            val media = sample(ownedPreview)
            val parcel = Parcel.obtain()
            try {
                writeLegacy(parcel, media)
                parcel.setDataPosition(0)
                val restored = parcelableCreator<LocalMedia>().createFromParcel(parcel)
                assertThat(restored).isEqualTo(media)
                assertThat(restored.progressiveVideo).isNull()
                assertThat(parcel.dataAvail()).isEqualTo(0)
            } finally { parcel.recycle() }
        }
    }
    @Test fun `new draft output preserves the exact existing inline field sequence`() {
        for (ownedPreview in listOf(false, true)) {
            val media = sample(ownedPreview)
            val legacy = Parcel.obtain()
            val current = Parcel.obtain()
            try {
                writeLegacy(legacy, media)
                media.writeToParcel(current, 0)
                val oldBytes = legacy.marshall()
                val newBytes = current.marshall()
                try { assertThat(newBytes).isEqualTo(oldBytes) }
                finally { oldBytes.fill(0); newBytes.fill(0) }
            } finally { legacy.recycle(); current.recycle() }
        }
    }
}
