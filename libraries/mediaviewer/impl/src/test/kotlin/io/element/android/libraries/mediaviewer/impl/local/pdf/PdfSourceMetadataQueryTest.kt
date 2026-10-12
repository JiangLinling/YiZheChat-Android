/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfSourceMetadataQueryTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `protected source rechecks provider metadata on every invocation without payload streams`() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.packageName } returns "com.yizhe.chat.next.debug"
        every { context.contentResolver } returns resolver
        val uri = Uri.parse("content://com.yizhe.chat.next.debug.securemedia/v1/synthetic-token")
        var admitted = true
        val cursors = mutableListOf<MatrixCursor>()
        every { resolver.query(uri, any<Array<String>>(), null, null, null) } answers {
            if (!admitted) throw SecurityException("synthetic denial")
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).also { it.addRow(arrayOf(2972L)); cursors.add(it) }
        }
        var encodedSizeCalls = 0
        val check = checkNotNull(ParcelFileDescriptorFactory(context).sourceAccessCheck(uri) { encodedSizeCalls++; 2972L })
        check()
        check()
        admitted = false
        assertThrows(PdfSourceUnavailableException::class.java) { check() }
        verify(exactly = 3) { resolver.query(uri, any<Array<String>>(), null, null, null) }
        verify(exactly = 0) { resolver.openInputStream(any()) }
        verify(exactly = 0) { resolver.openFileDescriptor(any(), any()) }
        assertThat(cursors.all { it.isClosed }).isTrue()
        assertThat(encodedSizeCalls).isEqualTo(1)
    }

    @Test
    fun `ordinary content model keeps its existing admission path`() {
        val context = mockk<Context>()
        every { context.packageName } returns "com.yizhe.chat.next.debug"
        val factory = ParcelFileDescriptorFactory(context)
        assertThat(factory.sourceAccessCheck(Uri.parse("content://ordinary-provider/document/1")) { 2972L }).isNull()
        assertThat(factory.sourceAccessCheck(null) { 2972L }).isNull()
    }
}
