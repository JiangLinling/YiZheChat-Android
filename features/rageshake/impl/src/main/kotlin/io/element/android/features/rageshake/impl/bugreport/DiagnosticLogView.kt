/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.rageshake.impl.bugreport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.features.rageshake.impl.reporter.DiagnosticBundle
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun DiagnosticLogView(bundle: DiagnosticBundle?, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("运行日志（本次提交使用同一份快照）")
                Text(bundle?.summary ?: "正在读取加密运行日志…")
                Button(text = "刷新日志", onClick = onRefresh)
                Button(text = "返回反馈", onClick = onDismiss)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(bundle?.snapshot?.events.orEmpty()) { event ->
                        Text("${event.timestampEpochMillis} [${event.priority}] ${event.source} ${event.location}\n${event.details}")
                        event.stack.chunked(4096).forEach { Text(it) }
                    }
                }
            }
        }
    }
}
