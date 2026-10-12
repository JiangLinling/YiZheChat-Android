/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaupload.impl

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only accepts the new transcode-owned output, never the picker original. A failed mutation deletes this owned output. */
internal fun ensureStreamableMp4(file: File, checkCancelled: () -> Unit = {}) {
    RandomAccessFile(file, "rw").use { input ->
        val boxes = mp4TopBoxes(input)
        val moov = boxes.single { it.type == "moov" }
        val media = boxes.filter { it.type == "mdat" }
        require(media.isNotEmpty())
        if (moov.start < media.first().start) return
        require(moov.length in 8L..16L * 1024 * 1024)
        val metadata = ByteArray(moov.length.toInt())
        input.seek(moov.start)
        input.readFully(metadata)
        val buffer = ByteBuffer.wrap(metadata).order(ByteOrder.BIG_ENDIAN)
        // A size-zero box reaches EOF; after relocation it must have an explicit size.
        if (buffer.getInt(0) == 0) buffer.putInt(0, metadata.size)
        var tables = 0
        fun visit(start: Int, end: Int) {
            var at = start
            while (at < end) {
                require(end - at >= 8)
                val size = buffer.getInt(at).toLong() and 0xffffffffL
                val type = String(metadata, at + 4, 4, Charsets.US_ASCII)
                val header = if (size == 1L) 16 else 8
                val length = when (size) { 0L -> (end - at).toLong(); 1L -> buffer.getLong(at + 8); else -> size }
                require(length >= header && length <= end - at)
                val body = at + header
                val next = at + length.toInt()
                when (type) {
                    "moov", "trak", "mdia", "minf", "stbl" -> visit(body, next)
                    "stco", "co64" -> {
                        require(next - body >= 8 && buffer.getInt(body) == 0)
                        val count = buffer.getInt(body + 4).toLong() and 0xffffffffL
                        val width = if (type == "stco") 4 else 8
                        require(count * width == (next - body - 8).toLong())
                        for (index in 0 until count.toInt()) {
                            val offsetAt = body + 8 + index * width
                            val offset = if (width == 4) buffer.getInt(offsetAt).toLong() and 0xffffffffL else buffer.getLong(offsetAt)
                            require(media.any { offset >= it.start + it.header && offset < it.start + it.length })
                            val adjusted = if (offset < moov.start) Math.addExact(offset, moov.length) else offset
                            if (width == 4) { require(adjusted <= 0xffffffffL); buffer.putInt(offsetAt, adjusted.toInt()) }
                            else buffer.putLong(offsetAt, adjusted)
                        }
                        tables++
                    }
                }
                at = next
            }
            require(at == end)
        }
        var mutationStarted = false
        try {
            visit(moov.header, metadata.size)
            require(tables > 0)
            val copyBuffer = ByteArray(65_536)
            try {
                val insertAt = media.first().start
                var remaining = moov.start - insertAt
                while (remaining > 0) {
                    checkCancelled()
                    val length = minOf(remaining, copyBuffer.size.toLong()).toInt()
                    val from = insertAt + remaining - length
                    input.seek(from)
                    input.readFully(copyBuffer, 0, length)
                    mutationStarted = true
                    input.seek(from + moov.length)
                    input.write(copyBuffer, 0, length)
                    remaining -= length
                }
                checkCancelled()
                mutationStarted = true
                input.seek(insertAt)
                input.write(metadata)
                input.fd.sync()
            } finally { copyBuffer.fill(0) }
        } catch (failure: Throwable) {
            if (mutationStarted) {
                input.close()
                if (file.exists() && !file.delete()) failure.addSuppressed(IllegalStateException("Owned MP4 output cleanup failed."))
            }
            throw failure
        } finally {
            metadata.fill(0)
        }
    }
}

private data class Mp4Box(val start: Long, val length: Long, val header: Int, val type: String)
private fun mp4TopBoxes(file: RandomAccessFile): List<Mp4Box> {
    val boxes = mutableListOf<Mp4Box>()
    var offset = 0L
    val typeBytes = ByteArray(4)
    while (offset < file.length()) {
        require(file.length() - offset >= 8)
        file.seek(offset)
        val size = file.readInt().toLong() and 0xffffffffL
        file.readFully(typeBytes)
        val header = if (size == 1L) 16 else 8
        val length = when (size) { 0L -> file.length() - offset; 1L -> file.readLong(); else -> size }
        require(length >= header && length <= file.length() - offset)
        boxes.add(Mp4Box(offset, length, header, String(typeBytes, Charsets.US_ASCII)))
        require(boxes.size <= 4096)
        offset += length
    }
    require(boxes.firstOrNull()?.type == "ftyp")
    return boxes
}
