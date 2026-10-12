package io.element.android.libraries.mediapickers.impl

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.components.attachment.fileTypeAppearance
import org.junit.Test

class AttachmentFileLogoTest {
    @Test
    fun `file logos retain the existing conversation colors and labels`() {
        assertThat(fileTypeAppearance(".pdf").label).isEqualTo("PDF")
        assertThat(fileTypeAppearance("pdf").color).isEqualTo(Color(0xFFE5484D))
        assertThat(fileTypeAppearance("DOCX").label).isEqualTo("W")
        assertThat(fileTypeAppearance("docx").color).isEqualTo(Color(0xFF2474CC))
        assertThat(fileTypeAppearance("xlsx").label).isEqualTo("X")
        assertThat(fileTypeAppearance("xlsx").color).isEqualTo(Color(0xFF10A760))
        assertThat(fileTypeAppearance("pptx").label).isEqualTo("P")
        assertThat(fileTypeAppearance("pptx").color).isEqualTo(Color(0xFFF07F2F))
        assertThat(fileTypeAppearance("zip").color).isEqualTo(Color(0xFFE28A22))
        assertThat(fileTypeAppearance("").label).isEqualTo("FILE")
    }
}
