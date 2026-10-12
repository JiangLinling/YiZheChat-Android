/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.IOException

internal data class ContinuousFragmentTiming(val firstSampleUsByTrack: Map<Int, Long>)

/** Retains only track timing. Called on D1's bounded authenticated bytes before they are wiped. */
internal object ContinuousFragmentTimeline {
    fun inspect(init: ByteArray, fragment: ByteArray, part: ContinuousVideoPart): ContinuousFragmentTiming {
        var position = 0
        val input = DefaultExtractorInput(DataReader { destination, offset, length ->
            if (position == init.size + fragment.size) C.RESULT_END_OF_INPUT else {
                val source = if (position < init.size) init else fragment
                val local = if (position < init.size) position else position - init.size
                val count = minOf(length, source.size - local)
                source.copyInto(destination, offset, local, local + count)
                position += count
                count
            }
        }, 0, init.size.toLong() + fragment.size)
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        val firstSamples = mutableMapOf<Int, Long>()
        val extractor = FragmentedMp4Extractor(DefaultSubtitleParserFactory())
        try {
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val discard = DummyTrackOutput()
                    return object : TrackOutput by discard {
                        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                            firstSamples.putIfAbsent(id, timeUs)
                            if (type == C.TRACK_TYPE_VIDEO) {
                                first = minOf(first, timeUs)
                                last = maxOf(last, timeUs)
                            }
                        }
                    }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            while (true) {
                when (extractor.read(input, PositionHolder())) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_CONTINUE -> Unit
                    else -> throw IOException("Independent video fragment requested an external seek.")
                }
            }
            if (first == Long.MAX_VALUE || Math.subtractExact(last, first) >= part.durationUs) {
                throw IOException("Video samples exceed the authenticated fragment timeline.")
            }
            return ContinuousFragmentTiming(firstSamples.mapValues { (_, time) ->
                Math.addExact(part.startUs, Math.subtractExact(time, first))
            })
        } finally {
            extractor.release()
        }
    }
}
