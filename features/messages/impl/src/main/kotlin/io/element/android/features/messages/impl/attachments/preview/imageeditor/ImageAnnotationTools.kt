/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview.imageeditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import io.element.android.features.messages.impl.R
import io.element.android.libraries.androidutils.bitmap.rotateToExifMetadataOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

internal enum class ImageTool { CROP, PEN, TEXT, MOSAIC }

@Composable
internal fun ImageAnnotationCanvas(
    state: AttachmentImageEditorState, tool: ImageTool, color: Int, width: Float,
    onAnnotation: (ImageAnnotation) -> Unit, onReplaceAnnotations: (List<ImageAnnotation>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf<List<ImagePoint>>(emptyList()) }
    var textPoint by remember { mutableStateOf<ImagePoint?>(null) }
    var text by remember { mutableStateOf("") }
    var movingText by remember { mutableStateOf<Int?>(null) }
    val latestAnnotations by rememberUpdatedState(state.edits.annotations)
    val extra = if (draft.isEmpty()) {
        emptyList()
    } else {
        listOf(
        if (tool == ImageTool.MOSAIC) ImageAnnotation.Mosaic(draft, width) else ImageAnnotation.Stroke(draft, color, width)
    )
    }
    val bitmap = rememberAnnotationBitmap(state.copy(edits = state.edits.copy(annotations = state.edits.annotations + extra)))
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            val ratio = image.width.toFloat() / image.height
            val w = minOf(maxWidth, maxHeight * ratio)
            val h = w / ratio
            Image(
                image.asImageBitmap(),
                null,
                Modifier.size(w, h)
                    .pointerInput(tool, color, width) {
                        fun point(x: Float, y: Float) = ImagePoint((x / size.width).coerceIn(0f, 1f), (y / size.height).coerceIn(0f, 1f))
                        detectDragGestures(
                            onDragStart = { start ->
                                val p = point(start.x, start.y)
                                if (tool == ImageTool.TEXT) {
                                    movingText = latestAnnotations.indexOfLast { it is ImageAnnotation.Label }.takeIf { it >= 0 }
                                } else {
                                    draft = listOf(p)
                                }
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                val moving = movingText
                                if (tool == ImageTool.TEXT && moving != null) {
                                    val label = latestAnnotations[moving] as ImageAnnotation.Label
                                    val gestureWidth = size.width
                                    val gestureHeight = size.height
                                    onReplaceAnnotations(latestAnnotations.toMutableList().apply {
                                        set(moving, label.copy(point = ImagePoint(
                                            (label.point.x + delta.x / gestureWidth).coerceIn(0f, 1f),
                                            (label.point.y + delta.y / gestureHeight).coerceIn(0f, 1f)
                                        )))
                                    })
                                } else if (tool != ImageTool.TEXT) {
                                    draft = draft + point(change.position.x, change.position.y)
                                }
                            },
                            onDragEnd = {
                                if (draft.isNotEmpty()) {
                                    onAnnotation(if (tool ==
                                    ImageTool.MOSAIC) {
                                        ImageAnnotation.Mosaic(draft, width)
                                    } else {
                                        ImageAnnotation.Stroke(draft, color, width)
                                    })
                                }
                                draft = emptyList()
                                movingText = null
                            },
                            onDragCancel = {
                                draft = emptyList()
                                movingText = null
                            },
                        )
                    }
                    .pointerInput(tool, color, width) {
                        detectTapGestures { pos ->
                        if (tool ==
                            ImageTool.TEXT) {
                            textPoint = ImagePoint((pos.x / size.width).coerceIn(0f, 1f), (pos.y / size.height).coerceIn(0f, 1f))
                            text = ""
                            } else {
                            onAnnotation(if (tool ==
                            ImageTool.MOSAIC) {
                                ImageAnnotation.Mosaic(listOf(ImagePoint(pos.x / size.width, pos.y / size.height)), width)
                            } else {
                                ImageAnnotation.Stroke(listOf(ImagePoint(pos.x / size.width, pos.y / size.height)), color, width)
                            })
                        }
                    }
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
    if (textPoint != null) {
        AlertDialog(
        onDismissRequest = { textPoint = null },
        title = { Text(stringResource(R.string.media_edit_text)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onAnnotation(ImageAnnotation.Label(textPoint!!, text, color, max(.025f, width * 3)))
                textPoint =
            null
            }) { Text(stringResource(R.string.media_done)) }
        },
        dismissButton = { TextButton(onClick = { textPoint = null }) { Text(stringResource(R.string.media_cancel)) } },
    )
    }
}

@Composable
internal fun AnnotationPreview(state: AttachmentImageEditorState, modifier: Modifier = Modifier) {
    rememberAnnotationBitmap(state)?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
}

@Composable
private fun rememberAnnotationBitmap(state: AttachmentImageEditorState): Bitmap? {
    val context = LocalContext.current
    val source by produceState<Bitmap?>(
        null,
        state.localMedia.uri,
        state.edits.rotationQuarterTurns,
        state.edits.isFlippedHorizontally,
        state.edits.isFlippedVertically
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(state.localMedia.uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                val sample = generateSequence(1) { it * 2 }.first { max(bounds.outWidth, bounds.outHeight) / it <= 2048 }
                val orientation =
                    context.contentResolver.openInputStream(state.localMedia.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
                        ?: 0
                val decoded = context.contentResolver.openInputStream(state.localMedia.uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize =
                    sample
                    })
                }
                decoded?.let {
                    val normalized = it.rotateToExifMetadataOrientation(orientation)
                    val result = normalized.applyEdits(state.edits)
                    if (result !== normalized) normalized.recycle()
                    if (normalized !== it) it.recycle()
                    result
                }
            }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                timber.log.Timber.w("Image edit preview unavailable (%s)", failure.javaClass.simpleName)
                null
            }
        }
    }
    val rendered by produceState<Bitmap?>(null, source, state.edits.annotations) {
        value = withContext(Dispatchers.Default) {
            source?.copy(Bitmap.Config.ARGB_8888, true)?.also { renderAnnotations(android.graphics.Canvas(it), source!!, state.edits.annotations) }
        }
    }
    return rendered
}

@Composable
internal fun AnnotationToolbar(tool: ImageTool, color: Int, width: Float, onTool: (ImageTool) -> Unit, onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit, onDone: () -> Unit, showSettings: Boolean) {
    if (showSettings && tool != ImageTool.CROP) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tool != ImageTool.MOSAIC) {
                listOf(0xffff4444.toInt(), 0xffffffff.toInt(), 0xff07c160.toInt(), 0xffffd400.toInt(), 0xff222222.toInt()).forEach { candidate ->
                    TextButton(onClick = { onColor(candidate) }, modifier = Modifier.width(36.dp)) {
                        Text(if (candidate == color) "●" else "○", color = Color(candidate))
                    }
                }
            }
            Slider(value = width, onValueChange = onWidth, valueRange = 0.006f..0.06f, modifier = Modifier.weight(1f))
        }
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            ImageTool.PEN to R.string.media_edit_pen,
            ImageTool.TEXT to R.string.media_edit_text,
            ImageTool.CROP to R.string.media_edit_crop,
            ImageTool.MOSAIC to R.string.media_edit_mosaic
        ).forEach { (item, label) ->
            androidx.compose.material3.IconButton(onClick = { onTool(item) }) {
                val icon = when (item) {
                    ImageTool.PEN -> io.element.android.compound.tokens.generated.CompoundIcons.Edit()
                    ImageTool.TEXT -> io.element.android.compound.tokens.generated.CompoundIcons.TextFormatting()
                    ImageTool.CROP -> io.element.android.compound.tokens.generated.CompoundIcons.Crop()
                    ImageTool.MOSAIC -> io.element.android.compound.tokens.generated.CompoundIcons.Grid()
                }
                androidx.compose.material3.Icon(
                    icon,
                    stringResource(label),
                    modifier = Modifier.size(28.dp),
                    tint = if (showSettings && tool == item) Color(0xff07c160) else Color.White
                )
            }
        }
        androidx.compose.material3.Button(
            onClick = onDone,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xff07c160), contentColor = Color.White)
        ) {
            Text(stringResource(R.string.media_done), fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
}
