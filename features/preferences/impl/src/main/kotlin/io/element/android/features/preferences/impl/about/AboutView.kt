/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.features.preferences.impl.components.SettingsNavigationRow
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
fun AboutView(
    state: AboutState,
    onInstallUpdate: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDocument by rememberSaveable { mutableStateOf<String?>(null) }
    val document = state.elementLegals.firstOrNull { it.documentId == selectedDocument }
    val back = { if (selectedDocument != null) selectedDocument = null else onBackClick() }
    BackHandler(enabled = selectedDocument != null, onBack = back)
    // Keep the presenter/download mounted while reading notices; reset only page scroll.
    key(selectedDocument) {
        PreferencePage(
            modifier = modifier,
            onBackClick = back,
            title = document?.let { stringResource(it.titleRes) } ?: stringResource(R.string.screen_yizhe_about_title),
        ) {
            if (document != null) {
                LegalDocumentContent(document)
            } else {
                VersionHeader(state.currentVersion)
                HorizontalDivider()
                AboutSectionTitle(stringResource(R.string.screen_yizhe_updates))
                UpdateContent(state, onInstallUpdate)
                HorizontalDivider()
                AboutSectionTitle(stringResource(R.string.screen_yizhe_notices))
                state.elementLegals.forEach { legal ->
                    SettingsNavigationRow(
                        title = stringResource(legal.titleRes),
                        icon = when (legal) {
                            ElementLegal.Copyright -> CompoundIcons.Info()
                            ElementLegal.AcceptableUsePolicy -> CompoundIcons.ListBulleted()
                            ElementLegal.PrivacyPolicy -> CompoundIcons.Shield()
                        },
                        onClick = { selectedDocument = legal.documentId },
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionHeader(version: String) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val isTestVersion = version.contains("debug", ignoreCase = true) || version.contains("test", ignoreCase = true)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.screen_yizhe_name), style = ElementTheme.typography.fontHeadingLgBold)
        Text(version.substringBefore('-'), color = ElementTheme.colors.textSecondary)
        if (isTestVersion) {
            Text(stringResource(R.string.screen_yizhe_test_version), color = ElementTheme.colors.textSecondary)
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.screen_yizhe_version_details)) },
        supportingContent = if (showDetails) {
            { SelectionContainer { Text(version) } }
        } else {
            null
        },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Info())),
        trailingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ChevronRight())),
        onClick = { showDetails = !showDetails },
    )
}

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = ElementTheme.typography.fontBodyMdMedium,
        color = ElementTheme.colors.textSecondary,
    )
}

@Composable
private fun UpdateContent(state: AboutState, onInstallUpdate: () -> Unit) {
    when (state.preparedUpdate) {
        is AsyncData.Loading -> {
            val context = LocalContext.current
            val progress = state.downloadProgress
            val fraction = progress?.fraction
            val verifying = fraction != null && fraction >= 1f
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            verifying -> R.string.screen_about_verifying_update
                            progress == null || progress.downloadedBytes == 0L -> R.string.screen_yizhe_preparing_download
                            else -> R.string.screen_yizhe_downloading
                        }
                    )
                )
                if (fraction == null || verifying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (progress != null && !verifying) {
                    Text(
                        if (fraction == null) {
                            stringResource(
                                R.string.screen_yizhe_download_unknown,
                                Formatter.formatShortFileSize(context, progress.downloadedBytes),
                            )
                        } else {
                            stringResource(
                                R.string.screen_yizhe_download_bytes,
                                Formatter.formatShortFileSize(context, progress.downloadedBytes),
                                Formatter.formatShortFileSize(context, progress.totalBytes),
                                (fraction * 100).toInt(),
                            )
                        },
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            }
        }
        is AsyncData.Success -> SettingsNavigationRow(
            title = stringResource(R.string.screen_about_install_update),
            summary = stringResource(R.string.screen_yizhe_install_verified),
            icon = CompoundIcons.Mobile(),
            onClick = onInstallUpdate,
        )
        is AsyncData.Failure -> SettingsNavigationRow(
            title = stringResource(R.string.screen_yizhe_download_failed),
            icon = CompoundIcons.ChatProblem(),
            onClick = {
                state.eventSink(AboutEvent.ClearUpdateError)
                state.updateCheck.dataOrNull()?.let { state.eventSink(AboutEvent.DownloadUpdate(it)) }
            },
        )
        else -> when (val update = state.updateCheck) {
            is AsyncData.Loading -> ListItem(
                headlineContent = { Text(stringResource(R.string.screen_about_checking_update)) },
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.History())),
            )
            is AsyncData.Success -> {
                val release = update.data
                SettingsNavigationRow(
                    title = if (release == null) {
                        stringResource(R.string.screen_yizhe_no_update)
                    } else {
                        stringResource(R.string.screen_about_update_available, release.versionName)
                    },
                    summary = release?.releaseNotes?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.screen_about_check_update),
                    icon = CompoundIcons.Mobile(),
                    onClick = {
                        state.eventSink(release?.let { AboutEvent.DownloadUpdate(it) } ?: AboutEvent.CheckForUpdate)
                    },
                )
            }
            else -> SettingsNavigationRow(
                title = stringResource(
                    if (update is AsyncData.Failure) R.string.screen_about_update_error else R.string.screen_about_check_update
                ),
                icon = CompoundIcons.History(),
                onClick = { state.eventSink(AboutEvent.CheckForUpdate) },
            )
        }
    }
}

@Composable
private fun LegalDocumentContent(document: ElementLegal) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.screen_yizhe_legal_date), color = ElementTheme.colors.textSecondary)
        Text(stringResource(R.string.screen_yizhe_legal_scope), color = ElementTheme.colors.textSecondary)
        HorizontalDivider()
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                stringResource(document.bodyRes).split("\n\n").forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            section.substringBefore('\n'),
                            style = ElementTheme.typography.fontBodyMdMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(section.substringAfter('\n'), style = ElementTheme.typography.fontBodyMdRegular)
                    }
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun AboutViewPreview(@PreviewParameter(AboutStateProvider::class) state: AboutState) = ElementPreview {
    AboutView(state = state, onInstallUpdate = {}, onBackClick = {})
}

@PreviewsDayNight
@Composable
internal fun AboutLegalPreview(@PreviewParameter(LegalDocumentProvider::class) document: ElementLegal) = ElementPreview {
    PreferencePage(title = stringResource(document.titleRes), onBackClick = {}) {
        LegalDocumentContent(document)
    }
}
