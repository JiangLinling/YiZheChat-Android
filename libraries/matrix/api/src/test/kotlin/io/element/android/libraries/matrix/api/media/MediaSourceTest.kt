/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.media.aMediaSource
import org.junit.Test

class MediaSourceTest {
    @Test
    fun `diagnostic description never exposes URL or encrypted descriptor`() {
        val source = MediaSource(
            url = "https://example.invalid/media?signature=private-url-signature",
            json = """{"file":{"key":{"k":"private-media-key"},"iv":"private-iv"}}""",
        )
        assertThat(source.toString()).isEqualTo("MediaSource(encrypted=true)")
        assertThat(listOf(source).toString()).doesNotContain("private-")
        assertThat(source.copy(json = null).toString()).isEqualTo("MediaSource(encrypted=false)")
        // Redaction is diagnostic-only: transport data and equality remain intact.
        assertThat(source.copy()).isEqualTo(source)
        assertThat(source.json).contains("private-media-key")
        assertThat(source.safeUrl).contains("private-url-signature")
    }

    @Test
    fun `safeUrl removes the fragment part in MXC urls`() {
        val mediaSource = aMediaSource(url = "mxc://matrix.org/url#fragment")
        assertThat(mediaSource.safeUrl).isEqualTo("mxc://matrix.org/url")
    }

    @Test
    fun `safeUrl keeps the fragment part in a non-MXC url`() {
        val mediaSource = aMediaSource(url = "https://matrix.org/url#fragment")
        assertThat(mediaSource.safeUrl).isEqualTo("https://matrix.org/url#fragment")
    }
}
