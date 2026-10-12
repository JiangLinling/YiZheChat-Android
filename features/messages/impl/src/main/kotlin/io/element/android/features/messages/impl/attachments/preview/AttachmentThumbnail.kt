/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AttachmentThumbnail(media: LocalMedia, modifier: Modifier = Modifier) {
    if (media.info.mimeType.isMimeTypeVideo()) {
        val context = LocalContext.current
        val bitmap by produceState<Bitmap?>(null, media.uri) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        context.contentResolver.loadThumbnail(media.uri, Size(192, 192), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver,
                            ContentUris.parseId(media.uri),
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), media.info.filename, modifier, contentScale = ContentScale.Crop) }
    } else {
        AsyncImage(media.uri, media.info.filename, modifier, contentScale = ContentScale.Crop)
    }
}
