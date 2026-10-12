/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import androidx.compose.material3.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.security.contentprotection.api.PrivateResourceRecord
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import io.element.android.libraries.androidutils.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommonInfoPanel(
    repository: CommonInfoRepository,
    onDismiss: () -> Unit,
    onInsert: (CommonInfoItem, (Result<Unit>) -> Unit) -> Unit,
    onSend: (CommonInfoItem, (Result<Unit>) -> Unit) -> Unit,
    initialItem: CommonInfoItem? = null,
    canSave: () -> Boolean = { true },
    onSaved: () -> Unit = {},
    embedded: Boolean = false,
    onEditorChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenComposerKeyboard: () -> Unit = {},
) {
    var entries by remember { mutableStateOf(emptyList<CommonInfoItem>()) }
    var editor by remember { mutableStateOf(initialItem) }
    var indexRecord by remember { mutableStateOf<PrivateResourceRecord?>(null) }
    var editorRecord by remember { mutableStateOf<PrivateResourceRecord?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var sendingId by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var managedId by remember { mutableStateOf<String?>(null) }
    val revision by repository.authorizationRevision.collectAsState()
    val pending by repository.pending.collectAsState()
    val latestDisplay by repository.display.collectAsState()
    val openingRevision = remember { revision }
    val openingHeatTime = remember { System.currentTimeMillis() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val insertedMessage = stringResource(R.string.screen_common_inserted)
    val sentMessage = stringResource(R.string.screen_common_sent)
    val insertFailedMessage = stringResource(R.string.screen_common_insert_failed)
    val sendFailedMessage = stringResource(R.string.screen_common_send_failed)
    DisposableEffect(Unit) { onDispose { onEditorChanged(false) } }
    LaunchedEffect(editor?.id, searching) {
        onEditorChanged(editor != null || searching)
        if (editor != null) LifecycleStageTiming.point("common_editor_shown", scope = if (editor?.bodyRevision == 0L) "new" else "edit")
    }
    LaunchedEffect(pending) {
        pending.filterNot { it.failed || it.action == "use" }.forEach { LifecycleStageTiming.point("common_pending_visible", operationId = it.operationId) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { repository.clearViewCache(); onDismiss() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        if (revision != openingRevision) {
            entries = emptyList(); editor = null; indexRecord = null; editorRecord = null; query = ""
            onDismiss()
        }
    }
    fun load() {
        if (loading) return
        loading = true; failed = false
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.loadForDisplay() }
                if (repository.authorizationRevision.value == openingRevision) { entries = loaded.items; indexRecord = loaded.record }
                LifecycleStageTiming.point("common_panel_ready")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; LifecycleStageTiming.point("common_refresh_failed", scope = "load") }
            finally { loading = false }
        }
    }
    LaunchedEffect(latestDisplay) {
        val value = latestDisplay
        if (value == null) {
            val previouslyDisplayed = indexRecord != null
            entries = emptyList(); indexRecord = null; managedId = null
            // Expiry may clear the repository first and cancel the per-record renewal effect.
            // Keep the security clear, then obtain a new signed head rather than showing a false empty list.
            if (previouslyDisplayed && repository.authorizationRevision.value == openingRevision) load()
        } else if (repository.authorizationRevision.value == openingRevision) {
            entries = value.items; indexRecord = value.record
        }
    }
    fun openEditor(item: CommonInfoItem) {
        val started = System.nanoTime()
        editor = item.copy(content = "", recipient = "", phone = "")
        editingId = item.id; editorRecord = null; managedId = null; failed = false
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.editForDisplay(item) }
                if (editingId == item.id && repository.authorizationRevision.value == openingRevision) {
                    editor = loaded.item.copy(content = loaded.item.sendText(), recipient = "", phone = "",
                        kind = if (loaded.item.kind == CommonInfoKind.ADDRESS) CommonInfoKind.TEXT else loaded.item.kind)
                    editorRecord = loaded.record
                    LifecycleStageTiming.interval("common_editor_ready", started, System.nanoTime(), "", "", "")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (editingId == item.id) failed = true }
            finally { if (editingId == item.id) editingId = null }
        }
    }
    fun enqueue(action: String, item: CommonInfoItem = CommonInfoItem()) {
        LifecycleStageTiming.observed("common_ui_action", scope = action)
        scope.launch {
            try { repository.enqueue(action, item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    LaunchedEffect(repository) { repository.startSync(); if (initialItem == null) load() }
    val displayedRecord = editorRecord ?: indexRecord
    LaunchedEffect(displayedRecord) {
        val record = displayedRecord?.takeIf { it.revision > 0 && !it.deleted } ?: return@LaunchedEffect
        while (repository.authorizationRevision.value == openingRevision) {
            val remaining = repository.remaining(record)
            if (remaining <= 0) {
                if (editorRecord != null) { editor = null; editorRecord = null; editingId = null }
                entries = emptyList(); indexRecord = null; repository.clearViewCache(); load()
                return@LaunchedEffect
            }
            delay((remaining - 8_000).coerceAtLeast(1))
            try { withContext(Dispatchers.IO) { repository.renew(record) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                LifecycleStageTiming.point("common_refresh_failed", scope = "renew")
                delay(repository.remaining(record).coerceAtLeast(1))
            }
        }
    }
    val content: @Composable () -> Unit = {
        Column(modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor != null) {
                    IconButton(onClick = {
                        focus.clearFocus(force = true); keyboard?.hide()
                        editingId = null
                        if (initialItem != null) onDismiss() else { editor = null; editorRecord = null }
                    }, enabled = !saving) { Icon(CompoundIcons.ArrowLeft(), stringResource(R.string.screen_common_back), Modifier.size(20.dp)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        focus.clearFocus(force = true); keyboard?.hide()
                        val item = editor ?: return@IconButton
                        LifecycleStageTiming.observed("common_ui_action", scope = "save")
                        if (failed && item.content.isEmpty()) { openEditor(item); return@IconButton }
                        if (!canSave()) { onDismiss(); return@IconButton }
                        saving = true; failed = false
                        scope.launch {
                            try {
                                check(canSave())
                                val kind = when {
                                    item.content.trim().matches(Regex("[0-9]{17}[0-9Xx]")) -> CommonInfoKind.IDENTITY
                                    item.content.trim().matches(Regex("1[3-9][0-9]{9}")) -> CommonInfoKind.PHONE
                                    else -> CommonInfoKind.TEXT
                                }
                                repository.enqueue("save", item.copy(kind = kind))
                                if (repository.authorizationRevision.value == openingRevision) {
                                    focus.clearFocus(force = true); keyboard?.hide()
                                    editor = null; editorRecord = null; onSaved()
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { saving = false }
                        }
                    }, enabled = !saving && editingId == null && editor?.title?.isNotBlank() == true && (editor?.content?.isNotBlank() == true || failed)) {
                        if (saving || editingId != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                        else Icon(if (failed && editor?.content?.isEmpty() == true) CompoundIcons.Restart() else CompoundIcons.Check(),
                            stringResource(if (failed) R.string.screen_common_retry else R.string.screen_common_save), Modifier.size(22.dp))
                    }
                } else {
                    Text(repository.projected(entries, openingHeatTime).size.toString(), Modifier.padding(end = 8.dp),
                        color = ElementTheme.colors.textSecondary, style = ElementTheme.typography.fontBodySmRegular)
                    Row((if (searching) Modifier.weight(1f) else Modifier.width(44.dp)).height(36.dp)
                        .background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(18.dp)), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (failed) load() else {
                            searching = !searching
                            if (!searching) { query = ""; focus.clearFocus(force = true); keyboard?.hide() }
                        } }) {
                            Icon(if (failed) CompoundIcons.Restart() else CompoundIcons.Search(),
                                stringResource(if (failed) R.string.screen_common_retry else R.string.screen_common_search), Modifier.size(18.dp))
                        }
                        if (searching) BasicTextField(query, { query = it.take(64) }, Modifier.weight(1f).padding(end = 12.dp), singleLine = true,
                            textStyle = ElementTheme.typography.fontBodyMdRegular.copy(color = ElementTheme.colors.textPrimary))
                    }
                    if (!searching) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.screen_common_gesture_hint), color = ElementTheme.colors.textSecondary,
                            style = ElementTheme.typography.fontBodySmRegular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        LifecycleStageTiming.observed("common_ui_action", scope = "new")
                        editor = CommonInfoItem(); editorRecord = null; editingId = null; managedId = null; failed = false
                    }) {
                        Icon(CompoundIcons.Plus(), stringResource(R.string.screen_common_new), Modifier.size(22.dp))
                    }
                }
            }
            val editing = editor
            if (editing != null) CommonInfoEditor(editing, !saving && editingId == null, Modifier.weight(1f), onChange = { editor = it })
            else {
                val visible = repository.projected(entries, openingHeatTime).filter { it.title.contains(query, true) || it.summary.contains(query, true) }
                if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 1.5.dp)
                    else if (!failed && indexRecord != null && query.isBlank()) Text(stringResource(R.string.screen_common_empty), color = ElementTheme.colors.textSecondary,
                        style = ElementTheme.typography.fontBodySmRegular)
                }
                else LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { it.id }) { item ->
                        val operation = pending.firstOrNull { it.item.id == item.id && it.action != "use" }
                        CommonInfoRow(item, operation == null && sendingId != item.id, managedId == item.id,
                            operation != null || sendingId == item.id, operation?.failed == true,
                            onSend = {
                                sendingId = item.id
                                onSend(item) {
                                    sendingId = null
                                    context.toast(if (it.isSuccess) sentMessage else sendFailedMessage)
                                    if (it.isSuccess) { onDismiss(); onOpenComposerKeyboard() }
                                }
                            },
                            onInsert = {
                                sendingId = item.id
                                onInsert(item) {
                                    sendingId = null
                                    context.toast(if (it.isSuccess) insertedMessage else insertFailedMessage)
                                    if (it.isSuccess) { onDismiss(); onOpenComposerKeyboard() }
                                }
                            },
                            onManage = { managedId = if (managedId == item.id) null else item.id },
                            onEdit = { openEditor(item) }, onPin = { enqueue("pin", item) },
                            onDelete = { enqueue("delete", item) }, onRetry = { repository.startSync() },
                            onReset = { enqueue("reset"); managedId = null }, heat = commonInfoHeat(item, openingHeatTime))
                    }
                }
            }
        }
    }
    if (embedded) content()
    else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ElementTheme.colors.bgCanvasDefault, dragHandle = null) {
        Box(Modifier.height(320.dp)) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommonInfoRow(item: CommonInfoItem, enabled: Boolean, managing: Boolean, processing: Boolean = false, failed: Boolean = false,
    onSend: () -> Unit, onManage: () -> Unit, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit = {},
    onReset: () -> Unit = {}, onInsert: () -> Unit = {}, heat: CommonInfoHeat = CommonInfoHeat(0.0, 0)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).combinedClickable(enabled = enabled, onClick = onInsert, onDoubleClick = onSend,
            onLongClick = onManage).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          CommonInfoHeatAvatar(heat.level)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f, fill = false), style = ElementTheme.typography.fontBodyMdRegular,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.pinnedAt > 0) Icon(CompoundIcons.Pin(), stringResource(R.string.screen_common_pin), Modifier.padding(start = 6.dp).size(12.dp))
            }
            Text(item.summary, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        if (processing) IconButton(onClick = onRetry, enabled = failed) {
            if (failed) Icon(CompoundIcons.Restart(), stringResource(R.string.screen_common_retry), Modifier.size(18.dp))
            else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        }
        else if (managing) {
            IconButton(onClick = onEdit, enabled = enabled) { Icon(CompoundIcons.Edit(), stringResource(R.string.screen_common_edit), Modifier.size(18.dp)) }
            IconButton(onClick = onPin, enabled = enabled) { Icon(CompoundIcons.Pin(), stringResource(if (item.pinnedAt > 0) R.string.screen_common_unpin else R.string.screen_common_pin), Modifier.size(18.dp)) }
            IconButton(onClick = onDelete, enabled = enabled) { Icon(CompoundIcons.Delete(), stringResource(R.string.screen_common_delete), Modifier.size(18.dp)) }
            IconButton(onClick = onReset, enabled = enabled) { Icon(CompoundIcons.Restart(), stringResource(R.string.screen_common_reset), Modifier.size(18.dp)) }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CommonInfoHeatAvatar(level: CommonInfoHeatLevel) {
    val color = when (level) {
        CommonInfoHeatLevel.FREQUENT -> Color(0xFFD85048)
        CommonInfoHeatLevel.REGULAR -> Color(0xFFD9A623)
        CommonInfoHeatLevel.OCCASIONAL -> Color(0xFF4E9B58)
        CommonInfoHeatLevel.NONE -> Color(0xFF8A8F94)
    }
    val description = stringResource(when (level) {
        CommonInfoHeatLevel.FREQUENT -> R.string.screen_common_heat_high
        CommonInfoHeatLevel.REGULAR -> R.string.screen_common_heat_medium
        CommonInfoHeatLevel.OCCASIONAL -> R.string.screen_common_heat_low
        CommonInfoHeatLevel.NONE -> R.string.screen_common_heat_none
    })
    Canvas(Modifier.size(28.dp).semantics { contentDescription = description }) {
        drawCircle(color.copy(alpha = 0.08f))
        val scale = size.minDimension / 28f
        val flame = Path().apply {
            moveTo(14f * scale, 5f * scale)
            cubicTo(20f * scale, 10f * scale, 18f * scale, 12f * scale, 20f * scale, 13f * scale)
            cubicTo(25f * scale, 21f * scale, 16f * scale, 25f * scale, 11f * scale, 22f * scale)
            cubicTo(5f * scale, 19f * scale, 7f * scale, 13f * scale, 10f * scale, 10f * scale)
            lineTo(11f * scale, 14f * scale)
            cubicTo(14f * scale, 12f * scale, 16f * scale, 9f * scale, 14f * scale, 5f * scale)
            close()
        }
        if (level == CommonInfoHeatLevel.FREQUENT) drawPath(flame, color)
        else {
            if (level == CommonInfoHeatLevel.REGULAR) drawPath(flame, color.copy(alpha = 0.22f))
            drawPath(flame, color, style = Stroke(width = 1.4f * scale))
        }
    }
}

@Composable
private fun CommonInfoEditor(item: CommonInfoItem, enabled: Boolean, modifier: Modifier = Modifier, onChange: (CommonInfoItem) -> Unit) {
    val titleHint = stringResource(R.string.screen_common_label)
    val contentHint = stringResource(R.string.screen_common_content)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            BasicTextField(item.title, { onChange(item.copy(title = it.take(64))) }, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                enabled = enabled, singleLine = true, textStyle = ElementTheme.typography.fontBodyMdRegular.copy(color = ElementTheme.colors.textPrimary),
                decorationBox = { inner -> Box { if (item.title.isEmpty()) Text(titleHint, color = ElementTheme.colors.textSecondary); inner() } })
            HorizontalDivider()
        }
        item {
            BasicTextField(item.content, { onChange(item.copy(content = it.take(20_000))) }, Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
                enabled = enabled, textStyle = ElementTheme.typography.fontBodyMdRegular.copy(color = ElementTheme.colors.textPrimary),
                decorationBox = { inner -> Box { if (item.content.isEmpty()) Text(contentHint, color = ElementTheme.colors.textSecondary); inner() } })
        }
    }
}

@PreviewsDayNight
@Composable
internal fun CommonInfoListPreview() = ElementPreview {
    Column {
        CommonInfoRow(CommonInfoItem(title = "家里收件地址", summary = "示例市 · 示例小区", pinnedAt = 1), true, false, false, false, {}, {}, {}, {}, {})
        CommonInfoRow(CommonInfoItem(title = "个人电话", summary = "138 **** 0000"), true, true, false, false, {}, {}, {}, {}, {})
    }
}

@PreviewsDayNight
@Composable
internal fun CommonInfoEditorPreview() = ElementPreview { CommonInfoEditor(CommonInfoItem(), true, onChange = {}) }
