package io.element.android.libraries.mediapickers.impl

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentFileIndexTest {
    @Test
    fun `all contains the six attachment groups including Excel and other contains the remainder`() {
        listOf("合同.DOCX", "contract.doc", "a.PDF", "deck.pptx", "deck.PPT", "notes.TXT", "a.zip", "a.7z", "a.rar", "a.xlsx", "a.XLS", "a.xlsm", "a.csv")
            .forEach { assertThat(matchesAttachmentCategory(it, "All")).isTrue() }
        listOf("a.jpg", "a.mp4", "a.log", "a.md", "no-extension", "a.apk")
            .forEach {
                assertThat(matchesAttachmentCategory(it, "All")).isFalse()
                assertThat(matchesAttachmentCategory(it, "Other")).isTrue()
            }
        assertThat(matchesAttachmentCategory("a.log", "Text")).isFalse()
        assertThat(matchesAttachmentCategory("a.md", "Text")).isFalse()
        assertThat(matchesAttachmentCategory("a.TXT", "Text")).isTrue()
        assertThat(matchesAttachmentCategory("a.XLSX", "Excel")).isTrue()
        assertThat(matchesAttachmentCategory("a.xls", "Other")).isFalse()
    }

    @Test
    fun `authorized folder recursively returns newest modified files first`() = runTest {
        val resolver = mockk<ContentResolver>()
        val root = Uri.parse("content://qa.documents/tree/root")
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>().toString()
            val columns = secondArg<Array<String>>()
            MatrixCursor(columns).apply {
                when {
                    uri.endsWith("document/root") -> addRow(arrayOf<Any?>("QA documents"))
                    uri.endsWith("document/root/children") -> {
                        addRow(arrayOf<Any?>("old", "old.pdf", "application/pdf", 12L, 100L))
                        addRow(arrayOf<Any?>("marker", MANAGED_FOLDER_MARKER, "application/octet-stream", 36L, 300L))
                        addRow(arrayOf<Any?>("nested", "Nested", DocumentsContract.Document.MIME_TYPE_DIR, null, 0L))
                    }
                    uri.endsWith("document/nested/children") -> addRow(arrayOf<Any?>("new", "合同.DOCX", "application/octet-stream", 30L, 200L))
                    else -> error("Unexpected query")
                }
            }
        }
        val result = scanAttachmentSources(resolver, listOf(AttachmentSource(root, true, 1000)))
        assertThat(result.files.map { it.name }).containsExactly("合同.DOCX", "old.pdf").inOrder()
        assertThat(result.files.map { it.source }.distinct()).containsExactly("QA documents")
        assertThat(result.failedSources).isEqualTo(0)
        assertThat(attachmentCategory(result.files.first().name)).isEqualTo("Word")
    }

    @Test
    fun `revoked source is reported separately from an empty folder`() = runTest {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } throws SecurityException("revoked")
        val result = scanAttachmentSources(resolver, listOf(AttachmentSource(Uri.parse("content://qa.documents/document/gone"), false, 0)))
        assertThat(result.files).isEmpty()
        assertThat(result.failedSources).isEqualTo(1)
    }

    @Test
    fun `providers without modification dates do not get fabricated save times`() = runTest {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://qa.documents/document/unknown")
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(arrayOf("_display_name", "_size")).apply { addRow(arrayOf<Any?>("unknown.pdf", null)) }
        }
        every { resolver.getType(uri) } returns "application/pdf"
        val result = scanAttachmentSources(resolver, listOf(AttachmentSource(uri, false, 123)))
        assertThat(result.files.single().modified).isEqualTo(0)
        assertThat(result.files.single().size).isNull()
        assertThat(result.files.single().addedAt).isEqualTo(123)
    }
}
