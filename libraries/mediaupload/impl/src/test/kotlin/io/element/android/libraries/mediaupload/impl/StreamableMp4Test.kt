/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class StreamableMp4Test {
    @get:Rule val temporary = TemporaryFolder()
    private fun box(type: String, bytes: ByteArray) = ByteBuffer.allocate(bytes.size + 8)
        .putInt(bytes.size + 8).put(type.toByteArray(Charsets.US_ASCII)).put(bytes).array()
    private fun moov(offsets: List<Long>, wide: Boolean): ByteArray {
        val entries = ByteBuffer.allocate(8 + offsets.size * if (wide) 8 else 4).putInt(0).putInt(offsets.size)
        for (offset in offsets) { if (wide) entries.putLong(offset) else entries.putInt(offset.toInt()) }
        return box("moov", box("trak", box("mdia", box("minf", box("stbl", box(if (wide) "co64" else "stco", entries.array()))))))
    }
    private fun offsets(bytes: ByteArray, wide: Boolean): List<Long> {
        val type = if (wide) "co64" else "stco"
        val at = bytes.toString(Charsets.ISO_8859_1).indexOf(type) + 4
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.getInt(at + 4)
        return (0 until count).map { index ->
            if (wide) buffer.getLong(at + 8 + index * 8) else buffer.getInt(at + 8 + index * 4).toLong() and 0xffffffffL
        }
    }

    @Test fun `lossless relocation repairs both offset table types and preserves samples before and after old metadata`() {
        for (wide in listOf(false, true)) {
            val ftyp = box("ftyp", ByteArray(8) { 3 })
            val media1 = box("mdat", ByteArray(300) { it.toByte() })
            val media2 = box("mdat", ByteArray(73) { (it + 9).toByte() })
            val size = moov(listOf(0, 0), wide).size
            val before = (ftyp.size + 8 + 29).toLong()
            val after = (ftyp.size + media1.size + size + 8 + 11).toLong()
            val original = ftyp + media1 + moov(listOf(before, after), wide) + media2
            val file = temporary.newFile("relocate-$wide.mp4").also { it.writeBytes(original) }
            ensureStreamableMp4(file)
            val actual = file.readBytes()
            assertThat(actual.size).isEqualTo(original.size)
            assertThat(actual.copyOfRange(ftyp.size + 4, ftyp.size + 8).toString(Charsets.US_ASCII)).isEqualTo("moov")
            val newOffsets = offsets(actual, wide)
            assertThat(newOffsets).containsExactly(before + size, after).inOrder()
            for ((old, new) in listOf(before to newOffsets[0], after to newOffsets[1])) {
                assertThat(actual.copyOfRange(new.toInt(), new.toInt() + 17)).isEqualTo(original.copyOfRange(old.toInt(), old.toInt() + 17))
            }
            assertThat(file.parentFile.listFiles()?.filter { it.name.startsWith("streamable-") }).isEmpty()
            ensureStreamableMp4(file)
            assertThat(file.readBytes()).isEqualTo(actual)
        }
    }

    @Test fun `metadata extending to EOF gets an explicit length when moved before media`() {
        val ftyp = box("ftyp", ByteArray(8))
        val media = box("mdat", ByteArray(100) { it.toByte() })
        val metadata = moov(listOf((ftyp.size + 8).toLong()), false)
        ByteBuffer.wrap(metadata).putInt(0, 0)
        val file = temporary.newFile("metadata-to-eof.mp4").also { it.writeBytes(ftyp + media + metadata) }
        ensureStreamableMp4(file)
        val actual = file.readBytes()
        assertThat(ByteBuffer.wrap(actual).getInt(ftyp.size)).isEqualTo(metadata.size)
        val relocated = offsets(actual, false).single().toInt()
        assertThat(actual.copyOfRange(relocated, relocated + 100)).isEqualTo(media.copyOfRange(8, 108))
    }

    @Test fun `cancellation before mutation leaves owned output intact and cancellation during mutation removes it`() {
        val ftyp = box("ftyp", ByteArray(8))
        val original = ftyp + box("mdat", ByteArray(100_000)) + moov(listOf((ftyp.size + 8).toLong()), false)
        val file = temporary.newFile("cancel.mp4").also { it.writeBytes(original) }
        var boundaries = 0
        assertThat(runCatching {
            ensureStreamableMp4(file) { if (++boundaries == 1) throw kotlinx.coroutines.CancellationException() }
        }.isFailure).isTrue()
        assertThat(file.readBytes()).isEqualTo(original)
        boundaries = 0
        assertThat(runCatching {
            ensureStreamableMp4(file) { if (++boundaries == 2) throw kotlinx.coroutines.CancellationException() }
        }.isFailure).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(file.parentFile.listFiles()?.filter { it.name.startsWith("streamable-") }).isEmpty()
    }

    @Test fun `forged chunk offsets and malformed boxes cannot replace the original producer file`() {
        val cases = listOf(
            box("ftyp", ByteArray(8)) + box("mdat", ByteArray(100)) + moov(listOf(1), false),
            box("ftyp", ByteArray(8)) + byteArrayOf(0, 0, 0, 7, 109, 100, 97, 116),
        )
        for ((index, original) in cases.withIndex()) {
            val file = temporary.newFile("invalid-$index.mp4").also { it.writeBytes(original) }
            assertThat(runCatching { ensureStreamableMp4(file) }.isFailure).isTrue()
            assertThat(file.readBytes()).isEqualTo(original)
        }
    }
}
