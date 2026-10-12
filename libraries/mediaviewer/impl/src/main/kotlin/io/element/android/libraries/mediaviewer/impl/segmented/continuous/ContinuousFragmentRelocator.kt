/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless container relocation, after D1 authenticated and parsed the exact init+fragment.
 * Independent fragments may contain absolute tfhd offsets relative to their own init. The virtual stream
 * changes only those eight-byte addresses; authenticated compressed samples, timestamps and cached bytes stay intact.
 */
internal class ContinuousFragmentRelocator private constructor(private val patches: List<Patch>) {
    fun apply(localPosition: Long, destination: ByteArray, offset: Int, count: Int) {
        patches.forEach { patch ->
            val start = maxOf(localPosition, patch.position)
            val end = minOf(localPosition + count, patch.position + patch.bytes.size)
            if (start < end) patch.bytes.copyInto(destination, offset + (start - localPosition).toInt(),
                (start - patch.position).toInt(), (end - patch.position).toInt())
        }
    }

    private data class Patch(val position: Long, val bytes: ByteArray)

    companion object {
        fun plan(initLength: Long, virtualFragmentStart: Long, fragment: ByteArray): ContinuousFragmentRelocator {
            val delta = Math.subtractExact(virtualFragmentStart, initLength)
            require(delta >= 0)
            val patches = mutableListOf<Patch>()
            fun walk(start: Int, end: Int, depth: Int) {
                if (depth > 3) throw IOException("Unexpected fMP4 nesting.")
                var position = start
                while (position < end) {
                    if (end - position < 8) throw IOException("Truncated fMP4 box.")
                    val input = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
                    val size32 = input.getInt(position).toLong() and 0xffff_ffffL
                    val header = if (size32 == 1L) 16 else 8
                    if (end - position < header) throw IOException("Truncated extended fMP4 box.")
                    val size = if (size32 == 1L) input.getLong(position + 8) else size32
                    if (size < header || size > end - position) throw IOException("Invalid fMP4 box length.")
                    val boxEnd = position + size.toInt()
                    val type = String(fragment, position + 4, 4, Charsets.US_ASCII)
                    when {
                        depth == 0 && type == "moof" || depth == 1 && type == "traf" -> walk(position + header, boxEnd, depth + 1)
                        depth == 2 && type == "tfhd" -> {
                            val body = position + header
                            if (boxEnd - body < 8) throw IOException("Truncated tfhd.")
                            val flags = input.getInt(body) and 0x00ff_ffff
                            if (flags and 1 != 0) {
                                if (boxEnd - body < 16) throw IOException("Missing tfhd base data offset.")
                                val original = input.getLong(body + 8)
                                if (original !in 0..Math.addExact(initLength, fragment.size.toLong())) throw IOException("tfhd offset escapes its verified source.")
                                val relocated = Math.addExact(original, delta)
                                patches += Patch(initLength + body + 8, ByteBuffer.allocate(8).putLong(relocated).array())
                            }
                        }
                    }
                    position = boxEnd
                }
            }
            walk(0, fragment.size, 0)
            return ContinuousFragmentRelocator(patches)
        }
    }
}
