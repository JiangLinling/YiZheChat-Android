/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.bugreport

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.rageshake.impl.R
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.form.textFieldState
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferenceDivider
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.components.preferences.PreferenceRow
import io.element.android.libraries.designsystem.components.preferences.PreferenceSwitch
import io.element.android.libraries.designsystem.modifiers.onTabOrEnterKeyFocusNext
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TextFieldValidity
import io.element.android.libraries.ui.strings.CommonStrings
import java.util.Locale

@Composable
fun BugReportView(
    state: BugReportState,
    onViewLogs: () -> Unit,
    onAddAttachments: () -> Unit,
    onSuccess: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eventSink = state.eventSink
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val back = { if (showDiagnostics) showDiagnostics = false else onBackClick() }
    BackHandler(enabled = showDiagnostics, onBack = back)

    Box(modifier = modifier) {
        PreferencePage(
            title = stringResource(
                if (showDiagnostics) R.string.screen_bug_report_diagnostics else CommonStrings.common_report_a_problem
            ),
            onBackClick = back,
        ) {
            val keyboardController = LocalSoftwareKeyboardController.current
            val isFormEnabled = state.sending !is AsyncAction.Loading
            var descriptionFieldState by textFieldState(stateValue = state.formState.description)

            if (!showDiagnostics) {
                PreferenceRow {
                    Text(
                        text = stringResource(R.string.screen_bug_report_intro),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                PreferenceRow {
                    TextField(
                        value = descriptionFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onTabOrEnterKeyFocusNext(LocalFocusManager.current),
                        enabled = isFormEnabled,
                        placeholder = stringResource(id = R.string.screen_bug_report_editor_placeholder),
                        onValueChange = {
                            descriptionFieldState = it
                            eventSink(BugReportEvents.SetDescription(it))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { keyboardController?.hide() }),
                        minLines = 4,
                        validity = if (state.isDescriptionInError) TextFieldValidity.Invalid else TextFieldValidity.None,
                    )
                }

                SectionTitle(stringResource(R.string.screen_bug_report_attachments_title))
                PreferenceRow {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            text = stringResource(R.string.screen_bug_report_add_media),
                            onClick = onAddAttachments,
                            enabled = state.canAddAttachments,
                            leadingIcon = IconSource.Vector(CompoundIcons.Attachment()),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                R.string.screen_bug_report_attachments_hint,
                                state.attachments.size,
                                MAX_BUG_REPORT_MEDIA_COUNT,
                            ),
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                        if (state.attachmentNotice == BugReportAttachmentNotice.SelectionTrimmed) {
                            Text(
                                text = stringResource(R.string.screen_bug_report_attachment_selection_trimmed),
                                style = ElementTheme.typography.fontBodySmRegular,
                                color = ElementTheme.colors.textCriticalPrimary,
                            )
                        }
                    }
                }
                state.attachments.forEach { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        enabled = isFormEnabled && attachment.status !is BugReportAttachmentStatus.Compressing,
                        onRemove = { eventSink(BugReportEvents.RemoveAttachment(attachment.id)) },
                    )
                }

                if (state.screenshotUri != null) {
                    PreferenceSwitch(
                        icon = CompoundIcons.Image(),
                        isChecked = state.formState.sendScreenshot,
                        onCheckedChange = { eventSink(BugReportEvents.SetSendScreenshot(it)) },
                        enabled = isFormEnabled,
                        title = stringResource(R.string.screen_bug_report_current_screenshot),
                    )
                    if (state.formState.sendScreenshot) {
                        ScreenshotPreview(state.screenshotUri)
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_bug_report_diagnostics)) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(R.string.screen_bug_report_include_logs).takeIf { state.formState.sendLogs },
                                stringResource(R.string.screen_bug_report_send_notification_settings_title).takeIf { state.formState.sendPushRules },
                            ).joinToString(" · ").ifEmpty { stringResource(R.string.screen_bug_report_no_diagnostics) }
                        )
                    },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Document())),
                    trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
                    enabled = isFormEnabled,
                    onClick = { showDiagnostics = true },
                )
            }

            if (showDiagnostics) {
                SectionTitle(stringResource(R.string.screen_bug_report_include_title))
                PreferenceSwitch(
                    icon = CompoundIcons.Document(),
                    isChecked = state.formState.sendLogs,
                    onCheckedChange = { eventSink(BugReportEvents.SetSendLog(it)) },
                    enabled = isFormEnabled,
                    title = stringResource(R.string.screen_bug_report_include_logs),
                )
                PreferenceSwitch(
                    icon = CompoundIcons.Notifications(),
                    isChecked = state.formState.sendPushRules,
                    onCheckedChange = { eventSink(BugReportEvents.SetSendPushRules(it)) },
                    enabled = isFormEnabled,
                    title = stringResource(R.string.screen_bug_report_send_notification_settings_title),
                )
            }
            if (!showDiagnostics) {
                PreferenceSwitch(
                    icon = CompoundIcons.Chat(),
                    isChecked = state.formState.canContact,
                    onCheckedChange = { eventSink(BugReportEvents.SetCanContact(it)) },
                    enabled = isFormEnabled,
                    title = stringResource(R.string.screen_bug_report_contact_me_title),
                )
            }
            if (showDiagnostics) {
                PreferenceDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_bug_report_view_logs)) },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ListBulleted())),
                    enabled = isFormEnabled,
                    onClick = onViewLogs,
                )
            }
            PreferenceRow {
                Text(
                    text = stringResource(R.string.screen_bug_report_privacy_notice),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!showDiagnostics) {
                PreferenceRow {
                    Button(
                        text = stringResource(id = CommonStrings.action_send),
                        onClick = {
                            // The text field keeps an IME-friendly local value. Reconcile it synchronously before
                            // submission so process/navigation restoration cannot send a stale presenter value.
                            eventSink(BugReportEvents.SetDescription(descriptionFieldState))
                            eventSink(BugReportEvents.SendBugReport)
                        },
                        enabled = state.submitEnabled,
                        showProgress = state.sending.isLoading(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 24.dp),
                    )
                }
            }
        }

        AsyncActionView(
            async = state.sending,
            progressDialog = { },
            onSuccess = {
                eventSink(BugReportEvents.ResetAll)
                onSuccess()
            },
            errorMessage = { error ->
                when (error) {
                    BugReportFormError.DescriptionTooShort -> stringResource(R.string.screen_bug_report_error_description_too_short)
                    else -> error.message ?: error.toString()
                }
            },
            onErrorDismiss = { eventSink(BugReportEvents.ClearError) },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    PreferenceRow {
        Text(
            text = title,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: BugReportAttachmentState,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    ListItem(
        enabled = enabled,
        leadingContent = ListItemContent.Custom {
            if (attachment.kind == BugReportAttachmentKind.Image && attachment.previewUri != null) {
                AsyncImage(
                    model = attachment.previewUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = if (attachment.kind == BugReportAttachmentKind.Video) CompoundIcons.VideoCall() else CompoundIcons.Image(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        headlineContent = { Text(attachment.displayName) },
        supportingContent = {
            Text(
                text = attachment.statusLabel(),
                color = if (attachment.status is BugReportAttachmentStatus.Failed) {
                    ElementTheme.colors.textCriticalPrimary
                } else {
                    ElementTheme.colors.textSecondary
                },
            )
        },
        trailingContent = ListItemContent.Custom {
            when (attachment.status) {
                BugReportAttachmentStatus.Compressing -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
                else -> IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        imageVector = CompoundIcons.Delete(),
                        contentDescription = stringResource(R.string.screen_bug_report_remove_attachment),
                    )
                }
            }
        },
    )
}

@Composable
private fun BugReportAttachmentState.statusLabel(): String = when (val currentStatus = status) {
    BugReportAttachmentStatus.Compressing -> stringResource(R.string.screen_bug_report_attachment_compressing)
    BugReportAttachmentStatus.Ready -> sizeBytes?.let(::formatFileSize).orEmpty()
    is BugReportAttachmentStatus.Failed -> when (currentStatus.reason) {
        BugReportAttachmentError.UnsupportedFormat -> stringResource(R.string.screen_bug_report_attachment_unsupported)
        BugReportAttachmentError.CompressionFailed -> stringResource(R.string.screen_bug_report_attachment_compression_failed)
        BugReportAttachmentError.ItemTooLarge -> stringResource(R.string.screen_bug_report_attachment_too_large)
        BugReportAttachmentError.TotalTooLarge -> stringResource(R.string.screen_bug_report_attachments_total_too_large)
    }
}

@Composable
private fun ScreenshotPreview(uri: String) {
    PreferenceRow {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            val model = ImageRequest.Builder(context)
                .data(uri)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            AsyncImage(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .clip(RoundedCornerShape(12.dp)),
                model = model,
                contentDescription = null,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val megabytes = bytes / (1024f * 1024f)
    return if (megabytes >= 1f) {
        String.format(Locale.getDefault(), "%.1f MB", megabytes)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f)
    }
}

@Preview(heightDp = 1000)
@Composable
internal fun BugReportViewDayPreview(@PreviewParameter(BugReportStateProvider::class) state: BugReportState) = ElementPreview {
    BugReportView(state, {}, {}, {}, {})
}

@Preview(heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun BugReportViewNightPreview(@PreviewParameter(BugReportStateProvider::class) state: BugReportState) = ElementPreview {
    BugReportView(state, {}, {}, {}, {})
}
