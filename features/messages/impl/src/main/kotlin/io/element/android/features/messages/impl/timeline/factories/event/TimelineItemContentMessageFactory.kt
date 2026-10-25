/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import android.text.style.URLSpan
import androidx.core.text.getSpans
import androidx.core.text.toSpannable
import dev.zacsweers.metro.Inject
import io.element.android.features.location.api.Location
import io.element.android.features.messages.api.timeline.HtmlConverterProvider
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemAudioContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEmoteContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLocationContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemNoticeContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStickerContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.model.event.parseProtectedRelayBody
import io.element.android.features.messages.impl.utils.TextPillificationHelper
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.androidutils.text.safeLinkify
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LocationMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.OtherMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import io.element.android.libraries.mediaviewer.api.util.FileExtensionExtractor
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jsoup.nodes.Document
import kotlin.time.Duration

private const val MIN_IMAGE_SIZE = 1L
private const val MAX_IMAGE_SIZE = 10_000L
private const val MIN_ASPECT_RATIO = 0.001f
private const val MAX_ASPECT_RATIO = 10f

@Inject
class TimelineItemContentMessageFactory(
    private val fileSizeFormatter: FileSizeFormatter,
    private val fileExtensionExtractor: FileExtensionExtractor,
    private val htmlConverterProvider: HtmlConverterProvider,
    private val permalinkParser: PermalinkParser,
    private val textPillificationHelper: TextPillificationHelper,
    private val stringProvider: StringProvider,
) {
    fun create(
        content: MessageContent,
        senderId: UserId,
        senderProfile: ProfileDetails,
        eventId: EventId?,
    ): TimelineItemEventContent {
        val protectedStatusLabel = content.protectedMessageStatus?.userVisibleLabelRes()?.let(stringProvider::getString)
        if (protectedStatusLabel != null) {
            return TimelineItemTextContent(
                body = protectedStatusLabel,
                htmlDocument = null,
                formattedBody = protectedStatusLabel,
                isEdited = false,
                isBurnAfterRead = content.protectedMessageStatus == ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
                isBurnAfterReadUnopened = content.protectedMessageStatus == ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED,
                isProtectedContentShell = true,
                isTerminalProtectedContentShell = content.protectedMessageStatus == ProtectedMessageStatus.REVOKED ||
                    content.protectedMessageStatus == ProtectedMessageStatus.EXPIRED,
            )
        }
        return when (val messageType = content.type) {
            is EmoteMessageType -> {
                val senderDisambiguatedDisplayName = senderProfile.getDisambiguatedDisplayName(senderId)
                val emoteBody = "* $senderDisambiguatedDisplayName ${messageType.body.trimEnd()}"
                val dom = messageType.formatted?.toHtmlDocument(
                    permalinkParser = permalinkParser,
                    prefix = "* $senderDisambiguatedDisplayName",
                )
                val formattedBody = dom?.let(::parseHtml)
                    ?: textPillificationHelper.pillify(emoteBody).safeLinkify()
                TimelineItemEmoteContent(
                    body = emoteBody,
                    htmlDocument = dom,
                    formattedBody = formattedBody,
                    isEdited = content.isEdited,
                )
            }
            is ImageMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                // Coerce the image sizes and prevent invalid aspect ratios, which can cause crashes
                val width = messageType.info?.width?.coerceIn(MIN_IMAGE_SIZE, MAX_IMAGE_SIZE)
                val height = messageType.info?.height?.coerceIn(MIN_IMAGE_SIZE, MAX_IMAGE_SIZE)
                val aspectRatio = aspectRatioOf(width, height)?.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
                TimelineItemImageContent(
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    mediaSource = messageType.source,
                    thumbnailSource = messageType.info?.thumbnailSource,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.OctetStream,
                    blurhash = messageType.info?.blurhash,
                    width = width?.toInt(),
                    height = height?.toInt(),
                    thumbnailWidth = messageType.info?.thumbnailInfo?.width?.coerceIn(MIN_IMAGE_SIZE, MAX_IMAGE_SIZE)?.toInt(),
                    thumbnailHeight = messageType.info?.thumbnailInfo?.height?.coerceIn(MIN_IMAGE_SIZE, MAX_IMAGE_SIZE)?.toInt(),
                    aspectRatio = aspectRatio,
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtensionExtractor.extractFromName(messageType.filename),
                    isBurnAfterRead = content.isBurnAfterRead,
                    mediaAlbum = content.protectedMediaAlbum,
                    localMediaProgress = content.localMediaProgress,
                    localPreviewUri = messageType.source.safeUrl.takeIf(::isLocalPreviewUri),
                )
            }
            is StickerMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                val aspectRatio = aspectRatioOf(messageType.info?.width, messageType.info?.height)
                TimelineItemStickerContent(
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    mediaSource = messageType.source,
                    thumbnailSource = messageType.info?.thumbnailSource,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.OctetStream,
                    blurhash = messageType.info?.blurhash,
                    width = messageType.info?.width?.toInt(),
                    height = messageType.info?.height?.toInt(),
                    aspectRatio = aspectRatio,
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtensionExtractor.extractFromName(messageType.filename)
                )
            }
            is LocationMessageType -> {
                val location = Location.fromGeoUri(messageType.geoUri)
                val body = messageType.body.trimEnd()
                if (location == null) {
                    TimelineItemTextContent(
                        body = body,
                        htmlDocument = null,
                        formattedBody = body,
                        isEdited = content.isEdited,
                        isBurnAfterRead = content.isBurnAfterRead,
                    )
                } else {
                    TimelineItemLocationContent(
                        description = messageType.description,
                        senderId = senderId,
                        senderProfile = senderProfile,
                        assetType = messageType.assetType,
                        mode = TimelineItemLocationContent.Mode.Static(location = location),
                        isBurnAfterRead = content.isBurnAfterRead,
                    )
                }
            }
            is VideoMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                val aspectRatio = aspectRatioOf(messageType.info?.width, messageType.info?.height)
                TimelineItemVideoContent(
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    thumbnailSource = messageType.info?.thumbnailSource,
                    mediaSource = messageType.source,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.OctetStream,
                    width = messageType.info?.width?.toInt(),
                    height = messageType.info?.height?.toInt(),
                    thumbnailWidth = messageType.info?.thumbnailInfo?.width?.toInt(),
                    thumbnailHeight = messageType.info?.thumbnailInfo?.height?.toInt(),
                    duration = messageType.info?.duration ?: Duration.ZERO,
                    blurHash = messageType.info?.blurhash,
                    aspectRatio = aspectRatio,
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtensionExtractor.extractFromName(messageType.filename),
                    isBurnAfterRead = content.isBurnAfterRead,
                    mediaAlbum = content.protectedMediaAlbum,
                    localMediaProgress = content.localMediaProgress,
                    localPreviewUri = messageType.source.safeUrl.takeIf(::isLocalPreviewUri),
                )
            }
            is AudioMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                TimelineItemAudioContent(
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    mediaSource = messageType.source,
                    duration = messageType.info?.duration ?: Duration.ZERO,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.OctetStream,
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtensionExtractor.extractFromName(messageType.filename),
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
            is VoiceMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                TimelineItemVoiceContent(
                    eventId = eventId,
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    mediaSource = messageType.source,
                    duration = messageType.info?.duration ?: Duration.ZERO,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.OctetStream,
                    waveform = messageType.details?.waveform?.toImmutableList() ?: persistentListOf(),
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtensionExtractor.extractFromName(messageType.filename),
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
            is FileMessageType -> {
                val dom = messageType.formattedCaption?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedCaption = dom?.let(::parseHtml)
                    ?: messageType.caption?.withLinks()
                val fileExtension = fileExtensionExtractor.extractFromName(messageType.filename)
                TimelineItemFileContent(
                    filename = messageType.filename,
                    fileSize = messageType.info?.size ?: 0,
                    caption = messageType.caption?.trimEnd(),
                    formattedCaption = formattedCaption,
                    isEdited = content.isEdited,
                    thumbnailSource = messageType.info?.thumbnailSource,
                    mediaSource = messageType.source,
                    mimeType = messageType.info?.mimetype ?: MimeTypes.fromFileExtension(fileExtension),
                    eventId = eventId?.value,
                    formattedFileSize = fileSizeFormatter.format(messageType.info?.size ?: 0),
                    fileExtension = fileExtension,
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
            is NoticeMessageType -> {
                val body = messageType.body.trimEnd()
                val dom = messageType.formatted?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedBody = dom?.let(::parseHtml)
                    ?: textPillificationHelper.pillify(body).safeLinkify()
                val htmlDocument = messageType.formatted?.toHtmlDocument(permalinkParser = permalinkParser)
                TimelineItemNoticeContent(
                    body = body,
                    htmlDocument = htmlDocument,
                    formattedBody = formattedBody,
                    isEdited = content.isEdited,
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
            is TextMessageType -> {
                val body = messageType.body.trimEnd()
                if (content.isContentProtectionAuthorized) {
                    parseProtectedRelayBody(body, content.isEdited, content.protectedRelayEntries)?.let { return it }
                }
                val dom = messageType.formatted?.toHtmlDocument(permalinkParser = permalinkParser)
                val formattedBody = dom?.let(::parseHtml)
                    ?: textPillificationHelper.pillify(body).safeLinkify()
                val htmlDocument = messageType.formatted?.toHtmlDocument(permalinkParser = permalinkParser)
                TimelineItemTextContent(
                    body = body,
                    htmlDocument = htmlDocument,
                    formattedBody = formattedBody,
                    isEdited = content.isEdited,
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
            is OtherMessageType -> {
                val body = messageType.body.trimEnd()
                TimelineItemTextContent(
                    body = body,
                    htmlDocument = null,
                    formattedBody = textPillificationHelper.pillify(body).safeLinkify(),
                    isEdited = content.isEdited,
                    isBurnAfterRead = content.isBurnAfterRead,
                )
            }
        }
    }

    private fun aspectRatioOf(width: Long?, height: Long?): Float? {
        val result = if (height != null && width != null) {
            width.toFloat() / height.toFloat()
        } else {
            null
        }

        return result?.takeIf { it.isFinite() }
    }

    private fun parseHtml(document: Document): CharSequence? {
        return htmlConverterProvider.provide()
            .fromDocumentToSpans(document)
            .let { textPillificationHelper.pillify(it) }
            .safeLinkify()
    }
}

internal fun MessageContent.toTimelineItemClipboardSource(eventId: EventId?): MessageClipboardSource? {
    if (isBurnAfterRead || protectedMessageStatus != null) return null
    val source = clipboardSource
    return when {
        source is MessageClipboardSource.Protected &&
            isContentProtectionAuthorized &&
            eventId?.value == source.eventId -> source
        source is MessageClipboardSource.Unprotected && !isContentProtectionAuthorized -> source
        else -> null
    }
}

private fun isLocalPreviewUri(value: String): Boolean = value.startsWith("content:") || value.startsWith("file:")

private fun ProtectedMessageStatus.userVisibleLabelRes(): Int? = when (this) {
    ProtectedMessageStatus.BURN_AFTER_READ_UNOPENED -> R.string.screen_room_burn_after_read_unopened
    ProtectedMessageStatus.REVOKED -> R.string.screen_room_burn_after_read_viewed
    ProtectedMessageStatus.EXPIRED -> R.string.screen_room_burn_after_read_expired
    ProtectedMessageStatus.LEGACY_BLOCKED -> R.string.screen_room_protected_legacy_blocked
    ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE -> R.string.screen_room_protected_temporarily_unavailable
    ProtectedMessageStatus.SEND_BINDING_PENDING -> io.element.android.features.messages.impl.R.string.screen_room_send_binding_pending
    ProtectedMessageStatus.LICENSE_AUTHORIZATION_FAILED -> R.string.screen_room_protected_authorization_failed
    ProtectedMessageStatus.VAULT_LOCKED -> R.string.screen_room_protected_vault_locked
    ProtectedMessageStatus.DEVICE_SECURITY_UNAVAILABLE -> R.string.screen_room_protected_device_security_unavailable
    ProtectedMessageStatus.ACCESS_DENIED -> R.string.screen_room_protected_access_denied
    ProtectedMessageStatus.INTEGRITY_REJECTED -> R.string.screen_room_protected_integrity_rejected
    ProtectedMessageStatus.ATTACHMENT_DECRYPTING -> R.string.screen_room_protected_attachment_decrypting
    ProtectedMessageStatus.DECRYPTING -> null
}

@Suppress("USELESS_ELVIS")
private fun String.withLinks(): CharSequence? {
    // Note: toSpannable() can return null when running unit tests
    val spannable = safeLinkify().toSpannable() ?: return null
    return spannable.takeIf { spannable.getSpans<URLSpan>(0, length).isNotEmpty() }
}
