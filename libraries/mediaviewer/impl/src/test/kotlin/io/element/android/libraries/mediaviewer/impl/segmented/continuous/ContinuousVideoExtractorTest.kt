/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.DummyTrackOutput
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ContinuousVideoExtractorTest {
    @Test
    fun `real Media3 reads multiple relocated fragments in one continuous source`() {
        val init = SegmentedMediaFmp4FixturePrototype.init
        val fragment = SegmentedMediaFmp4FixturePrototype.segment
        val layout = ContinuousVideoLayout(init.size.toLong(), List(3) { ContinuousVideoPart(fragment.size.toLong(), it * 1_000_000L, 1_000_000) })
        val stream = init + layout.parts.indices.flatMap { index ->
            val copy = fragment.copyOf()
            val relocation = ContinuousFragmentRelocator.plan(init.size.toLong(), layout.starts[index], fragment)
            relocation.apply(init.size.toLong(), copy, 0, copy.size)
            copy.toList()
        }.toByteArray()
        assertThat(fragment).isEqualTo(SegmentedMediaFmp4FixturePrototype.segment)
        val timings = layout.parts.map { ContinuousFragmentTimeline.inspect(init, fragment, it) }
        val extractor = ContinuousVideoExtractor(layout) { timings[it] }
        val sampleTimes = mutableListOf<Long>()
        var seekMap: SeekMap? = null
        val bytes = ByteArrayInputStream(stream)
        val input = DefaultExtractorInput({ destination, offset, length -> bytes.read(destination, offset, length) }, 0, stream.size.toLong())
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val discard = DummyTrackOutput()
                return object : TrackOutput by discard {
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (type == C.TRACK_TYPE_VIDEO) sampleTimes += timeUs
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(value: SeekMap) { seekMap = value }
        }
        try {
            assertThat(extractor.sniff(input)).isTrue()
            input.resetPeekPosition()
            extractor.init(output)
            var reads = 0
            while (true) {
                check(++reads < 1000)
                val result = extractor.read(input, PositionHolder())
                if (result == Extractor.RESULT_END_OF_INPUT) break
                assertThat(result).isEqualTo(Extractor.RESULT_CONTINUE)
            }
            assertThat(input.position).isEqualTo(stream.size.toLong())
            assertThat(requireNotNull(seekMap).isSeekable).isTrue()
            val seek = requireNotNull(seekMap).getSeekPoints(1_500_000).first
            assertThat(seek.timeUs).isEqualTo(1_000_000)
            assertThat(seek.position).isEqualTo(layout.starts[1])
            assertThat(sampleTimes.map { it / 1_000_000L }.distinct()).containsExactly(0L, 1L, 2L).inOrder()
            // Seek uses the same extractor and jumps directly to an independent fragment, without rereading init.
            sampleTimes.clear()
            val target = layout.starts[2]
            val tail = ByteArrayInputStream(stream, target.toInt(), stream.size - target.toInt())
            val seekInput = DefaultExtractorInput({ destination, offset, length -> tail.read(destination, offset, length) }, target, stream.size.toLong())
            extractor.seek(target, 2_000_000)
            while (extractor.read(seekInput, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
            assertThat(sampleTimes).isNotEmpty()
            assertThat(sampleTimes.min()).isEqualTo(2_000_000)
        } finally { extractor.release() }
    }

    @Test
    fun `relocation across one byte reads equals a full read and never changes original bytes`() {
        val fragment = SegmentedMediaFmp4FixturePrototype.segment.copyOf()
        val original = fragment.copyOf()
        val initLength = SegmentedMediaFmp4FixturePrototype.init.size.toLong()
        val relocation = ContinuousFragmentRelocator.plan(initLength, initLength + fragment.size, fragment)
        val whole = fragment.copyOf().also { relocation.apply(initLength, it, 0, it.size) }
        val small = fragment.copyOf()
        small.indices.forEach { relocation.apply(initLength + it, small, it, 1) }
        assertThat(small).isEqualTo(whole)
        assertThat(fragment).isEqualTo(original)
        assertThat(whole).isNotEqualTo(original)
    }

    @Test(expected = IOException::class)
    fun `truncated container metadata fails closed before decoder handoff`() {
        ContinuousFragmentRelocator.plan(100, 200, byteArrayOf(0, 0, 0, 8, 109, 111, 111))
    }
}
