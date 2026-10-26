/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.rageshake.impl.bugreport

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.rageshake.impl.reporter.DiagnosticBundle
import io.element.android.features.rageshake.impl.reporter.DiagnosticSnapshotProvider
import io.element.android.libraries.androidutils.system.toast
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch

@ContributesNode(AppScope::class)
@AssistedInject
class BugReportNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: BugReportPresenter,
    private val pickerProvider: PickerProvider,
    private val diagnosticSnapshots: DiagnosticSnapshotProvider,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun onDone()
        fun navigateToViewLogs(basePath: String)
    }

    private val callback: Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        var bundle by remember { mutableStateOf<DiagnosticBundle?>(null) }
        var showLogs by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { bundle = diagnosticSnapshots.get(refresh = true) }
        DisposableEffect(Unit) { onDispose { diagnosticSnapshots.clear() } }
        if (showLogs) {
            DiagnosticLogView(bundle, onDismiss = { showLogs = false }, onRefresh = {
                scope.launch { bundle = diagnosticSnapshots.get(refresh = true) }
            })
        }
        val activity = LocalActivity.current
        val galleryPicker = pickerProvider.registerGalleryPicker { media ->
            state.eventSink(BugReportEvents.AddAttachments(media))
        }
        BugReportView(
            state = state,
            modifier = modifier,
            onBackClick = {
                state.eventSink(BugReportEvents.DiscardAttachments)
                navigateUp()
            },
            onSuccess = {
                activity?.toast(CommonStrings.common_report_submitted)
                callback.onDone()
            },
            onViewLogs = {
                showLogs = true
            },
            diagnosticStatus = bundle?.summary ?: "正在读取加密运行日志…",
            onAddAttachments = galleryPicker::launch,
        )
    }
}
