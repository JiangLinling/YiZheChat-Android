/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.messagefavourites

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import org.junit.Test

class FavouritePreviewReadinessTest {
    @Test
    fun `decrypting shell is not consumed as the favourite preview`() {
        val content = aMessageContent(body = "").copy(
            protectedMessageStatus = ProtectedMessageStatus.DECRYPTING,
            isContentProtectionAuthorized = false,
        )

        assertThat(content.isReadyForFavouritePreview()).isFalse()
    }

    @Test
    fun `authorized projection is ready for the favourite preview`() {
        val content = aMessageContent(body = "message").copy(
            protectedMessageStatus = null,
            isContentProtectionAuthorized = true,
        )

        assertThat(content.isReadyForFavouritePreview()).isTrue()
    }

    @Test
    fun `terminal protected outcome does not leave the favourite loading forever`() {
        val content = aMessageContent(body = "").copy(
            protectedMessageStatus = ProtectedMessageStatus.REVOKED,
            isContentProtectionAuthorized = false,
        )

        assertThat(content.isReadyForFavouritePreview()).isTrue()
    }
}
