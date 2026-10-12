/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.messages.reply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.core.extensions.toSafeLength
import io.element.android.libraries.designsystem.atomic.atoms.PlaceholderAtom
import io.element.android.libraries.designsystem.icons.CompoundDrawables
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.ui.R
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnailType
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * https://www.figma.com/design/G1xy0HDZKJf5TCRFmKb5d5/Compound-Android-Components?node-id=2019-6286
 */
@Composable
fun InReplyToView(
    inReplyTo: InReplyToDetails,
    hideImage: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    when (inReplyTo) {
        is InReplyToDetails.Ready -> {
            ReplyToReadyContent(
                senderId = inReplyTo.senderId,
                senderProfile = inReplyTo.senderProfile,
                metadata = inReplyTo.metadata(hideImage),
                maxLines = maxLines,
                modifier = modifier,
            )
        }
        is InReplyToDetails.Error ->
            ReplyToErrorContent(maxLines = maxLines, modifier = modifier)
        is InReplyToDetails.Loading ->
            ReplyToLoadingContent(modifier = modifier)
    }
}

@Composable
private fun ReplyToReadyContent(
    senderId: UserId,
    senderProfile: ProfileDetails,
    metadata: InReplyToMetadata?,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val displayName = senderProfile.getDisambiguatedDisplayName(senderId)
    val paddings = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    Row(
        modifier
            .padding(paddings)
    ) {
        val a11InReplyToText = stringResource(CommonStrings.common_in_reply_to, displayName)
        ReplyToContentText(
            senderName = displayName,
            metadata = metadata,
            maxLines = maxLines,
            modifier = Modifier.semantics {
                contentDescription = a11InReplyToText
                isTraversalGroup = true
            },
        )
    }
}

@Composable
private fun ReplyToLoadingContent(
    modifier: Modifier = Modifier,
) {
    val paddings = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Row(
        modifier
            .padding(paddings)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlaceholderAtom(width = 80.dp, height = 12.dp)
            PlaceholderAtom(width = 140.dp, height = 14.dp)
        }
    }
}

@Composable
private fun ReplyToErrorContent(
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val paddings = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Row(
        modifier
            .padding(paddings)
    ) {
        Text(
            text = stringResource(CommonStrings.common_message_removed),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReplyToContentText(
    senderName: String,
    metadata: InReplyToMetadata?,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val summary = when (metadata) {
        InReplyToMetadata.Redacted -> stringResource(id = CommonStrings.common_message_removed)
        InReplyToMetadata.UnableToDecrypt -> stringResource(id = CommonStrings.common_waiting_for_decryption_key)
        InReplyToMetadata.Unavailable -> stringResource(id = R.string.reply_message_unavailable)
        // Add a limit to the text length to avoid a crash in Compose
        is InReplyToMetadata.Text -> metadata.text.toSafeLength()
        // Add a limit to the text length to avoid a crash in Compose
        is InReplyToMetadata.Thumbnail -> when (metadata.attachmentThumbnailInfo.type) {
            AttachmentThumbnailType.Image -> stringResource(CommonStrings.common_image)
            AttachmentThumbnailType.Video -> stringResource(CommonStrings.common_video)
            AttachmentThumbnailType.File -> stringResource(CommonStrings.common_file)
            AttachmentThumbnailType.Audio -> stringResource(CommonStrings.common_audio)
            AttachmentThumbnailType.Location -> stringResource(CommonStrings.common_shared_location)
            AttachmentThumbnailType.Voice -> stringResource(CommonStrings.common_voice_message)
            AttachmentThumbnailType.Poll -> metadata.text.toSafeLength()
        }
        null -> stringResource(id = CommonStrings.common_message_removed)
    }
    val text = "$senderName：$summary"
    val iconResourceId = when (metadata) {
        InReplyToMetadata.Redacted -> CompoundDrawables.ic_compound_delete
        InReplyToMetadata.UnableToDecrypt,
        InReplyToMetadata.Unavailable -> CompoundDrawables.ic_compound_time
        else -> null
    }
    val fontStyle = when (metadata) {
        is InReplyToMetadata.Informative -> FontStyle.Italic
        else -> FontStyle.Normal
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconResourceId != null) {
            Icon(
                resourceId = iconResourceId,
                tint = ElementTheme.colors.iconSecondary,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = ElementTheme.typography.fontBodySmRegular,
            fontStyle = fontStyle,
            textAlign = TextAlign.Start,
            color = ElementTheme.colors.textSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun InReplyToViewPreview(@PreviewParameter(provider = InReplyToDetailsProvider::class) inReplyTo: InReplyToDetails) = ElementPreview {
    InReplyToView(
        inReplyTo = inReplyTo,
        hideImage = false,
    )
}
