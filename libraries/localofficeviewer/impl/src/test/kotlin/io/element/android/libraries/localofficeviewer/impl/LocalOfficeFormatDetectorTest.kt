package io.element.android.libraries.localofficeviewer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.LocalOfficeContentSource
import io.element.android.libraries.localofficeviewer.api.LocalOfficeFormat
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalOfficeFormatDetectorTest {
    @Test
    fun `office source beyond old 20 MiB limit is accepted with complete digest and cleared on close`() {
        val bytes = ByteArray(24 * 1024 * 1024)
        java.util.Random(642).nextBytes(bytes)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            .copyInto(bytes)
        var opens = 0
        val source = request("large.doc", "application/msword", bytes).copy(
            expectedSha256 = sha256(bytes),
            contentSource = LocalOfficeContentSource {
                opens++
                ByteArrayInputStream(bytes)
            },
        )
        val accepted = LocalOfficeFormatDetector.detect(source) as LocalOfficeDetection.Accepted
        assertThat(opens).isEqualTo(1)
        assertThat(accepted.summary.actualSize).isEqualTo(bytes.size.toLong())
        assertThat(accepted.summary.sha256).isEqualTo(sha256(bytes))
        val payloadStream = accepted.payload.openInputStream()
        accepted.payload.clear()
        assertThat(payloadStream.readBytes().all { it == 0.toByte() }).isTrue()
        payloadStream.close()
    }

    @Test
    fun `unknown length grows without truncating the complete source`() {
        val bytes = ByteArray(192 * 1024 + 17) { 'a'.code.toByte() }
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            .copyInto(bytes)
        val source = request("sample.doc", "application/msword", bytes).copy(declaredSize = null)
        val accepted = LocalOfficeFormatDetector.detect(source) as LocalOfficeDetection.Accepted
        assertThat(accepted.summary.sha256).isEqualTo(sha256(bytes))
        assertThat(accepted.payload.openInputStream().use { it.readBytes() }).isEqualTo(bytes)
        accepted.payload.clear()
    }

    @Test
    fun `known length overrun closes stream immediately before rendering`() {
        var closed = false
        val source = request("sample.doc", "application/msword", ByteArray(256)).copy(
            declaredSize = 8,
            contentSource = LocalOfficeContentSource {
                object : ByteArrayInputStream(ByteArray(256)) {
                    override fun close() {
                        closed = true
                    }
                }
            },
        )
        assertThat(LocalOfficeFormatDetector.detect(source)).isEqualTo(
            LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.DECLARED_SIZE_MISMATCH)
        )
        assertThat(closed).isTrue()
    }

    @Test
    fun `docx is fully read and accepted with a stable digest`() {
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf("word/document.xml" to "<document>Hello</document>".encodeToByteArray()),
        )

        val result = LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes))

        val accepted = result as LocalOfficeDetection.Accepted
        assertThat(accepted.summary).isEqualTo(
            LocalOfficeValidationSummary(
                format = LocalOfficeFormat.DOCX,
                actualSize = bytes.size.toLong(),
                sha256 = sha256(bytes),
            )
        )
        assertThat(accepted.payload.openInputStream().readBytes()).isEqualTo(bytes)
        accepted.payload.clear()
        assertThat(accepted.payload.isCleared()).isTrue()
    }

    @Test
    fun `first batch minimal documents pass format-specific admission`() {
        val samples = listOf(
            Triple("sample.docx", DOCX_MIME, ooxml(DOCX_CONTENT_TYPE, mapOf("word/document.xml" to "hello".encodeToByteArray()))),
            Triple("sample.xlsx", XLSX_MIME, ooxml(XLSX_CONTENT_TYPE, mapOf("xl/workbook.xml" to "sheet".encodeToByteArray()))),
            Triple("sample.pptx", PPTX_MIME, ooxml(PPTX_CONTENT_TYPE, mapOf("ppt/presentation.xml" to "slide".encodeToByteArray()))),
            Triple("sample.csv", "text/csv", "name,value\nalpha,1".encodeToByteArray()),
        )

        samples.forEach { (name, mime, bytes) ->
            val accepted = LocalOfficeFormatDetector.detect(request(name, mime, bytes)) as LocalOfficeDetection.Accepted
            assertThat(accepted.payload.openInputStream().readBytes()).isEqualTo(bytes)
            accepted.payload.clear()
        }
    }

    @Test
    fun `extension and OOXML family mismatch is blocked`() {
        val result = LocalOfficeFormatDetector.detect(
            request(
                "sample.docx",
                DOCX_MIME,
                ooxml(DOCX_CONTENT_TYPE, mapOf("xl/workbook.xml" to byteArrayOf(1))),
            )
        )

        assertThat(result).isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_STRUCTURE_INVALID))
    }

    @Test
    fun `macro extension with non macro declaration is blocked`() {
        val result = LocalOfficeFormatDetector.detect(
            request(
                "sample.xlsm",
                XLSM_MIME,
                ooxml(XLSX_CONTENT_TYPE, mapOf("xl/workbook.xml" to byteArrayOf(1))),
            )
        )

        assertThat(result).isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_STRUCTURE_INVALID))
    }

    @Test
    fun `legacy format requires OLE signature`() {
        val valid = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val accepted = LocalOfficeFormatDetector.detect(request("sample.doc", "application/msword", valid))

        assertThat((accepted as LocalOfficeDetection.Accepted).summary.format).isEqualTo(LocalOfficeFormat.DOC)
        accepted.payload.clear()
        assertThat(LocalOfficeFormatDetector.detect(request("sample.doc", "application/msword", byteArrayOf(1, 2, 3))))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.SIGNATURE_MISMATCH))
    }

    @Test
    fun `CSV containing NUL is blocked`() {
        assertThat(LocalOfficeFormatDetector.detect(request("sample.csv", "text/csv", byteArrayOf(65, 0, 66))))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.SIGNATURE_MISMATCH))
    }

    @Test
    fun `declared size must match the complete source`() {
        val bytes = "a,b\n1,2".encodeToByteArray()
        val request = request("sample.csv", "text/csv", bytes).copy(declaredSize = bytes.size + 1L)

        assertThat(LocalOfficeFormatDetector.detect(request))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.DECLARED_SIZE_MISMATCH))
    }

    @Test
    fun `host digest is checked when provided`() {
        val bytes = "a,b\n1,2".encodeToByteArray()
        val request = request("sample.csv", "text/csv", bytes).copy(expectedSha256 = "00".repeat(32))

        assertThat(LocalOfficeFormatDetector.detect(request))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.EXPECTED_DIGEST_MISMATCH))
    }

    @Test
    fun `oversized declared input is rejected before opening the source`() {
        var opened = false
        val request = LocalOfficePreviewRequest(
            operationId = "local-office-operation-0001",
            attachmentId = "attachment-1",
            displayName = "sample.docx",
            declaredMimeType = DOCX_MIME,
            declaredSize = LocalOfficeFormatDetector.MAX_INPUT_BYTES + 1,
            contentSource = LocalOfficeContentSource {
                opened = true
                ByteArrayInputStream(byteArrayOf(1))
            },
        )

        assertThat(LocalOfficeFormatDetector.detect(request))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.INPUT_SIZE_LIMIT))
        assertThat(opened).isFalse()
    }

    @Test
    fun `archive path traversal is blocked`() {
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = linkedMapOf(
                "word/document.xml" to byteArrayOf(1),
                "../escaped.xml" to byteArrayOf(2),
            ),
        )

        assertThat(LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes)))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_PATH_INVALID))
    }

    @Test
    fun `external OOXML relationship is blocked before rendering`() {
        val relationship = """
            <Relationships>
              <Relationship TargetMode="External" Target="https://example.invalid/pixel.png" />
            </Relationships>
        """.trimIndent().encodeToByteArray()
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf(
                "word/document.xml" to byteArrayOf(1),
                "word/_rels/document.xml.rels" to relationship,
            ),
        )

        assertThat(LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes)))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_EXTERNAL_RELATIONSHIP))
    }

    @Test
    fun `ordinary external hyperlink is admitted but remains non fetching`() {
        val relationship = """
            <Relationships>
              <Relationship
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                TargetMode="External"
                Target="https://example.invalid/read-only" />
            </Relationships>
        """.trimIndent().encodeToByteArray()
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf(
                "word/document.xml" to byteArrayOf(1),
                "word/_rels/document.xml.rels" to relationship,
            ),
        )

        val result = LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes))

        assertThat(result).isInstanceOf(LocalOfficeDetection.Accepted::class.java)
        (result as LocalOfficeDetection.Accepted).payload.clear()
    }

    @Test
    fun `prefixed ordinary external hyperlink is admitted but remains non fetching`() {
        val relationship = """
            <Relationships xmlns:r="urn:test">
              <r:Relationship
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                TargetMode="External"
                Target="https://example.invalid/read-only" />
            </Relationships>
        """.trimIndent().encodeToByteArray()
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf(
                "word/document.xml" to byteArrayOf(1),
                "word/_rels/document.xml.rels" to relationship,
            ),
        )

        val result = LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes))

        assertThat(result).isInstanceOf(LocalOfficeDetection.Accepted::class.java)
        (result as LocalOfficeDetection.Accepted).payload.clear()
    }

    @Test
    fun `external image relationship remains blocked`() {
        val relationship = """
            <Relationships>
              <Relationship
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                TargetMode="External"
                Target="https://example.invalid/pixel.png" />
            </Relationships>
        """.trimIndent().encodeToByteArray()
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf(
                "word/document.xml" to byteArrayOf(1),
                "word/_rels/document.xml.rels" to relationship,
            ),
        )

        assertThat(LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes)))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_EXTERNAL_RELATIONSHIP))
    }

    @Test
    fun `prefixed external image relationship remains blocked`() {
        val relationship = """
            <Relationships xmlns:r="urn:test">
              <r:Relationship
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                TargetMode="External"
                Target="https://example.invalid/pixel.png" />
            </Relationships>
        """.trimIndent().encodeToByteArray()
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf(
                "word/document.xml" to byteArrayOf(1),
                "word/_rels/document.xml.rels" to relationship,
            ),
        )

        assertThat(LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes)))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_EXTERNAL_RELATIONSHIP))
    }

    @Test
    fun `high expansion ratio archive is blocked`() {
        val bytes = ooxml(
            contentType = DOCX_CONTENT_TYPE,
            entries = mapOf("word/document.xml" to ByteArray(9 * 1024 * 1024)),
        )

        assertThat(LocalOfficeFormatDetector.detect(request("sample.docx", DOCX_MIME, bytes)))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.ARCHIVE_RATIO_LIMIT))
    }

    @Test
    fun `actual bytes beyond input budget are rejected during the first stream`() {
        val oversized = ByteArray(LocalOfficeFormatDetector.MAX_INPUT_BYTES.toInt() + 1) { 1 }
        val request = request("sample.doc", "application/msword", oversized).copy(declaredSize = null)

        assertThat(LocalOfficeFormatDetector.detect(request))
            .isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.INPUT_SIZE_LIMIT))
    }

    @Test
    fun `validation timeout closes blocking provider stream and terminates worker`() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val isClosed = AtomicBoolean(false)
        val blockingStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                enteredRead.countDown()
                while (!isClosed.get()) {
                    try {
                        released.await()
                    } catch (_: InterruptedException) {
                        // Closing the provider stream is the authoritative cancellation signal.
                    }
                }
                return -1
            }

            override fun close() {
                isClosed.set(true)
                released.countDown()
                closed.countDown()
            }
        }
        val request = LocalOfficePreviewRequest(
            operationId = "local-office-operation-0001",
            attachmentId = "attachment-1",
            displayName = "sample.docx",
            declaredMimeType = DOCX_MIME,
            declaredSize = null,
            contentSource = LocalOfficeContentSource { blockingStream },
        )
        val runner = LocalOfficeValidationRunner(request, timeoutMillis = 1_000)

        val result = runner.await()

        assertThat(enteredRead.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(result).isEqualTo(LocalOfficeDetection.SecurityBlocked(LocalOfficeBlockReason.VALIDATION_TIMEOUT))
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue()
        repeat(100) {
            if (runner.isWorkerTerminated()) return@repeat
            Thread.sleep(10)
        }
        assertThat(runner.isWorkerTerminated()).isTrue()
    }

    private fun request(name: String, mime: String, bytes: ByteArray) = LocalOfficePreviewRequest(
        operationId = "local-office-operation-0001",
        attachmentId = "attachment-1",
        displayName = name,
        declaredMimeType = mime,
        declaredSize = bytes.size.toLong(),
        contentSource = LocalOfficeContentSource { ByteArrayInputStream(bytes) },
    )

    private fun ooxml(contentType: String, entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                "<Types><Override PartName=\"/document\" ContentType=\"$contentType\" /></Types>".encodeToByteArray()
            )
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSM_MIME = "application/vnd.ms-excel.sheet.macroenabled.12"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        const val XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        const val PPTX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    }
}
