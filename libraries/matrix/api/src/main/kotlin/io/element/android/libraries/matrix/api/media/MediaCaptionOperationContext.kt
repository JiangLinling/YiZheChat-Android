/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.media

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the presenter's existing LC-15 action alongside its item's LC-16/LC-21 upload. */
class MediaCaptionOperationContext(
    val uploadOperationId: String,
    val captionOperationId: String,
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val roomIdHash: String,
) : AbstractCoroutineContextElement(Key) {
    init {
        require(uploadOperationId.length in 16..256 && captionOperationId.length in 16..256)
        require(uploadOperationId != captionOperationId)
        require(listOf(accountIdHash, matrixDeviceIdHash, roomIdHash).all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    companion object Key : CoroutineContext.Key<MediaCaptionOperationContext>
}
