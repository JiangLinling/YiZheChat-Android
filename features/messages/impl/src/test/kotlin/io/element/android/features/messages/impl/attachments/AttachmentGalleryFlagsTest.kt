/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments

import android.net.Uri
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMediaAttachment
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentGalleryFlagsTest {
    @Test
    fun `one shot gallery hints do not change legacy persisted parcel layout`() {
        val base = aMediaAttachment(aLocalMedia(Uri.parse("content://test/legacy")))
        val hinted = base.copy(initiallySelected = false, focused = true, openEditor = true)
        val original = Parcel.obtain()
        val presentation = Parcel.obtain()
        try {
            original.writeParcelable(base, 0)
            presentation.writeParcelable(hinted, 0)
            assertThat(presentation.marshall().toList()).isEqualTo(original.marshall().toList())
            presentation.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = presentation.readParcelable<Attachment.Media>(Attachment.Media::class.java.classLoader)
            assertThat(restored).isEqualTo(base)
            assertThat(presentation.dataAvail()).isEqualTo(0)
        } finally {
            original.recycle()
            presentation.recycle()
        }
    }
}
