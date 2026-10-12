/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediapickers.impl

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.element.android.libraries.mediapickers.api.MAX_GALLERY_ITEMS
import io.element.android.libraries.mediapickers.api.PickedMedia
import io.element.android.libraries.mediapickers.api.PickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class GalleryItem(val uri: Uri, val mime: String, val album: String, val albumId: String, val duration: Long)

/** Reads only the OS-authorized library; denial never causes an empty library to be presented as successful access. */
@Composable
internal fun rememberGalleryPicker(onResult: (List<PickedMedia>) -> Unit, preselected: List<PickedMedia>? = null): PickerLauncher<PickVisualMediaRequest, List<Uri>> {
    var visible by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable { mutableStateOf(ArrayList(preselected.orEmpty().map { it.uri.toString() })) }
    val original = rememberSaveable { mutableStateOf(preselected?.firstOrNull()?.originalPhotos == true) }
    val callback by rememberUpdatedState(onResult)
    val currentPreselected by rememberUpdatedState(preselected)
    if (visible) {
        GalleryPicker(
        selection = selection,
            originalChoice = original,
        onClose = {
            visible = false
            callback(emptyList())
        },
        onResult = {
            visible = false
            callback(it)
        },
    )
    }
    return remember {
        object : PickerLauncher<PickVisualMediaRequest, List<Uri>> {
            override fun launch() {
                selection.value = ArrayList(currentPreselected.orEmpty().map { it.uri.toString() })
                original.value = currentPreselected?.firstOrNull()?.originalPhotos == true
                visible = true
            }
            override fun launch(customInput: PickVisualMediaRequest) = launch()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryPicker(selection: MutableState<ArrayList<String>>, originalChoice: MutableState<Boolean>, onClose: () -> Unit, onResult: (List<PickedMedia>) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selected by selection
    var original by originalChoice
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    var previewUri by rememberSaveable { mutableStateOf<String?>(null) }
    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val access = refresh.let { permissions.any(::has) }
    val partial = Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
        !(has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO))
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_ITEMS)) { uris ->
        if (uris.isNotEmpty()) onResult(uris.map { PickedMedia(it, context.contentResolver.getType(it)) })
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var loadFailed by remember { mutableStateOf(false) }
    val library by produceState<List<GalleryItem>?>(null, refresh, access) {
        value = null
        loadFailed = false
        value = if (!access) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
            try {
                val collection = MediaStore.Files.getContentUri("external")
                val columns = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Video.Media.DURATION
                )
                buildList {
                    context.contentResolver.query(
                        collection,
                        columns,
                        "media_type IN (?, ?)",
                        arrayOf("1", "3"),
                        "date_added DESC, _id DESC"
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val isVideo = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            add(GalleryItem(
                                ContentUris.withAppendedId(base, cursor.getLong(0)),
                                cursor.getString(2) ?: if (isVideo) "video/mp4" else "image/jpeg",
                                cursor.getString(3).orEmpty(),
                                cursor.getString(4).orEmpty(),
                                cursor.getLong(5)
                            ))
                        }
                    }
                }
            } catch (_: SecurityException) {
                loadFailed = true
                emptyList()
            } catch (_: IllegalArgumentException) {
                loadFailed = true
                emptyList()
            }
        }
        }
    }
    LaunchedEffect(library) {
        library?.let { items -> selected = ArrayList(selected.filter { key -> items.any { it.uri.toString() == key } }) }
    }
    fun toggle(item: GalleryItem) {
        val key = item.uri.toString()
        if (key !in selected && selected.size >= MAX_GALLERY_ITEMS) {
            android.widget.Toast.makeText(context, context.getString(R.string.gallery_limit, MAX_GALLERY_ITEMS), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        selected = ArrayList(if (key in selected) {
            selected - key
        } else if (selected.size < MAX_GALLERY_ITEMS) {
            selected + key
        } else {
            selected
        })
    }
    fun finish(send: Boolean) {
        val items = library.orEmpty().associateBy { it.uri.toString() }
        onResult(
            (if (send && selected.isEmpty() &&
                previewUri != null) {
                    listOf(previewUri!!)
                } else {
                    selected
                }).mapNotNull { key -> items[key]?.let { PickedMedia(uri = it.uri, mimeType = it.mime, originalPhotos = original, sendImmediately = send) } }
        )
    }
    val visibleItems = library.orEmpty().filter { albumId == null || it.albumId == albumId }
    val previewItem = visibleItems.firstOrNull { it.uri.toString() == previewUri }
    fun editPreview(item: GalleryItem) {
        val items = library.orEmpty().associateBy { it.uri.toString() }
        val keys = selected + listOf(item.uri.toString()).filter { it !in selected }
        onResult(keys.mapNotNull { key ->
            items[key]?.let {
            PickedMedia(
                it.uri,
                it.mime,
                originalPhotos = original,
                initiallySelected = key in selected,
                focused = key == item.uri.toString(),
                openEditor = key == item.uri.toString()
            )
        }
        })
    }
    Dialog(onDismissRequest = {
        if (previewUri !=
        null) {
            previewUri = null
        } else {
            onClose()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color(0xff303030), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    IconButton(modifier = Modifier.align(Alignment.CenterStart), onClick = { if (previewUri != null) previewUri = null else onClose() }) {
                        androidx.compose.foundation.Canvas(Modifier.size(22.dp).semantics { contentDescription = context.getString(R.string.gallery_close) }) {
                            val inset = size.width * .15f
                            if (previewUri != null) {
                                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width - inset, inset), androidx.compose.ui.geometry.Offset(
                                    inset,
                                    size.height / 2
                                ), strokeWidth = 2.dp.toPx())
                                drawLine(
                                    Color.White,
                                    androidx.compose.ui.geometry.Offset(inset, size.height / 2),
                                    androidx.compose.ui.geometry.Offset(size.width - inset, size.height - inset),
                                    strokeWidth = 2.dp.toPx()
                                )
                            } else {
                                drawLine(Color.White, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(
                                    size.width - inset,
                                    size.height - inset
                                ), strokeWidth = 2.dp.toPx())
                                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width - inset, inset), androidx.compose.ui.geometry.Offset(
                                    inset,
                                    size.height - inset
                                ), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                        if (previewItem != null) {
                            Text("${visibleItems.indexOf(previewItem) + 1}/${visibleItems.size}", color = Color.White, fontSize = 18.sp)
                        } else {
                            TextButton(
                                onClick = { albumMenu = true },
                                modifier = Modifier.background(Color(0xff484848), androidx.compose.foundation.shape.RoundedCornerShape(32.dp)),
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                            ) {
                            Text(
                                (library.orEmpty().firstOrNull { it.albumId == albumId }?.album ?: stringResource(R.string.gallery_title)) + " ▾",
                                fontSize = 18.sp
                            )
                        }
                        }
                        DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_title)) }, onClick = {
                                albumId = null
                                albumMenu = false
                            })
                            library.orEmpty().distinctBy { it.albumId }.forEach { item ->
                                DropdownMenuItem(text = { Text(item.album) }, onClick = {
                                    albumId = item.albumId
                                    albumMenu = false
                                })
                            }
                        }
                    }
                    if (previewItem != null) {
                        Row(
                            Modifier.align(
                                Alignment.CenterEnd
                            ).toggleable(value = previewUri in selected, role = Role.Checkbox, onValueChange = { toggle(previewItem) })
                            .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(24.dp).border(1.5.dp, Color.White, CircleShape)
                                .background(if (previewUri in
                                    selected) {
                                        Color(0xff07c160)
                                    } else {
                                        Color.Transparent
                                    }, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (previewUri in selected) Text("✓", color = Color.White)
                            }
                            Text(stringResource(R.string.gallery_select), Modifier.padding(start = 6.dp), color = Color.White)
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
                if (partial) TextButton(onClick = { permissionLauncher.launch(permissions) }) { Text(stringResource(R.string.gallery_partial)) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        !access || loadFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.gallery_permission))
                            Button(onClick = { permissionLauncher.launch(permissions) }) { Text(stringResource(R.string.gallery_allow)) }
                            TextButton(onClick = { systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                                Text(stringResource(R.string.gallery_system_picker))
                            }
                        }
                        library == null -> CircularProgressIndicator()
                        library.orEmpty().isEmpty() -> Text(stringResource(R.string.gallery_empty))
                        previewItem != null -> {
                            val pager = rememberPagerState(initialPage = visibleItems.indexOf(previewItem), pageCount = { visibleItems.size })
                            LaunchedEffect(previewUri) {
                                val index = visibleItems.indexOfFirst { it.uri.toString() == previewUri }
                                if (index >= 0 && pager.currentPage != index) pager.scrollToPage(index)
                            }
                            LaunchedEffect(pager.settledPage) { previewUri = visibleItems.getOrNull(pager.settledPage)?.uri?.toString() }
                            HorizontalPager(
                                pager,
                                key = { visibleItems[it].uri.toString() },
                                modifier = Modifier.fillMaxSize().background(Color.Black)
                            ) { index ->
                                val item = visibleItems[index]
                                if (item.mime.startsWith("video/")) {
                                    GalleryVideoPreview(item.uri, Modifier.fillMaxSize())
                                } else {
                                    GalleryThumbnail(item, Modifier.fillMaxSize(), fit = true)
                                }
                            }
                        }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(visibleItems, key = { it.uri.toString() }) { item ->
                                Box(Modifier.aspectRatio(1f).clickable { previewUri = item.uri.toString() }) {
                                    GalleryThumbnail(item, Modifier.fillMaxSize())
                                    val number = selected.indexOf(item.uri.toString()) + 1
                                    val selectionLabel = stringResource(R.string.gallery_select)
                                    Text(
                                        if (number > 0) "$number" else "",
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                                            .background(if (number > 0) Color(0xff07c160) else Color.Black.copy(alpha = .25f), CircleShape)
                                            .border(2.dp, if (number > 0) Color(0xff07c160) else Color.White, CircleShape)
                                            .semantics { contentDescription = selectionLabel }
                                            .toggleable(value = number > 0, role = Role.Checkbox, onValueChange = { toggle(item) })
                                            .wrapContentSize(Alignment.Center)
                                    )
                                    if (item.duration >
                                        0) {
                                            Text(
                                                "${item.duration / 60000}:${(item.duration / 1000 % 60).toString().padStart(2, '0')}",
                                                Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .5f)).padding(4.dp)
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
                if (previewUri != null && selected.isNotEmpty()) {
                    LazyRow(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(selected, key = { it }) { key ->
                        library.orEmpty().firstOrNull { it.uri.toString() == key }?.let { item ->
                        GalleryThumbnail(item, Modifier.size(68.dp).border(if (previewUri ==
                            key) {
                                3.dp
                            } else {
                                0.dp
                            }, Color(0xff07c160)).clickable { previewUri = key })
                    }
                    }
                }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (previewItem != null) {
                        TextButton(onClick = { editPreview(previewItem) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                            Text(stringResource(R.string.gallery_edit), fontSize = 18.sp)
                        }
                    } else {
                        TextButton(
                            onClick = { finish(false) },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                        Text(
                            if (selected.isEmpty()) stringResource(R.string.gallery_preview_empty) else stringResource(R.string.gallery_preview, selected.size),
                            fontSize = 18.sp
                        )
                    }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.toggleable(value = original, role = Role.Checkbox, onValueChange = { original = it }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.padding(8.dp).size(22.dp).border(1.5.dp, Color.White, CircleShape)
                            .background(if (original) Color(0xff07c160) else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (original) Text("✓", color = Color.White)
                        }
                        Text(stringResource(R.string.gallery_original), fontSize = 18.sp)
                    }
                    }
                    Button(
                        onClick = { finish(true) },
                        enabled = selected.isNotEmpty() || previewItem != null,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xff07c160),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xff454545),
                            disabledContentColor = Color(0xff808080)
                        )
                    ) {
                        Text(
                        if (selected.isEmpty()) stringResource(R.string.gallery_send_empty) else stringResource(R.string.gallery_send, selected.size),
                        fontSize = 18.sp
                    )
                    }
                }
            }
        }
        BackHandler { if (previewUri != null) previewUri = null else onClose() }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun GalleryVideoPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
    }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(factory = {
        PlayerView(it).apply {
        this.player = player
        useController = true
    }
    }, modifier = modifier, update = { it.player = player })
}

@Composable
private fun GalleryThumbnail(item: GalleryItem, modifier: Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    if (fit && !item.mime.startsWith("video/")) {
        me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage(model = item.uri, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        return
    }
    val bitmap by produceState<Bitmap?>(null, item.uri, fit) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >=
                    29) {
                        context.contentResolver.loadThumbnail(item.uri, android.util.Size(if (fit) 1200 else 256, if (fit) 1200 else 256), null)
                    } else if (item.mime.startsWith("video/")) {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver,
                            ContentUris.parseId(item.uri),
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    } else {
                        context.contentResolver.openInputStream(item.uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply {
                                inSampleSize =
                    if (fit) 2 else 8
                            })
                        }
                    }
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.background(Color(0xff333333)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
            it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop
        )
        }
    }
}
