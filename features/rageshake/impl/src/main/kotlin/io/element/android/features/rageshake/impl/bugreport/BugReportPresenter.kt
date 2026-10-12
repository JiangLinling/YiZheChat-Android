/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.bugreport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import io.element.android.features.rageshake.api.reporter.BugReportAttachment
import io.element.android.features.rageshake.api.reporter.BugReporter
import io.element.android.features.rageshake.api.reporter.BugReporterListener
import io.element.android.features.rageshake.impl.crash.CrashDataStore
import io.element.android.features.rageshake.impl.screenshot.ScreenshotHolder
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.di.annotations.AppCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

@Inject
class BugReportPresenter(
    private val bugReporter: BugReporter,
    private val crashDataStore: CrashDataStore,
    private val screenshotHolder: ScreenshotHolder,
    private val attachmentProcessor: BugReportAttachmentProcessor,
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
) : Presenter<BugReportState> {
    private class BugReporterUploadListener(
        private val sendingProgress: MutableFloatState,
        private val sendingAction: MutableState<AsyncAction<Unit>>,
    ) : BugReporterListener {
        override fun onUploadCancelled() {
            sendingProgress.floatValue = 0f
            sendingAction.value = AsyncAction.Uninitialized
        }

        override fun onUploadFailed(reason: String?) {
            sendingProgress.floatValue = 0f
            sendingAction.value = AsyncAction.Failure(Exception(reason))
        }

        override fun onProgress(progress: Int) {
            sendingProgress.floatValue = progress.toFloat() / 100
            sendingAction.value = AsyncAction.Loading
        }

        override fun onUploadSucceed() {
            sendingProgress.floatValue = 0f
            sendingAction.value = AsyncAction.Success(Unit)
        }
    }

    @Composable
    override fun present(): BugReportState {
        val screenshotUri = rememberSaveable {
            mutableStateOf(
                screenshotHolder.getFileUri()
            )
        }
        val crashInfo: String by remember {
            crashDataStore.crashInfo()
        }.collectAsState(initial = "")

        val sendingProgress = remember {
            mutableFloatStateOf(0f)
        }
        val sendingAction: MutableState<AsyncAction<Unit>> = remember {
            mutableStateOf(AsyncAction.Uninitialized)
        }
        val formState: MutableState<BugReportFormState> = rememberSaveable {
            mutableStateOf(BugReportFormState.Default)
        }
        val attachments = remember { mutableStateOf<List<BugReportAttachmentState>>(emptyList()) }
        val processedAttachments = remember { mutableMapOf<String, ProcessedBugReportAttachment>() }
        val attachmentNotice = remember { mutableStateOf<BugReportAttachmentNotice?>(null) }
        val uploadListener = BugReporterUploadListener(sendingProgress, sendingAction)

        fun removeAttachment(id: String) {
            processedAttachments.remove(id)?.let(attachmentProcessor::release)
            attachments.value = attachments.value.filterNot { it.id == id }
        }

        fun addAttachments(media: List<io.element.android.libraries.mediapickers.api.PickedMedia>) {
            val availableSlots = (MAX_BUG_REPORT_MEDIA_COUNT - attachments.value.size).coerceAtLeast(0)
            val selected = media.take(availableSlots)
            attachmentNotice.value = BugReportAttachmentNotice.SelectionTrimmed.takeIf { media.size > selected.size }
            if (selected.isEmpty()) return

            val pending = selected.map { pickedMedia ->
                BugReportAttachmentState(
                    id = UUID.randomUUID().toString(),
                    displayName = pickedMedia.uri.lastPathSegment ?: "附件",
                    kind = if (pickedMedia.mimeType?.startsWith("video/") == true) {
                        BugReportAttachmentKind.Video
                    } else {
                        BugReportAttachmentKind.Image
                    },
                    status = BugReportAttachmentStatus.Compressing,
                )
            }
            attachments.value += pending
            appCoroutineScope.launch {
                selected.zip(pending).forEach { (pickedMedia, pendingItem) ->
                    val result = attachmentProcessor.process(
                        uri = pickedMedia.uri,
                        mimeType = pickedMedia.mimeType,
                    )
                    val processed = result.getOrNull()
                    val totalBytes = processedAttachments.values.sumOf { it.attachment.file.length() } +
                        (processed?.attachment?.file?.length() ?: 0L)
                    val updatedItem = when {
                        processed != null && totalBytes <= MAX_BUG_REPORT_MEDIA_TOTAL_BYTES -> {
                            processedAttachments[pendingItem.id] = processed
                            pendingItem.copy(
                                displayName = processed.displayName,
                                kind = processed.kind,
                                sizeBytes = processed.attachment.file.length(),
                                previewUri = processed.attachment.file.absolutePath,
                                status = BugReportAttachmentStatus.Ready,
                            )
                        }
                        processed != null -> {
                            attachmentProcessor.release(processed)
                            pendingItem.copy(status = BugReportAttachmentStatus.Failed(BugReportAttachmentError.TotalTooLarge))
                        }
                        else -> pendingItem.copy(
                            status = BugReportAttachmentStatus.Failed(result.exceptionOrNull().toAttachmentError())
                        )
                    }
                    if (attachments.value.any { it.id == pendingItem.id }) {
                        attachments.value = attachments.value.map { current ->
                            if (current.id == pendingItem.id) updatedItem else current
                        }
                    } else {
                        processedAttachments.remove(pendingItem.id)?.let(attachmentProcessor::release)
                    }
                }
            }
        }

        fun handleEvent(event: BugReportEvents) {
            when (event) {
                BugReportEvents.SendBugReport -> {
                    val trimmedDescription = formState.value.description.trim()
                    if (trimmedDescription.length < 10) {
                        sendingAction.value = AsyncAction.Failure(BugReportFormError.DescriptionTooShort)
                    } else {
                        sendingAction.value = AsyncAction.Loading
                        appCoroutineScope.sendBugReport(
                            formState = formState.value.copy(description = trimmedDescription),
                            hasCrashLogs = crashInfo.isNotEmpty(),
                            screenshotUri = screenshotUri.value,
                            attachments = processedAttachments.values.map { it.attachment },
                            listener = uploadListener,
                        )
                    }
                }
                BugReportEvents.ResetAll -> {
                    processedAttachments.values.forEach(attachmentProcessor::release)
                    processedAttachments.clear()
                    attachments.value = emptyList()
                    appCoroutineScope.resetAll()
                }
                BugReportEvents.DiscardAttachments -> {
                    processedAttachments.values.forEach(attachmentProcessor::release)
                    processedAttachments.clear()
                    attachments.value = emptyList()
                }
                is BugReportEvents.AddAttachments -> addAttachments(event.media)
                is BugReportEvents.RemoveAttachment -> removeAttachment(event.id)
                BugReportEvents.ClearAttachmentNotice -> attachmentNotice.value = null
                is BugReportEvents.SetDescription -> updateFormState(formState) {
                    copy(description = event.description)
                }
                is BugReportEvents.SetCanContact -> updateFormState(formState) {
                    copy(canContact = event.canContact)
                }
                is BugReportEvents.SetSendLog -> updateFormState(formState) {
                    copy(sendLogs = event.sendLog)
                }
                is BugReportEvents.SetSendScreenshot -> updateFormState(formState) {
                    copy(sendScreenshot = event.sendScreenshot)
                }
                is BugReportEvents.SetSendPushRules -> updateFormState(formState) {
                    copy(sendPushRules = event.sendPushRules)
                }
                BugReportEvents.ClearError -> {
                    sendingProgress.floatValue = 0f
                    sendingAction.value = AsyncAction.Uninitialized
                }
            }
        }

        return BugReportState(
            hasCrashLogs = crashInfo.isNotEmpty(),
            sendingProgress = sendingProgress.floatValue,
            sending = sendingAction.value,
            formState = formState.value,
            screenshotUri = screenshotUri.value,
            attachments = attachments.value,
            attachmentNotice = attachmentNotice.value,
            eventSink = ::handleEvent,
        )
    }

    private fun updateFormState(formState: MutableState<BugReportFormState>, operation: BugReportFormState.() -> BugReportFormState) {
        formState.value = operation(formState.value)
    }

    private fun CoroutineScope.sendBugReport(
        formState: BugReportFormState,
        hasCrashLogs: Boolean,
        screenshotUri: String?,
        attachments: List<BugReportAttachment>,
        listener: BugReporterListener,
    ) = launch {
        var compressedScreenshot: ProcessedBugReportAttachment? = null
        try {
            val finalAttachments = attachments.toMutableList()
            if (formState.sendScreenshot && screenshotUri != null) {
                val screenshotAttachment = attachmentProcessor.process(
                    uri = screenshotUri.toUri(),
                    mimeType = "image/png",
                    fallbackName = "screenshot.png",
                ).getOrElse {
                    listener.onUploadFailed("截图压缩失败，请关闭“当前截图”后重试。")
                    return@launch
                }
                compressedScreenshot = screenshotAttachment
                if (finalAttachments.sumOf { it.file.length() } + screenshotAttachment.attachment.file.length() > MAX_BUG_REPORT_MEDIA_TOTAL_BYTES) {
                    listener.onUploadFailed("压缩后的附件总计超过 20 MB，请删除部分附件后重试。")
                    return@launch
                }
                finalAttachments += screenshotAttachment.attachment
            }
            bugReporter.sendBugReport(
                withDevicesLogs = formState.sendLogs,
                withCrashLogs = hasCrashLogs && formState.sendLogs,
                withScreenshot = false,
                problemDescription = formState.description,
                canContact = formState.canContact,
                sendPushRules = formState.sendPushRules,
                attachments = finalAttachments,
                listener = listener,
            )
        } finally {
            compressedScreenshot?.let(attachmentProcessor::release)
        }
    }

    private fun CoroutineScope.resetAll() = launch {
        screenshotHolder.reset()
        crashDataStore.reset()
    }

    private fun Throwable?.toAttachmentError(): BugReportAttachmentError = when (this) {
        BugReportAttachmentFailure.UnsupportedFormat -> BugReportAttachmentError.UnsupportedFormat
        BugReportAttachmentFailure.CompressedFileTooLarge -> BugReportAttachmentError.ItemTooLarge
        else -> BugReportAttachmentError.CompressionFailed
    }
}
