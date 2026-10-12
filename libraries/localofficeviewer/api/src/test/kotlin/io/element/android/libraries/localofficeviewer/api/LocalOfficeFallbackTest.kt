package io.element.android.libraries.localofficeviewer.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

class LocalOfficeFallbackTest {
    @Test
    fun `phase one fallback is explicitly not connected`() = runTest {
        val request = LocalOfficePreviewRequest(
            operationId = "local-office-operation-0001",
            attachmentId = "attachment-1",
            displayName = "sample.docx",
            declaredMimeType = null,
            declaredSize = 1,
            contentSource = LocalOfficeContentSource { ByteArrayInputStream(byteArrayOf(1)) },
        )

        assertThat(NotConnectedLocalOfficeFallback.requestFallback(request))
            .isEqualTo(LocalOfficeFallbackResult.NotConnected)
    }
}
