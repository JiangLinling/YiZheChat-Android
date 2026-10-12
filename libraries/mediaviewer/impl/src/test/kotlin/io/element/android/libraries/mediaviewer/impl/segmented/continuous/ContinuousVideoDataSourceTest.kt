/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
class ContinuousVideoDataSourceTest {
    private val uri = Uri.parse("yizhe-segmented-video://continuous-test")
    private val layout = ContinuousVideoLayout(2, List(3) { ContinuousVideoPart(4, it * 1_000_000L, 1_000_000) })

    @Test
    fun `cleanup failure fences shared owner and cannot admit another reader`() {
        var failed = false
        val broken = object : ContinuousVideoFragment {
            override suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int = count
            override suspend fun probe() = Unit
            override fun revoke() = Unit
            override suspend fun closeAndAwait() { throw IOException("incomplete cleanup") }
        }
        val buffer = ContinuousVideoBuffer(layout, Dispatchers.IO, { broken }, { true })
        val source = ContinuousVideoDataSource(uri, buffer, { failed = true }, {})
        source.open(DataSpec(uri))
        assertThat(runCatching { source.close() }.isFailure).isTrue()
        assertThat(failed).isTrue()
        assertThat(runCatching { source.open(DataSpec(uri)) }.isFailure).isTrue()
    }

    @Test
    fun `EOF keeps authority alive and the same DataSource reopens with a fresh bounded generation`() {
        val generations = mutableListOf<ContinuousVideoBuffer>()
        val released = mutableListOf<ContinuousVideoBuffer>()
        val source = ContinuousVideoDataSource(uri, {
            ContinuousVideoBuffer(layout, Dispatchers.IO, { fragment(it) }, { true }).also(generations::add)
        }, { error("Unexpected failure") }, released::add, retainAuthorityAtEnd = true)
        try {
            source.open(DataSpec(uri))
            val scratch = ByteArray(20)
            while (source.read(scratch, 0, scratch.size) != -1) Unit
            source.close()
            val old = generations.single()
            assertThat(old.isClosed).isFalse()
            runBlocking { old.probe() }
            source.open(DataSpec.Builder().setUri(uri).setPosition(layout.starts[2]).build())
            assertThat(old.isClosed).isTrue()
            assertThat(released).containsExactly(old)
            assertThat(generations).hasSize(2)
            assertThat(source.read(scratch, 0, 1)).isEqualTo(1)
            assertThat(scratch[0]).isEqualTo(2.toByte())
        } finally {
            source.close()
            runBlocking { generations.forEach { it.closeAndAwait() } }
        }
        assertThat(released).hasSize(2)
    }

    @Test
    fun `range open starts at the target fragment and skips duplicate init`() {
        var released = false
        val buffer = ContinuousVideoBuffer(layout, Dispatchers.IO, { fragment(it) }, { true })
        val source = ContinuousVideoDataSource(uri, buffer, { error("Unexpected failure") }, { released = true })
        try {
            assertThat(source.open(DataSpec.Builder().setUri(uri).setPosition(layout.starts[2]).build())).isEqualTo(4)
            val result = ByteArray(4)
            assertThat(source.read(result, 0, 4)).isEqualTo(4)
            assertThat(result).isEqualTo(ByteArray(4) { 2 })
            assertThat(source.read(result, 0, 4)).isEqualTo(-1)
        } finally { source.close() }
        assertThat(released).isTrue()
        assertThat(buffer.isClosed).isTrue()
    }

    @Test
    fun `loader interruption fences only the old reader and preserves shared owner`() {
        var failed = false
        val buffer = ContinuousVideoBuffer(layout, Dispatchers.IO, { fragment(it, InterruptedIOException("seek cancellation")) }, { true })
        val source = ContinuousVideoDataSource(uri, buffer, { failed = true }, {})
        try {
            source.open(DataSpec(uri))
            assertThat(runCatching { source.read(ByteArray(4), 0, 4) }.isFailure).isTrue()
            assertThat(failed).isFalse()
            assertThat(buffer.isClosed).isTrue()
        } finally { source.close() }
    }

    @Test
    fun `authenticated read failure clears handoff and closes shared owner`() {
        var failed = false
        val buffer = ContinuousVideoBuffer(layout, Dispatchers.IO, { fragment(it, IOException("lease revoked")) }, { true })
        val source = ContinuousVideoDataSource(uri, buffer, { failed = true }, {})
        try {
            source.open(DataSpec(uri))
            val result = ByteArray(4) { 7 }
            assertThat(runCatching { source.read(result, 0, 4) }.isFailure).isTrue()
            assertThat(result).isEqualTo(ByteArray(4))
            assertThat(failed).isTrue()
        } finally { source.close() }
    }

    @Test
    fun `unbound URI or escaping range cannot trigger acquisition`() {
        var acquired = false
        val buffer = ContinuousVideoBuffer(layout, Dispatchers.IO, { acquired = true; fragment(it) }, { true })
        val source = ContinuousVideoDataSource(uri, buffer, {}, {})
        try {
            assertThat(runCatching { source.open(DataSpec(Uri.parse("https://example.invalid/video"))) }.isFailure).isTrue()
            assertThat(runCatching { source.open(DataSpec.Builder().setUri(uri).setLength(layout.length + 1).build()) }.isFailure).isTrue()
            assertThat(acquired).isFalse()
        } finally { source.close() }
    }

    private fun fragment(index: Int, failure: IOException? = null) = object : ContinuousVideoFragment {
        private val data = byteArrayOf(9, 9) + ByteArray(4) { index.toByte() }
        override suspend fun read(position: Long, destination: ByteArray, offset: Int, count: Int): Int {
            data.copyInto(destination, offset, position.toInt(), position.toInt() + count)
            failure?.let { throw it }
            return count
        }
        override suspend fun probe() = Unit
        override fun revoke() = Unit
        override suspend fun closeAndAwait() = Unit
    }
}
