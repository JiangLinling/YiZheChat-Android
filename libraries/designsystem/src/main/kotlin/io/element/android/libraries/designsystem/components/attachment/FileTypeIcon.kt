/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.designsystem.components.attachment

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text

data class FileTypeAppearance(val label: String, val color: Color)

/** Shared by conversation attachments and the attachment browser. */
fun fileTypeAppearance(fileExtension: String): FileTypeAppearance {
    val extension = fileExtension.trimStart('.').uppercase().ifBlank { "FILE" }.take(4)
    val fileTypeLabel = when (extension) {
        "DOC", "DOCX" -> "W"
        "XLS", "XLSX" -> "X"
        "PPT", "PPTX" -> "P"
        else -> extension
    }
    val typeColor = when (extension) {
        "PDF" -> Color(0xFFE5484D)
        "DOC", "DOCX" -> Color(0xFF2474CC)
        "XLS", "XLSX" -> Color(0xFF10A760)
        "PPT", "PPTX" -> Color(0xFFF07F2F)
        "ZIP", "RAR", "7Z" -> Color(0xFFE28A22)
        else -> Color(0xFF6B7280)
    }
    return FileTypeAppearance(fileTypeLabel, typeColor)
}

@Composable
fun FileTypeIcon(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fold = 12.dp.toPx()
            val page = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width - fold, 0f)
                lineTo(size.width, fold)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(page, color)
            val foldedCorner = Path().apply {
                moveTo(size.width - fold, 0f)
                lineTo(size.width - fold, fold)
                lineTo(size.width, fold)
                close()
            }
            drawPath(foldedCorner, Color.White.copy(alpha = 0.35f))
        }
        Text(
            text = label,
            color = Color.White,
            style = ElementTheme.typography.fontBodyMdMedium,
            maxLines = 1,
        )
    }
}

internal class FileTypeIconProvider : PreviewParameterProvider<String> {
    override val values = sequenceOf("pdf", "docx", "xlsx", "pptx", "txt", "zip", "file")
}

@PreviewsDayNight
@Composable
internal fun FileTypeIconPreview(@PreviewParameter(FileTypeIconProvider::class) extension: String) = ElementPreview {
    val appearance = fileTypeAppearance(extension)
    FileTypeIcon(appearance.label, appearance.color, Modifier.size(width = 46.dp, height = 52.dp))
}
