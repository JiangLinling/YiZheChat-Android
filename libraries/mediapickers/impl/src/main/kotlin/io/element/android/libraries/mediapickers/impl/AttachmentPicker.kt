/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.attachment.FileTypeIcon
import io.element.android.libraries.designsystem.components.attachment.fileTypeAppearance
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerLauncher
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLimitException
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

@Composable
internal fun rememberAttachmentPicker(
    accountId: String,
    store: AttachmentSourceStore,
    factory: LocalMediaFactory,
    renderer: LocalMediaRenderer,
    coordinator: AppOperationalStateCoordinator,
    roomIdHash: String?,
    previewLeaseService: LocalAttachmentPreviewLeaseService?,
    onResult: (List<PickedMedia>) -> Unit,
): PickerLauncher<String, List<Uri>> {
    var visible by rememberSaveable(accountId, roomIdHash) { mutableStateOf(false) }
    val callback by rememberUpdatedState(onResult)
    val context = LocalContext.current.applicationContext
    val ownerScope = rememberCoroutineScope()
    val library = remember(accountId, roomIdHash, store) {
        AttachmentIndexedLibrary(AttachmentAndroidIndexBackend(context, accountId, store), ownerScope)
    }
    DisposableEffect(library) { onDispose { library.close() } }
    if (visible) {
        AttachmentPicker(
            accountId,
            store,
            factory,
            renderer,
            coordinator,
            roomIdHash,
            previewLeaseService,
            library,
        onClose = { visible = false },
            onResult = {
            visible = false
            callback(it)
        }
        )
    }
    return remember(accountId, roomIdHash) {
        object : PickerLauncher<String, List<Uri>> {
            override fun launch() {
                visible = true
            }
            override fun launch(customInput: String) {
                visible = true
            }
        }
    }
}

@Composable
private fun AttachmentPicker(
    accountId: String,
    store: AttachmentSourceStore,
    factory: LocalMediaFactory,
    renderer: LocalMediaRenderer,
    coordinator: AppOperationalStateCoordinator,
    roomIdHash: String?,
    previewLeaseService: LocalAttachmentPreviewLeaseService?,
    library: AttachmentIndexedLibrary,
    onClose: () -> Unit,
    onResult: (List<PickedMedia>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var directoryPath by remember { mutableStateOf(listOf<String>()) }
    var directoryNames by remember { mutableStateOf(listOf<String>()) }
    var directoryListing by remember { mutableStateOf<AttachmentDirectoryListing?>(null) }
    var directoryError by remember { mutableStateOf(false) }
    var directoryLoading by remember { mutableStateOf(false) }
    var longPressedUri by remember { mutableStateOf<Uri?>(null) }
    var movingFile by remember { mutableStateOf<AttachmentFile?>(null) }
    var moveReturnSearch by remember { mutableStateOf("") }
    var highlightedUri by remember { mutableStateOf<Uri?>(null) }
    var movedNotice by remember { mutableStateOf(false) }
    val recentListState = rememberLazyListState()
    var selected by remember { mutableStateOf(listOf<AttachmentFile>()) }
    var previewHandle by remember { mutableStateOf<AttachmentPreviewHandle?>(null) }
    val preview = previewHandle?.media
    var openingPreview by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<AttachmentFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val libraryState by library.state.collectAsState()
    val indexing = libraryState.updating
    var forceRefresh by remember { mutableStateOf(false) }
    var sourcesChanged by remember { mutableStateOf(true) }
    val dirtyDirectories = remember { mutableSetOf<String>() }
    var foreground by remember { mutableStateOf(true) }
    var showSelected by remember { mutableStateOf(false) }
    var accessRequestInFlight by rememberSaveable { mutableStateOf(false) }
    var accessOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf(emptyList<AttachmentSource>()) }
    var sourcesLoaded by remember { mutableStateOf(false) }
    var sharedAccess by remember { mutableStateOf(hasAttachmentSharedAccess()) }
    val index = libraryState.index.takeIf { sourcesLoaded && libraryState.sharedAccess == sharedAccess }
    var sharedOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingManagementId by remember { mutableStateOf<String?>(null) }
    var managementDialog by remember { mutableStateOf<String?>(null) }
    var managementName by remember { mutableStateOf("") }
    var managementContent by remember { mutableStateOf("") }
    var managementFolder by remember { mutableStateOf<AttachmentSource?>(null) }
    var managementError by remember { mutableStateOf(false) }
    val fileManager = remember(store) { AttachmentFileManager(store) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSource = sources.firstOrNull { it.uri.toString() == folder && it.tree }
    val currentDirectory = currentSource?.let { source ->
        directoryPath.lastOrNull()?.let(Uri::parse) ?: attachmentDirectoryUri(source)
    }
    val visibleDirectory = directoryListing?.takeIf {
        it.uri == currentDirectory && currentSource != null && (!currentSource.managed || sharedAccess)
    }
    fun enterRoot(uri: String) {
        highlightedUri = null
        movedNotice = false
        folder = uri
        directoryPath = emptyList()
        directoryNames = emptyList()
        search = ""
    }
    fun cancelMove() {
        movingFile = null
        longPressedUri = null
        showFolders = false
        folder = null
        directoryPath = emptyList()
        directoryNames = emptyList()
        search = moveReturnSearch
        managementError = false
    }
    LaunchedEffect(currentDirectory, currentSource, sharedAccess, refresh) {
        if (directoryListing?.uri != currentDirectory || currentSource == null || (currentSource.managed && !sharedAccess)) directoryListing = null
        directoryError = false
        if (currentDirectory != null && currentSource != null) {
            directoryLoading = true
            try {
                directoryListing = browseAttachmentDirectory(context.contentResolver, currentSource, currentDirectory)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                directoryListing = null
                directoryError = true
            } finally {
                directoryLoading = false
            }
        }
    }
    fun closePreview() {
        previewHandle?.close()
        previewHandle = null
        previewFile = null
    }
    DisposableEffect(previewHandle) {
        val owned = previewHandle
        onDispose { owned?.close() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sharedAccess = hasAttachmentSharedAccess()
                foreground = true
                refresh++
            }
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                closePreview()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(sources, foreground) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                sourcesChanged = true
                refresh++
            }
        }
        val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh++
            }
        }
        if (foreground) {
            sources.forEach { source -> runCatching { context.contentResolver.registerContentObserver(source.uri, true, observer) } }
            runCatching { context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver) }
        }
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.contentResolver.unregisterContentObserver(mediaObserver)
        }
    }
    DisposableEffect(libraryState.watchedDirectories, foreground) {
        val handler = Handler(Looper.getMainLooper())
        @Suppress("DEPRECATION")
        val watchers = if (foreground) {
            libraryState.watchedDirectories.map { directory ->
            object : FileObserver(directory, CLOSE_WRITE or CREATE or DELETE or MOVED_FROM or MOVED_TO or DELETE_SELF or MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    handler.post {
                        dirtyDirectories.add(directory)
                        if (sources.any { source ->
                            attachmentDiskFile(source.uri)?.let { java.io.File(directory).toPath().startsWith(it.toPath()) } == true
                        }) {
                            sourcesChanged = true
                        }
                        refresh++
                    }
                }
            }.also { it.startWatching() }
        }
        } else {
            emptyList()
        }
        onDispose {
            watchers.forEach { it.stopWatching() }
            handler.removeCallbacksAndMessages(null)
        }
    }
    LaunchedEffect(foreground) {
        if (foreground) {
            while (true) {
            delay(15_000)
            refresh++
        }
        }
    }
    LaunchedEffect(accountId, refresh) {
        try {
            sharedAccess = hasAttachmentSharedAccess()
            sources = store.load(accountId)
            sourcesLoaded = true
            pendingManagementId = store.pendingManagement(accountId)?.getString("operationId")
            library.refresh(sources, forceRefresh, sourcesChanged, dirtyDirectories.toSet())
            forceRefresh = false
            sourcesChanged = false
            dirtyDirectories.clear()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            error = context.getString(R.string.attachment_access_error)
            selected = emptyList()
            closePreview()
        }
    }
    LaunchedEffect(index, indexing, sharedAccess, visibleDirectory, directoryLoading) {
        if (!indexing && index != null && !directoryLoading) {
            val available = (index.files + visibleDirectory?.files.orEmpty()).associateBy { it.uri }
            selected = selected.mapNotNull { available[it.uri] }
            if (previewFile != null && previewFile?.uri !in available) closePreview()
        } else if (!sharedAccess) {
            movingFile = null
            selected = selected.filter { item -> sources.any { it.uri == item.uri || it.uri.toString() == item.root } }
            closePreview()
        }
    }
    val openError = stringResource(R.string.attachment_access_error)
    LaunchedEffect(movedNotice) {
        if (movedNotice) {
            delay(3_000)
            movedNotice = false
            highlightedUri = null
        }
    }
    LaunchedEffect(sourcesLoaded, currentSource, folder) {
        if (sourcesLoaded && folder != null && currentSource == null) {
            folder = null
            directoryPath = emptyList()
            directoryNames = emptyList()
        }
    }
    fun acceptSourcePermission(uris: List<Uri>, tree: Boolean) {
        scope.launch {
            busy = true
            try {
                val operationId = checkNotNull(accessOperationId)
                coordinator.perform(lifecycleOperationRequest(
                    lifecycleId = FullAppLifecycleId.LC_03,
                    trigger = "feature_requires_permission",
                    operationId = operationId,
                )) {
                    if (uris.isNotEmpty()) store.add(accountId, uris, tree)
                }
                accessOperationId = null
                sourcesChanged = true
                refresh++
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                error = context.getString(R.string.attachment_access_error)
            } finally {
                accessOperationId = null
                accessRequestInFlight = false
                busy = false
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        acceptSourcePermission(listOfNotNull(uri), tree = true)
    }
    val sharedPermissionPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            try {
                val operationId = checkNotNull(sharedOperationId)
                coordinator.perform(lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission", operationId = operationId)) {
                    sharedAccess = hasAttachmentSharedAccess()
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                error = context.getString(R.string.attachment_access_error)
            } finally {
                sharedOperationId = null
                refresh++
            }
        }
    }
    fun requestSharedPermission() {
        if (sharedOperationId != null) return
        scope.launch {
            try {
                sharedOperationId = coordinator.start(lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission")).operationId
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                try {
                    sharedPermissionPicker.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    sharedPermissionPicker.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                sharedOperationId = null
                error = context.getString(R.string.attachment_access_error)
            }
        }
    }
    fun runManagement(resume: Boolean = false, block: suspend (String) -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            managementError = false
            try {
                val request = if (resume) {
                    lifecycleOperationRequest(FullAppLifecycleId.LC_16, "media_select", operationId = checkNotNull(pendingManagementId))
                } else {
                    lifecycleOperationRequest(FullAppLifecycleId.LC_16, "media_select")
                }
                coordinator.perform(request) { operationId -> block(operationId) }
                managementDialog = null
                managementName = ""
                managementContent = ""
                managementFolder = null
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                managementError = true
                val pending = if (failure is AttachmentNameCollisionException) runCatching { store.pendingManagement(accountId) } else null
                if (failure is AttachmentNameCollisionException && movingFile != null && pending != null && pending.isSuccess && pending.getOrNull() == null) {
                    managementDialog = "rename"
                    managementName = checkNotNull(movingFile).name
                }
            } finally {
                busy = false
                sourcesChanged = true
                refresh++
            }
        }
    }
    fun startManagement(kind: String) {
        if (!sharedAccess) {
            requestSharedPermission()
            return
        }
        managementName = if (kind == "text") context.getString(R.string.attachment_default_filename) else ""
        managementContent = ""
        managementFolder = if (currentSource?.managed == true && currentDirectory != null) {
            currentSource.copy(uri = currentDirectory)
        } else {
            sources.firstOrNull { it.managed }
        }
        managementError = false
        managementDialog = kind
    }
    fun moveTo(target: AttachmentSource, newName: String? = null) {
        val item = movingFile ?: return
        managementFolder = target
        val rootSource = currentSource?.takeIf { it.managed } ?: target
        val targetPath = if (target.uri == rootSource.uri) {
            emptyList()
        } else if (target.uri == currentDirectory) {
            directoryPath
        } else {
            directoryPath + target.uri.toString()
        }
        val targetNames = if (target.uri == rootSource.uri) {
            emptyList()
        } else if (target.uri == currentDirectory) {
            directoryNames
        } else {
            directoryNames + checkNotNull(attachmentDiskFile(target.uri)).name
        }
        runManagement { operationId ->
            fileManager.move(accountId, listOf(item), target, operationId, newName)
            val destination = File(checkNotNull(attachmentDiskFile(target.uri)), newName ?: item.name)
            highlightedUri = Uri.fromFile(destination)
            selected = selected.filterNot { it.uri == item.uri }
            movingFile = null
            longPressedUri = null
            movedNotice = true
            folder = rootSource.uri.toString()
            directoryPath = targetPath
            directoryNames = targetNames
            search = ""
            showFolders = true
        }
    }
    fun startMove(item: AttachmentFile) {
        moveReturnSearch = search
        movingFile = item
        longPressedUri = null
        movedNotice = false
        showFolders = true
        showSelected = false
        folder = null
        directoryPath = emptyList()
        directoryNames = emptyList()
        search = ""
    }
    fun requestSourcePermission() {
        if (accessRequestInFlight) return
        accessRequestInFlight = true
        scope.launch {
            busy = true
            try {
                val operation = coordinator.start(lifecycleOperationRequest(FullAppLifecycleId.LC_03, "feature_requires_permission"))
                accessOperationId = operation.operationId
                folderPicker.launch(null)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                accessOperationId = null
                accessRequestInFlight = false
                error = context.getString(R.string.attachment_access_error)
            } finally {
                busy = false
            }
        }
    }
    fun toggle(item: AttachmentFile) {
        longPressedUri = null
        selected = if (selected.any { it.uri == item.uri }) {
            selected.filterNot { it.uri == item.uri }
        } else if (selected.size < MAX_ATTACHMENTS) {
            selected + item
        } else {
            selected
        }
    }
    fun openPreview(item: AttachmentFile) {
        longPressedUri = null
        if (openingPreview) return
        openingPreview = true
        error = null
        scope.launch {
            busy = true
            var opened: AttachmentPreviewHandle? = null
            try {
                val media = withContext(Dispatchers.IO) {
                    if (item.uri.scheme == "file") check(hasAttachmentSharedAccess())
                    context.contentResolver.openInputStream(item.uri)?.use { it.read() }
                        ?: error(openError)
                    factory.createFromUri(item.uri, item.mime, item.name, item.size?.let { Formatter.formatFileSize(context, it) })
                        .copy(lifecycleRoomIdHash = roomIdHash)
                }
                opened = AttachmentPreviewHandle.open(media, checkNotNull(previewLeaseService))
                currentCoroutineContext().ensureActive()
                check(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                check(index?.files?.any { it.uri == item.uri } == true || visibleDirectory?.files?.any { it.uri == item.uri } == true)
                closePreview()
                previewFile = item
                previewHandle = opened
                opened = null
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                error = if (failure is LocalAttachmentPreviewLimitException) {
                    context.getString(R.string.attachment_preview_size_limit, Formatter.formatFileSize(context, failure.maxInputBytes))
                } else {
                    context.getString(R.string.attachment_access_error)
                }
            } finally {
                opened?.close()
                openingPreview = false
                busy = false
            }
        }
    }
    fun finish() {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    selected.map { item ->
                        if (item.uri.scheme == "file") check(hasAttachmentSharedAccess())
                        context.contentResolver.openInputStream(item.uri)?.use { it.read() } ?: error(openError)
                        PickedMedia(item.uri, item.mime)
                    }
                }
                onResult(result)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                error = context.getString(R.string.attachment_access_error)
            } finally {
                busy = false
            }
        }
    }
    var attachmentDialogView by remember { mutableStateOf<View?>(null) }

    fun back() {
        if (busy) return
        when {
            preview != null -> {
                closePreview()
                previewFile = null
            }
            showSelected -> showSelected = false
            directoryPath.isNotEmpty() -> {
                directoryPath = directoryPath.dropLast(1)
                directoryNames = directoryNames.dropLast(1)
                search = ""
            }
            folder != null -> {
                folder = null
                search = ""
            }
            movingFile != null -> cancelMove()
            else -> onClose()
        }
    }
    fun systemBack() {
        handleAttachmentSystemBack(attachmentDialogView, previewOpen = preview != null, onBack = ::back)
    }
    Dialog(onDismissRequest = ::systemBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val currentDialogView = LocalView.current
        DisposableEffect(currentDialogView) {
            attachmentDialogView = currentDialogView
            onDispose { attachmentDialogView = null }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = ElementTheme.colors.bgCanvasDefault) {
            Column(Modifier.systemBarsPadding()) {
                AttachmentSearchHeader(
                    previewTitle = if (preview != null) previewFile?.name.orEmpty() else null,
                    search = search,
                    onSearch = {
                        search = it
                        longPressedUri = null
                    },
                    onBack = ::back,
                    showFolders = showFolders,
                    searchLabel = if (showFolders && folder == null) {
                        R.string.attachment_search_folders
                    } else if (showFolders) {
                        R.string.attachment_search_directory
                    } else {
                        R.string.attachment_search_short
                    },
                    folderTitle = if (showFolders && folder != null) {
                        val rootName = index?.folders?.firstOrNull { it.first == folder }?.second.orEmpty()
                        (listOf(context.getString(R.string.attachment_folders), rootName) + directoryNames).joinToString(" › ")
                    } else {
                        null
                    },
                    moveTitle = movingFile?.name,
                    onCancelMove = { if (!busy) cancelMove() },
                    navigationEnabled = !busy,
                    onRecent = {
                        movingFile = null
                        longPressedUri = null
                        directoryPath = emptyList()
                        directoryNames = emptyList()
                        showFolders = false
                        showSelected = false
                        folder = null
                        search = ""
                    },
                    onFolders = {
                        longPressedUri = null
                        directoryPath = emptyList()
                        directoryNames = emptyList()
                        showFolders = true
                        showSelected = false
                        folder = null
                        search = ""
                    },
                    onRefresh = {
                        forceRefresh = true
                        refresh++
                    },
                    refreshEnabled = !busy && !indexing,
                )
                if (error != null ||
                    libraryState.error) {
                        Text(error ?: openError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                if (busy || directoryLoading || (!showFolders && indexing)) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (preview != null && previewFile != null) {
                    val item = previewFile!!
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        renderer.Render(preview!!, renderMode = LocalMediaRenderMode.EMBEDDED)
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::closePreview) { Text(stringResource(R.string.attachment_back_list)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { toggle(item) }, enabled = selected.any { it.uri == item.uri } || selected.size < MAX_ATTACHMENTS) {
                            Text(stringResource(if (selected.any { it.uri == item.uri }) R.string.attachment_deselect else R.string.attachment_select))
                        }
                    }
                } else {
                    if (index?.failedSources != 0 && index != null) {
                        Text(
                            stringResource(R.string.attachment_sources_unavailable),
                        modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (index?.truncated == true) Text(stringResource(R.string.attachment_partial_index), modifier = Modifier.padding(horizontal = 16.dp))
                    if (!sharedAccess && !showFolders) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.attachment_shared_permission),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = ::requestSharedPermission) { Text(stringResource(R.string.attachment_enable)) }
                        }
                    }
                    if (pendingManagementId != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.attachment_management_pending),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { runManagement(resume = true) { fileManager.resume(accountId) } }, enabled = !busy) {
                                Text(stringResource(R.string.attachment_resume))
                            }
                            TextButton(onClick = { runManagement(resume = true) { fileManager.cancel(accountId) } }, enabled = !busy) {
                                Text(stringResource(R.string.attachment_stop))
                            }
                        }
                    }
                    if (managementError) {
                        Text(
                            stringResource(R.string.attachment_management_error),
                            color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    if (directoryError) {
                        Text(
                            stringResource(R.string.attachment_access_error),
                        color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    if (movedNotice) Text(stringResource(R.string.attachment_moved), modifier = Modifier.padding(horizontal = 16.dp))
                    if (showFolders) {
                        AttachmentFolderContent(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            roots = index?.folders.orEmpty(),
                            sources = sources,
                            currentSource = currentSource,
                            currentDirectory = currentDirectory,
                            listing = visibleDirectory,
                            insideFolder = folder != null,
                            search = search,
                            moving = movingFile != null,
                            enabled = !busy && !accessRequestInFlight && pendingManagementId == null,
                            canCreate = folder == null || (currentSource?.managed == true && sharedAccess),
                            highlightedUri = highlightedUri,
                            selected = selected,
                            onCreateFolder = { startManagement("folder") },
                            onCreateFile = { startManagement("text") },
                            onAddFolder = { requestSourcePermission() },
                            onEnterRoot = ::enterRoot,
                            onEnterDirectory = { directory ->
                                directoryPath = directoryPath + directory.uri.toString()
                                directoryNames = directoryNames + directory.name
                                search = ""
                            },
                            onChooseTarget = { moveTo(it) },
                            onPreview = ::openPreview,
                            onSelect = ::toggle,
                        )
                    } else {
                        AttachmentCategoryCapsules(category) {
                            category = it
                            longPressedUri = null
                        }
                        val displayed = (if (showSelected) selected else index?.files.orEmpty()).filter {
                            ((showSelected && category == "All") || matchesAttachmentCategory(it.name, category)) &&
                                it.name.contains(search.trim(), ignoreCase = true)
                        }
                        Text(
                            stringResource(if (showSelected) R.string.attachment_selected_count else R.string.attachment_file_count, displayed.size),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (displayed.isEmpty() && !busy && !indexing) {
                                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(if (sharedAccess ||
                                    sources.isNotEmpty()) {
                                        R.string.attachment_no_results
                                    } else {
                                        R.string.attachment_shared_empty
                                    }))
                                Text(
                                    stringResource(if (sharedAccess) R.string.attachment_shared_scope else R.string.attachment_shared_permission),
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            }
                            LazyColumn(Modifier.fillMaxSize(), state = recentListState) {
                                items(displayed, key = { it.uri.toString() }) { item ->
                                    val isSelected = selected.any { it.uri == item.uri }
                                    AttachmentFileRow(
                                        item = item,
                                        isSelected = isSelected,
                                        enabled = !busy,
                                        selectionEnabled = isSelected || selected.size < MAX_ATTACHMENTS,
                                        onPreview = { openPreview(item) },
                                        onSelect = { toggle(item) },
                                        moveVisible = longPressedUri == item.uri,
                                        onLongPress = { longPressedUri = item.uri },
                                        onMove = { startMove(item) },
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                    if (movingFile == null && (!showFolders || (folder != null && selected.isNotEmpty()))) {
                    HorizontalDivider()
                    AttachmentSelectionFooter(
                        selectedCount = selected.size,
                        busy = busy,
                        onSelected = {
                            showSelected = !showSelected
                            showFolders = false
                            folder = null
                            category = "All"
                            search = ""
                        },
                        onContinue = ::finish,
                    )
                    }
                }
            }
        }
        if (managementDialog != null) {
            val kind = managementDialog!!
            val ownFolders = sources.filter { it.managed }
            AlertDialog(
                onDismissRequest = {
                    if (!busy) {
                        managementDialog = null
                        managementError = false
                    }
                },
                title = {
                    Text(stringResource(
                        when (kind) {
                    "folder" -> R.string.attachment_create_folder
                    "text" -> R.string.attachment_create_file
                    else -> R.string.attachment_rename_collision
                }
                    ))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (kind == "text" && folder == null) {
                            if (ownFolders.isEmpty()) {
                                Text(stringResource(R.string.attachment_create_folder_first))
                                TextButton(onClick = { startManagement("folder") }) { Text(stringResource(R.string.attachment_create_folder)) }
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                                    items(ownFolders, key = { it.uri.toString() }) { source ->
                                        FilterChip(
                                            selected = source == managementFolder,
                                            onClick = { managementFolder = source },
                                            label = { Text(attachmentDiskFile(source.uri)?.name.orEmpty()) }
                                        )
                                    }
                                }
                            }
                        }
                        if (kind == "rename") Text(stringResource(R.string.attachment_collision_help))
                        if (kind == "folder" || kind == "text" || kind == "rename") {
                            OutlinedTextField(
                                value = managementName,
                                onValueChange = { managementName = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.attachment_name)) }
                            )
                        }
                        if (kind == "text") {
                            OutlinedTextField(
                                value = managementContent,
                                onValueChange = { managementContent = it },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                label = { Text(stringResource(R.string.attachment_file_content)) }
                            )
                        }
                        if (managementError) Text(stringResource(R.string.attachment_management_error), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && runCatching { attachmentSafeName(managementName) }.isSuccess &&
                        (kind == "folder" || managementFolder != null),
                        onClick = {
                        val targetFolder = managementFolder
                        val name = managementName
                        val content = managementContent
                        if (kind == "rename") {
                            moveTo(checkNotNull(targetFolder), name)
                        } else {
                            runManagement { operationId ->
                            when (kind) {
                                "folder" -> fileManager.createFolder(
                                    accountId,
                                    name,
                                    operationId,
                                    currentDirectory.takeIf { currentSource?.managed == true }
                                )
                                "text" -> fileManager.createText(accountId, checkNotNull(targetFolder), name, content, operationId)
                                else -> error("Unknown file management action")
                            }
                        }
                        }
                    }
                    ) { Text(stringResource(if (kind == "rename") R.string.attachment_move_action else R.string.attachment_save)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                    managementDialog = null
                    managementError = false
                }, enabled = !busy) { Text(stringResource(R.string.attachment_cancel)) }
                },
            )
        }
        BackHandler(onBack = ::systemBack)
    }
}

private const val MAX_ATTACHMENTS = 9
private val CATEGORIES = listOf(
    "All" to R.string.attachment_all,
    "Word" to R.string.attachment_word,
    "Excel" to R.string.attachment_excel,
    "PDF" to R.string.attachment_pdf,
    "PPT" to R.string.attachment_ppt,
    "Text" to R.string.attachment_text,
    "Archive" to R.string.attachment_archive,
    "Other" to R.string.attachment_other
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentFileRow(
    item: AttachmentFile,
    isSelected: Boolean,
    enabled: Boolean,
    selectionEnabled: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
    moveVisible: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(if (highlighted) ElementTheme.colors.bgSubtleSecondary else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                enabled = enabled,
                onClick = onPreview,
                onLongClick = onLongPress,
                onLongClickLabel = if (onLongPress != null) stringResource(R.string.attachment_move_action) else null
            )
        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val appearance = fileTypeAppearance(item.name.substringAfterLast('.', "FILE"))
        FileTypeIcon(appearance.label, appearance.color, Modifier.size(width = 46.dp, height = 52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val time = if (item.modified > 0) {
                DateFormat.format("MM-dd HH:mm", Date(item.modified)).toString()
            } else {
                stringResource(R.string.attachment_time_unknown)
            }
            Text(
                "${item.size?.let { Formatter.formatFileSize(context, it) } ?: stringResource(R.string.attachment_size_unknown)} · $time",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                item.source.ifBlank { stringResource(R.string.attachment_added_file) },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (moveVisible && onMove != null) {
            TextButton(onClick = onMove, enabled = enabled) { Text(stringResource(R.string.attachment_move_action)) }
        } else {
            Checkbox(
            checked = isSelected,
            onCheckedChange = { onSelect() },
        modifier = Modifier.semantics { contentDescription = item.name },
            enabled = enabled && selectionEnabled
        )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun AttachmentFileRowPreview() = ElementPreview {
    AttachmentFileRow(
        item = AttachmentFile(
            Uri.EMPTY,
            "项目合同.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            204800L,
            1790848800000L,
            1790848800000L,
            "Documents/合同",
            ""
        ),
        isSelected = true,
        enabled = true,
        selectionEnabled = true,
        onPreview = {},
        onSelect = {},
    )
}

@PreviewsDayNight
@Composable
internal fun AttachmentCollisionPreview() = ElementPreview {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.attachment_rename_collision)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.attachment_collision_help))
                OutlinedTextField("Budget-2.xlsx", {}, singleLine = true, label = { Text(stringResource(R.string.attachment_name)) })
            }
        },
        confirmButton = { TextButton(onClick = {}) { Text(stringResource(R.string.attachment_move_action)) } },
        dismissButton = { TextButton(onClick = {}) { Text(stringResource(R.string.attachment_cancel)) } },
    )
}

@Composable
internal fun AttachmentSmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = stringResource(label)) }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@PreviewsDayNight
@Composable
internal fun AttachmentFolderActionsPreview() = ElementPreview {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        AttachmentSmallAction(CompoundIcons.Plus(), R.string.attachment_create_folder, true) {}
        AttachmentSmallAction(CompoundIcons.Files(), R.string.attachment_create_file, true) {}
        AttachmentSmallAction(CompoundIcons.Folder(), R.string.attachment_add_folder, true) {}
    }
}

@Composable
internal fun AttachmentSearchHeader(
    previewTitle: String?,
    search: String,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    showFolders: Boolean = false,
    onRecent: () -> Unit = {},
    onFolders: () -> Unit = {},
    onRefresh: () -> Unit = {},
    refreshEnabled: Boolean = true,
    searchLabel: Int = R.string.attachment_search_short,
    folderTitle: String? = null,
    moveTitle: String? = null,
    onCancelMove: () -> Unit = {},
    navigationEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                enabled = navigationEnabled
            ) { Icon(CompoundIcons.ArrowLeft(), contentDescription = stringResource(R.string.attachment_back)) }
            if (previewTitle != null) {
                Text(previewTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Spacer(Modifier.weight(1f))
                AttachmentTab(R.string.attachment_latest, !showFolders, onRecent, navigationEnabled)
                AttachmentTab(R.string.attachment_folders, showFolders, onFolders, navigationEnabled)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                    Icon(CompoundIcons.Restart(), contentDescription = stringResource(R.string.attachment_refresh))
                }
            }
        }
        if (previewTitle == null) {
            if (moveTitle != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.attachment_choose_move_folder, moveTitle),
                    Modifier.weight(1f),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCancelMove, enabled = navigationEnabled) { Text(stringResource(R.string.attachment_cancel)) }
            }
            }
            if (folderTitle != null) {
                Text(
                    folderTitle,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = ElementTheme.typography.fontBodyMdMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = search,
                onValueChange = onSearch,
                singleLine = true,
                textStyle = ElementTheme.typography.fontBodySmRegular.copy(color = ElementTheme.colors.textPrimary),
                cursorBrush = SolidColor(ElementTheme.colors.textPrimary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(40.dp).background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(10.dp)),
                decorationBox = { inner ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (search.isEmpty()) {
                                Text(
                                    stringResource(searchLabel),
                                style = ElementTheme.typography.fontBodySmRegular,
                                    color = ElementTheme.colors.textSecondary
                                )
                            }
                            inner()
                        }
                        Icon(CompoundIcons.Search(), contentDescription = null, modifier = Modifier.size(20.dp), tint = ElementTheme.colors.iconSecondary)
                    }
                },
            )
        }
    }
}

@Composable
private fun AttachmentTab(label: Int, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Column(
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(label),
            style = ElementTheme.typography.fontBodyMdMedium,
            color = if (selected) ElementTheme.colors.textPrimary else ElementTheme.colors.textSecondary
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(16.dp).height(2.dp).background(if (selected) ElementTheme.colors.textPrimary else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
private fun AttachmentCategoryCapsules(category: String, onCategory: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(CATEGORIES) { (value, label) ->
            Box(Modifier.height(44.dp).clickable { onCategory(value) }, contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(14.dp), color = if (category ==
                    value) {
                        ElementTheme.colors.bgAccentRest
                    } else {
                        ElementTheme.colors.bgSubtleSecondary
                    }) {
                    Text(
                        stringResource(label),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = if (category == value) androidx.compose.ui.graphics.Color.White else ElementTheme.colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentSelectionFooter(
    selectedCount: Int,
    busy: Boolean,
    onSelected: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSelected) { Text(stringResource(R.string.attachment_selected_count, selectedCount)) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onContinue, enabled = selectedCount > 0 && !busy) {
            Text(stringResource(R.string.attachment_continue, selectedCount))
        }
    }
}

internal enum class AttachmentPickerPreviewState { Latest, Selected, Folders, PermissionMissing, Indexing, Empty, Preview }

internal class AttachmentPickerPreviewProvider : PreviewParameterProvider<AttachmentPickerPreviewState> {
    override val values = AttachmentPickerPreviewState.entries.asSequence()
}

@PreviewsDayNight
@Composable
internal fun AttachmentPickerStatesPreview(@PreviewParameter(AttachmentPickerPreviewProvider::class) state: AttachmentPickerPreviewState) = ElementPreview {
    val selected = state == AttachmentPickerPreviewState.Selected
    val folders = state == AttachmentPickerPreviewState.Folders
    val preview = state == AttachmentPickerPreviewState.Preview
    Column(Modifier.fillMaxWidth().height(640.dp)) {
        AttachmentSearchHeader(if (preview) "项目合同.docx" else null, "", {}, {}, showFolders = folders)
        if (state == AttachmentPickerPreviewState.Indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (preview) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.attachment_back_list))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(onClick = {}) { Text(stringResource(R.string.attachment_back_list)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = {}) { Text(stringResource(R.string.attachment_select)) }
            }
        } else {
            if (state == AttachmentPickerPreviewState.PermissionMissing) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.attachment_shared_permission), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {}) { Text(stringResource(R.string.attachment_enable)) }
                }
            }
            if (folders) {
                AttachmentFolderActionsPreview()
                ListItem(
                    headlineContent = { Text("我的合同") },
                    leadingContent = { Icon(CompoundIcons.Folder(), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.attachment_my_folder)) },
                )
            } else {
                AttachmentCategoryCapsules("All") {}
                if (state == AttachmentPickerPreviewState.Empty) {
                    Text(stringResource(R.string.attachment_no_results), modifier = Modifier.padding(24.dp))
                } else {
                    AttachmentFileRow(
                        AttachmentFile(
                            Uri.EMPTY,
                            "项目合同.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            204800L,
                            1790848800000L,
                            1790848800000L,
                            "Documents/合同",
                            ""
                        ),
                        selected,
                        true,
                        true,
                        {},
                        {},
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!folders) {
                HorizontalDivider()
                AttachmentSelectionFooter(if (selected) 1 else 0, false, {}, {})
            }
        }
    }
}
