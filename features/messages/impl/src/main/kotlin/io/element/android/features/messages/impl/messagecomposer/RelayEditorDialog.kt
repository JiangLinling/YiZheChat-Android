/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.element.android.compound.tokens.generated.CompoundIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.features.poll.api.pollcontent.InteractionDialogSystemBars
import io.element.android.features.poll.api.pollcontent.InteractionFormCard
import io.element.android.features.poll.api.pollcontent.InteractionFormField
import io.element.android.features.poll.api.pollcontent.InteractionFormSection
import io.element.android.features.poll.api.pollcontent.InteractionNumberBadge
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import io.element.android.features.poll.api.pollcontent.InteractionSubmitButton as Button
import io.element.android.features.poll.api.R as PollR

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry

@Composable
internal fun RelayEditorDialog(
    initialTitle: String = "",
    entries: ImmutableList<String> = persistentListOf(),
    initiator: String? = null,
    isCreating: Boolean,
    canClose: Boolean = false,
    closed: Boolean = false,
    itemDetails: ImmutableList<ProtectedRelayEntry> = persistentListOf(),
    editableItemIds: ImmutableSet<String> = persistentSetOf(),
    onEditEntry: (itemId: String, expectedRevision: Long, entry: String, operationId: String, onComplete: (Result<Unit>) -> Unit) -> Unit = { _, _, _, _, _ -> },
    loadPendingEdit: ((Result<io.element.android.libraries.matrix.api.timeline.RelayEditRecovery?>) -> Unit) -> Unit = { it(Result.success(null)) },
    onSubmit: (title: String, entry: String, onComplete: (Result<Unit>) -> Unit) -> Unit,
    onCloseRelay: ((Result<Unit>) -> Unit) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    // Concurrent list changes must never erase the participant's draft.
    // A recovered protected intent must not be copied into an unencrypted saved-state Bundle.
    var entry by remember(initialTitle) { mutableStateOf("") }
    var action by remember(initialTitle) { mutableStateOf<AsyncAction<Unit>>(AsyncAction.Uninitialized) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var submittedEntry by remember(initialTitle) { mutableStateOf<String?>(null) }
    var submittedEntryCount by remember(initialTitle) { mutableStateOf(0) }
    var editingItemId by rememberSaveable(initialTitle) { mutableStateOf<String?>(null) }
    var editingRevision by rememberSaveable(initialTitle) { mutableStateOf<Long?>(null) }
    var appendDraft by remember(initialTitle) { mutableStateOf("") }
    var pendingEditOperationId by rememberSaveable(initialTitle) { mutableStateOf<String?>(null) }
    var recoveryLoading by remember { mutableStateOf(!isCreating) }
    var recoveryUnavailable by remember { mutableStateOf(false) }
    var recoveryLocked by remember { mutableStateOf(false) }
    fun loadRecovery() {
        recoveryLoading = true
        loadPendingEdit { result ->
            recoveryLoading = false
            recoveryUnavailable = result.isFailure
            result.fold(
                onSuccess = { pending ->
                    val originalResolved = recoveryLocked && pending == null
                    recoveryLocked = pending != null
                    if (pending != null) {
                        editingItemId = pending.itemId
                        editingRevision = pending.expectedRevision
                        pendingEditOperationId = pending.operationId.removePrefix("relay-edit-")
                        entry = pending.entry
                        submittedEntry = pending.entry
                        recoveryLocked = true
                    }
                    action = if (originalResolved) AsyncAction.Success(Unit) else AsyncAction.Uninitialized
                },
                onFailure = { action = AsyncAction.Failure(it) },
            )
        }
    }
    LaunchedEffect(isCreating, closed) {
        if (closed) { confirmClose = false; confirmDiscard = false }
        if (!isCreating) loadRecovery()
    }
    val editingItem = itemDetails.singleOrNull { it.id == editingItemId }
    val editCurrent = editingItemId == null || (editingItemId in editableItemIds && editingItem?.revision == editingRevision)
    val busy = action is AsyncAction.Loading || recoveryLoading
    val entrySynced = action is AsyncAction.Success && submittedEntry != null &&
        if (editingItemId == null) {
            if (itemDetails.isEmpty()) entries.drop(submittedEntryCount).contains(submittedEntry)
            else itemDetails.drop(submittedEntryCount).any { it.text == submittedEntry && it.isConfirmed }
        } else editingItem?.text == submittedEntry && editingItem?.revision == editingRevision?.plus(1) && editingItem?.isConfirmed == true
    val ownPendingEdit = editingItem?.text == submittedEntry && editingItem?.revision == editingRevision?.plus(1) &&
        editingItem?.isConfirmed == false
    val waiting = action is AsyncAction.Success && !closed && !entrySynced && (editingItemId == null || editCurrent || ownPendingEdit)
    val canSubmit = title.isNotBlank() && entry.isNotBlank() && !busy && !recoveryUnavailable && !waiting && !entrySynced &&
        (!recoveryLocked && !closed && editCurrent && (editingItemId == null || entry.trim() != editingItem?.text))
    val complete: (Result<Unit>) -> Unit = { result ->
        action = result.fold({ AsyncAction.Success(Unit) }, { AsyncAction.Failure(it) })
    }
    val requestDismiss = {
        if (closed || !busy) {
            if (recoveryLocked) {
                onDismiss() // Closing the dialog retains the original encrypted in-flight intent.
            } else if (!closed && !entrySynced && !waiting && (entry.isNotBlank() || (isCreating && title != initialTitle))) {
                confirmDiscard = true
            } else {
                onDismiss()
            }
        }
    }
    @Composable
    fun EntryField() {
        InteractionFormSection(stringResource(when {
            editingItemId != null -> R.string.screen_room_relay_edit_entry
            isCreating -> R.string.screen_room_relay_first_entry
            else -> R.string.screen_room_relay_your_entry
        })) {
            InteractionFormField(
                entry,
                stringResource(R.string.screen_room_relay_entry_placeholder),
                enabled = !closed && !busy && !waiting && !entrySynced && !recoveryLocked && !recoveryUnavailable,
                onChange = { entry = it.take(RELAY_ENTRY_MAX_LENGTH); pendingEditOperationId = null },
                minLines = 2,
            )
        }
    }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = closed || !busy,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        ),
    ) {
        InteractionDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = ElementTheme.colors.bgSubtleSecondaryLevel0) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(enabled = closed || !busy, onClick = requestDismiss)
                    Text(
                        stringResource(if (isCreating) R.string.screen_room_relay_create_title else R.string.screen_room_relay_result_title),
                        style = ElementTheme.typography.fontHeadingSmMedium.copy(fontSize = 17.sp),
                    )
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (isCreating) {
                        item {
                            InteractionFormSection(stringResource(R.string.screen_room_relay_title_label), Modifier.padding(bottom = 20.dp)) {
                                InteractionFormField(
                                    title,
                                    stringResource(R.string.screen_room_relay_topic_hint),
                                    enabled = !busy && !waiting,
                                    onChange = { title = it.take(RELAY_TITLE_MAX_LENGTH) },
                                )
                            }
                        }
                        item { EntryField() }
                    } else {
                        item {
                            InteractionFormCard {
                                Text(title, style = ElementTheme.typography.fontHeadingSmMedium.copy(fontSize = 17.sp, lineHeight = 25.sp))
                                if (initiator != null) {
                                    Text(
                                        stringResource(R.string.screen_room_relay_started_by, initiator, entries.size),
                                        style = ElementTheme.typography.fontBodySmRegular,
                                        color = ElementTheme.colors.textSecondary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        item { RelaySectionLabel(stringResource(R.string.screen_room_relay_list_title, entries.size)) }
                        itemsIndexed(entries) { index, value ->
                            val shape = RoundedCornerShape(
                                topStart = if (index == 0) 12.dp else 0.dp,
                                topEnd = if (index == 0) 12.dp else 0.dp,
                                bottomStart = if (index == entries.lastIndex) 12.dp else 0.dp,
                                bottomEnd = if (index == entries.lastIndex) 12.dp else 0.dp,
                            )
                            Column(Modifier.fillMaxWidth().background(ElementTheme.colors.bgCanvasDefault, shape).padding(horizontal = 16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    InteractionNumberBadge(index + 1)
                                    Text(value, Modifier.weight(1f), style = ElementTheme.typography.fontBodyMdRegular.copy(fontSize = 14.sp, lineHeight = 22.sp))
                                    itemDetails.getOrNull(index)?.takeIf { it.id in editableItemIds && !closed }?.let { item ->
                                        IconButton(
                                            enabled = !busy && !waiting && !recoveryLocked && !recoveryUnavailable,
                                            onClick = {
                                                if (editingItemId == null) appendDraft = entry
                                                editingItemId = item.id
                                                editingRevision = item.revision
                                                pendingEditOperationId = null
                                                entry = item.text
                                                action = AsyncAction.Uninitialized
                                                submittedEntry = null
                                            },
                                        ) {
                                            Icon(
                                                imageVector = CompoundIcons.Edit(),
                                                contentDescription = stringResource(R.string.screen_room_relay_edit_entry),
                                                modifier = Modifier.size(20.dp),
                                                tint = ElementTheme.colors.iconSecondary,
                                            )
                                        }
                                    }
                                }
                                if (index < entries.lastIndex) HorizontalDivider(color = ElementTheme.colors.borderDisabled)
                            }
                        }
                        if (entries.isEmpty()) item {
                            InteractionFormCard {
                                Text(
                                    stringResource(R.string.screen_room_relay_empty),
                                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp),
                                    style = ElementTheme.typography.fontBodySmRegular,
                                    color = ElementTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
                Column(Modifier.background(ElementTheme.colors.bgCanvasDefault).padding(16.dp)) {
                    if (!closed && !isCreating) EntryField()
                    Text(
                        stringResource(
                            when {
                            closed -> R.string.screen_room_relay_closed
                            busy -> R.string.screen_room_relay_submitting
                            recoveryUnavailable -> relayEditErrorResource((action as? AsyncAction.Failure)?.error)
                            waiting -> R.string.screen_room_relay_pending
                            entrySynced -> R.string.screen_room_relay_synced
                            recoveryLocked -> if (action is AsyncAction.Failure) relayEditErrorResource((action as AsyncAction.Failure).error)
                                else R.string.screen_room_relay_recovery_pending
                            action is AsyncAction.Failure -> R.string.screen_room_relay_submit_failed
                            !editCurrent -> R.string.screen_room_relay_edit_conflict
                            isCreating -> R.string.screen_room_relay_create_hint
                            else -> R.string.screen_room_relay_editor_hint
                        }
                        ),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = if (action is AsyncAction.Failure) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = if (!closed && !isCreating) 10.dp else 0.dp, bottom = 12.dp),
                    )
                    if (closed && (recoveryLocked || recoveryUnavailable)) {
                        Text(
                            stringResource(if (recoveryUnavailable) relayEditErrorResource((action as? AsyncAction.Failure)?.error)
                                else R.string.screen_room_relay_recovery_pending),
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Button(
                        text = stringResource(
                            when {
                                closed || entrySynced -> R.string.screen_room_relay_back
                                recoveryUnavailable || recoveryLocked -> R.string.screen_room_relay_check_recovery
                                isCreating -> R.string.screen_room_relay_publish
                                editingItemId != null -> R.string.screen_room_relay_save_edit
                                else -> R.string.screen_room_relay_submit
                            }
                        ),
                        enabled = if (closed) true else if (recoveryUnavailable || recoveryLocked || entrySynced) !busy else canSubmit,
                        showProgress = busy && !closed,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (closed || entrySynced) {
                                onDismiss()
                            } else if (recoveryUnavailable || recoveryLocked) {
                                loadRecovery()
                            } else if (canSubmit) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                action = AsyncAction.Loading
                                submittedEntry = entry.trim()
                                submittedEntryCount = entries.size
                                val itemId = editingItemId
                                if (itemId == null) onSubmit(title.trim(), entry.trim(), complete)
                                else {
                                    val operationId = pendingEditOperationId ?: java.util.UUID.randomUUID().toString().also {
                                        pendingEditOperationId = it
                                    }
                                    recoveryLocked = true
                                    onEditEntry(itemId, checkNotNull(editingRevision), entry.trim(), operationId, complete)
                                }
                            }
                        },
                    )
                    if (!closed && (editingItemId != null || canClose)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (editingItemId != null) {
                                TextButton(stringResource(R.string.screen_room_relay_cancel_edit), enabled = !busy && !waiting && !recoveryLocked,
                                    onClick = {
                                        editingItemId = null
                                        editingRevision = null
                                        pendingEditOperationId = null
                                        entry = appendDraft
                                        submittedEntry = null
                                        action = AsyncAction.Uninitialized
                                    })
                            }
                            Spacer(Modifier.weight(1f))
                            if (canClose) {
                                TextButton(
                                    stringResource(R.string.screen_room_relay_close_action),
                                    enabled = !busy && !waiting && !recoveryLocked && !recoveryUnavailable,
                                    onClick = { confirmClose = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard && !closed) {
        ConfirmationDialog(
            title = stringResource(PollR.string.screen_interaction_discard_title),
            content = stringResource(PollR.string.screen_interaction_discard_content),
            submitText = stringResource(PollR.string.screen_interaction_discard),
            cancelText = stringResource(PollR.string.screen_interaction_continue),
            destructiveSubmit = true,
            onSubmitClick = { confirmDiscard = false; onDismiss() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmClose && !closed) {
        ConfirmationDialog(
        content = stringResource(R.string.screen_room_relay_close_confirmation),
        onDismiss = { confirmClose = false },
        onSubmitClick = {
            confirmClose = false
            if (!busy && !waiting && !closed) {
                submittedEntry = null
                action = AsyncAction.Loading
                onCloseRelay(complete)
            }
        },
    )
    }
}

@Composable
private fun RelaySectionLabel(text: String) = Text(
    text,
    Modifier.padding(start = 2.dp, top = 20.dp, bottom = 8.dp),
    style = ElementTheme.typography.fontBodySmMedium,
    color = ElementTheme.colors.textSecondary
)

private const val RELAY_TITLE_MAX_LENGTH = 500
private const val RELAY_ENTRY_MAX_LENGTH = 2_000

@PreviewsDayNight
@Composable
private fun RelayEditorDialogPreview() = ElementPreview {
    RelayEditorDialog(
        initialTitle = "周六羽毛球报名",
        entries = persistentListOf("周楠 2 人", "林小满 1 人"),
        initiator = "周楠",
        isCreating = false,
        canClose = true,
        onSubmit = { _, _, _ -> },
        onDismiss = {}
    )
}

@PreviewsDayNight
@Composable
private fun RelayCreateDialogPreview() = ElementPreview {
    RelayEditorDialog(isCreating = true, onSubmit = { _, _, _ -> }, onDismiss = {})
}

@PreviewsDayNight
@Composable
private fun RelayClosedDialogPreview() = ElementPreview {
    RelayEditorDialog(
        initialTitle = "周六羽毛球报名",
        entries = persistentListOf("周楠 2 人"),
        isCreating = false,
        closed = true,
        onSubmit = { _, _, _ -> },
        onDismiss = {}
    )
}
