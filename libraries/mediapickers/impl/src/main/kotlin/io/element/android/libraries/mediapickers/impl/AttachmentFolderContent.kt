/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

@Composable
internal fun AttachmentFolderContent(
    modifier: Modifier,
    roots: List<Pair<String, String>>,
    sources: List<AttachmentSource>,
    currentSource: AttachmentSource?,
    currentDirectory: Uri?,
    listing: AttachmentDirectoryListing?,
    insideFolder: Boolean,
    search: String,
    moving: Boolean,
    enabled: Boolean,
    canCreate: Boolean,
    highlightedUri: Uri?,
    selected: List<AttachmentFile>,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
    onAddFolder: () -> Unit,
    onEnterRoot: (String) -> Unit,
    onEnterDirectory: (AttachmentDirectory) -> Unit,
    onChooseTarget: (AttachmentSource) -> Unit,
    onPreview: (AttachmentFile) -> Unit,
    onSelect: (AttachmentFile) -> Unit,
) {
    val listState = rememberLazyListState()
    var lastHighlighted by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(highlightedUri, listing, search) {
        if (highlightedUri == null) {
            lastHighlighted = null
        } else if (lastHighlighted != highlightedUri && listing != null) {
            val position = listing.files.filter { it.name.contains(search.trim(), ignoreCase = true) }.indexOfFirst { it.uri == highlightedUri }
            if (position >= 0) {
                val folders = listing.directories.count { it.name.contains(search.trim(), ignoreCase = true) }
                listState.scrollToItem(folders + position)
                lastHighlighted = highlightedUri
            }
        }
    }
    Column(modifier) {
        if (moving && insideFolder && currentSource?.managed == true && currentDirectory != null) {
            TextButton(onClick = { onChooseTarget(currentSource.copy(uri = currentDirectory)) }, enabled = enabled) {
                Text(stringResource(R.string.attachment_move_here))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AttachmentSmallAction(CompoundIcons.Plus(), R.string.attachment_create_folder, enabled && canCreate, onCreateFolder)
            AttachmentSmallAction(CompoundIcons.Files(), R.string.attachment_create_file, enabled && canCreate && !moving, onCreateFile)
            AttachmentSmallAction(CompoundIcons.Folder(), R.string.attachment_add_folder, enabled, onAddFolder)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (!insideFolder) {
                val visibleRoots = roots.filter { it.second.contains(search.trim(), ignoreCase = true) }
                items(visibleRoots, key = { it.first }) { (uri, name) ->
                    val source = sources.firstOrNull { it.uri.toString() == uri }
                    val available = enabled && (!moving || source?.managed == true)
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = { Icon(CompoundIcons.Folder(), contentDescription = null) },
                        supportingContent = {
                            Text(stringResource(if (moving && source?.managed != true) {
                            R.string.attachment_move_unavailable
                        } else if (source?.managed == true) {
                            R.string.attachment_my_folder
                        } else {
                            R.string.attachment_folders
                        }))
                        },
                        trailingContent = {
                            if (moving && source?.managed == true) {
                                IconButton(onClick = { onEnterRoot(uri) }, enabled = available) {
                                Icon(CompoundIcons.ChevronRight(), contentDescription = stringResource(R.string.attachment_open_folder))
                            }
                            }
                        },
                        modifier = Modifier.clickable(enabled = available) {
                            if (moving) onChooseTarget(checkNotNull(source)) else onEnterRoot(uri)
                        },
                    )
                }
                if (visibleRoots.isEmpty()) item { Text(stringResource(R.string.attachment_folders_empty), Modifier.padding(16.dp)) }
            } else {
                val folders = listing?.directories.orEmpty().filter { it.name.contains(search.trim(), ignoreCase = true) }
                val files = listing?.files.orEmpty().filter { it.name.contains(search.trim(), ignoreCase = true) }
                items(folders, key = { it.uri.toString() }) { directory ->
                    ListItem(
                        headlineContent = { Text(directory.name) },
                        leadingContent = { Icon(CompoundIcons.Folder(), contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.attachment_folders)) },
                        trailingContent = {
                            if (moving) {
                                IconButton(onClick = { onEnterDirectory(directory) }, enabled = enabled) {
                                Icon(CompoundIcons.ChevronRight(), contentDescription = stringResource(R.string.attachment_open_folder))
                            }
                            }
                        },
                        modifier = Modifier.clickable(enabled = enabled) {
                            if (moving && currentSource?.managed == true) {
                                onChooseTarget(currentSource.copy(uri = directory.uri))
                            } else {
                                onEnterDirectory(directory)
                            }
                        },
                    )
                }
                items(files, key = { it.uri.toString() }) { item ->
                    AttachmentFileRow(
                        item,
                        selected.any { it.uri == item.uri },
                        enabled,
                        selected.size < 9 || selected.any { it.uri == item.uri },
                        { onPreview(item) },
                        { onSelect(item) },
                        highlighted = item.uri == highlightedUri
                    )
                }
                if (listing != null && folders.isEmpty() && files.isEmpty()) {
                    item {
                    Text(stringResource(R.string.attachment_directory_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                }
                if (listing?.truncated == true) item { Text(stringResource(R.string.attachment_partial_index), Modifier.padding(16.dp)) }
            }
        }
    }
}

internal enum class AttachmentFolderPreviewState { Root, Directory, ReadOnlyDirectory, Empty, Error, MoveRoot, MoveDirectory, LongPressed }
internal class AttachmentFolderPreviewProvider : PreviewParameterProvider<AttachmentFolderPreviewState> {
    override val values = AttachmentFolderPreviewState.entries.asSequence()
}

@PreviewsDayNight
@Composable
internal fun AttachmentFolderStatesPreview(@PreviewParameter(AttachmentFolderPreviewProvider::class) state: AttachmentFolderPreviewState) = ElementPreview {
    val source = AttachmentSource(Uri.parse("file:///sdcard/Documents/Contracts"), true, 0, managed = state != AttachmentFolderPreviewState.ReadOnlyDirectory)
    val inside = state in listOf(
        AttachmentFolderPreviewState.Directory,
        AttachmentFolderPreviewState.ReadOnlyDirectory,
        AttachmentFolderPreviewState.Empty,
        AttachmentFolderPreviewState.Error,
        AttachmentFolderPreviewState.MoveDirectory
    )
    val moving = state in listOf(AttachmentFolderPreviewState.MoveRoot, AttachmentFolderPreviewState.MoveDirectory)
    val file = AttachmentFile(
        Uri.parse("file:///sdcard/Documents/Contracts/Budget.xlsx"),
        "Budget.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        2048,
        0,
        0,
        "Contracts",
        source.uri.toString()
    )
    val listing =
        AttachmentDirectoryListing(source.uri, listOf(AttachmentDirectory(Uri.parse("file:///sdcard/Documents/Contracts/2026"), "2026")), listOf(file))
    Column(Modifier.fillMaxWidth().height(640.dp)) {
        AttachmentSearchHeader(
            null,
            "",
            {},
            {},
            showFolders = state != AttachmentFolderPreviewState.LongPressed,
            searchLabel = if (state == AttachmentFolderPreviewState.LongPressed) {
                R.string.attachment_search_short
            } else if (inside) {
                R.string.attachment_search_directory
            } else {
                R.string.attachment_search_folders
            },
            folderTitle = if (inside) "Folders › Contracts" else null,
            moveTitle = if (moving) "Budget.xlsx" else null
        )
        if (state == AttachmentFolderPreviewState.LongPressed) {
            AttachmentFileRow(file, false, true, true, {}, {}, moveVisible = true, onLongPress = {}, onMove = {})
        } else {
            if (state == AttachmentFolderPreviewState.Error) {
                Text(
                    stringResource(R.string.attachment_access_error),
                Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            AttachmentFolderContent(
            modifier = Modifier.weight(1f),
                roots = listOf(source.uri.toString() to "Contracts"),
                sources = listOf(source),
            currentSource = source,
                currentDirectory = source.uri,
            listing = if (state == AttachmentFolderPreviewState.Error) {
                null
            } else if (state == AttachmentFolderPreviewState.Empty) {
                listing.copy(directories = emptyList(), files = emptyList())
            } else {
                listing
            },
            insideFolder = inside,
                search = "",
                moving = moving,
                enabled = state != AttachmentFolderPreviewState.Error,
            canCreate = source.managed && state != AttachmentFolderPreviewState.Error,
            highlightedUri = if (state == AttachmentFolderPreviewState.Directory) file.uri else null,
                selected = emptyList(),
            onCreateFolder = {},
                onCreateFile = {},
                onAddFolder = {},
                onEnterRoot = {},
                onEnterDirectory = {},
            onChooseTarget = {},
                onPreview = {},
                onSelect = {},
        )
        }
    }
}
