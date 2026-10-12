/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.mediapickers.api.RecentImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun RecentImagesRow(images: ImmutableList<RecentImage>, eventSink: (MessageComposerEvent) -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(images.last().uri) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= images.size - 6 }
            .distinctUntilChanged().collect { nearEnd ->
                if (nearEnd) eventSink(MessageComposerEvent.LoadMoreRecentImages(images.last().uri))
            }
    }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = listState,
            // The chat bottom sheet requests intrinsic height; LazyRow must answer without subcomposition.
            modifier = Modifier.weight(1f).height(88.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(images, key = { _, image -> image.uri.toString() }) { index, image ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(image.uri).size(256)
                        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                    contentDescription = stringResource(R.string.screen_room_recent_image_item, index + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { eventSink(MessageComposerEvent.SelectRecentImage(image.uri)) },
                    onError = { eventSink(MessageComposerEvent.RecentImageUnavailable(image.uri)) },
                )
            }
        }
        IconButton(onClick = { eventSink(MessageComposerEvent.DismissRecentImages) }, modifier = Modifier.size(48.dp)) {
            Icon(CompoundIcons.Close(), stringResource(R.string.screen_room_recent_images_dismiss), tint = ElementTheme.colors.iconSecondary)
        }
    }
}

@PreviewsDayNight
@Composable
internal fun RecentImagesRowPreview() = ElementPreview {
    RecentImagesRow((1..12).map { RecentImage(Uri.parse("content://media/external/images/media/$it"), 0) }.toImmutableList(), {})
}
