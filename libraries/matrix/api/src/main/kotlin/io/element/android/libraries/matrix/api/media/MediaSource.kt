/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class MediaSource(
    /**
     * Url of the media.
     */
    private val url: String,
    /**
     * This is used to hold data for encrypted media.
     */
    val json: String? = null,
    /** Trusted enclosing Matrix event scope; never recovered from an MXC URL or media header. */
    val eventOrigin: MediaEventOrigin? = null,
) : Parcelable {
    // Navigation targets and SDK wrappers may be logged recursively. Neither the encrypted
    // descriptor (which contains the media key) nor a potentially signed URL is diagnostic data.
    override fun toString(): String = "MediaSource(encrypted=${json != null})"

    /**
     * A URL with invalid parts (like `#fragment`, if it's an MXC url) removed.
     */
    @IgnoredOnParcel
    val safeUrl = if (url.startsWith("mxc")) {
        // We've seen some MXC urls in the wild having some `mxc://foo/bar#auto` fragment suffix, which is invalid
        url.substringBefore("#")
    } else {
        url
    }
}

@Parcelize
data class MediaEventOrigin(val roomId: String, val eventId: String?) : Parcelable {
    init {
        require(roomId.startsWith("!") && roomId.length > 1)
        require(eventId == null || (eventId.startsWith("$") && eventId.length > 1))
    }

    override fun toString(): String = "MediaEventOrigin(canonical=${eventId != null})"
}
