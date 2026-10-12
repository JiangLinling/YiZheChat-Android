/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

/** Uses the authenticated manifest's key-frame index; seeking skips directly to an independently verified moof. */
internal class ContinuousVideoSeekMap(private val layout: ContinuousVideoLayout) : SeekMap {
    override fun isSeekable(): Boolean = true
    override fun getDurationUs(): Long = layout.durationUs
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val index = layout.partAtTime(timeUs)
        val first = SeekPoint(layout.parts[index].startUs, layout.starts[index])
        return SeekMap.SeekPoints(first)
    }
}

internal class ContinuousVideoExtractor(
    private val layout: ContinuousVideoLayout,
    private val timing: (Int) -> ContinuousFragmentTiming,
) : Extractor {
    private val delegate = FragmentedMp4Extractor(DefaultSubtitleParserFactory())
    private var input: ExtractorInput? = null
    private var pendingSeek: Pair<Long, Long>? = null
    private val trackOffsets = mutableMapOf<Pair<Int, Int>, Long>()
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                return object : TrackOutput by track {
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        val position = requireNotNull(input).position - 1
                        val index = layout.partAt(position)
                        val shift = trackOffsets.getOrPut(index to id) {
                            Math.subtractExact(requireNotNull(timing(index).firstSampleUsByTrack[id]), timeUs)
                        }
                        track.sampleMetadata(Math.addExact(timeUs, shift), flags, size, offset, cryptoData)
                    }
                }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(ContinuousVideoSeekMap(layout))
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        this.input = input
        pendingSeek?.let { (position, _) ->
            // Decode from the fragment's key frame; Media3's sample queues seek on our global timestamps.
            // This also supports independent fragments without tfdt, whose decode time otherwise carries over.
            delegate.seek(position, 0)
            pendingSeek = null
        }
        return delegate.read(input, seekPosition)
    }
    override fun seek(position: Long, timeUs: Long) {
        trackOffsets.clear()
        pendingSeek = position to timeUs
    }
    override fun release() = delegate.release()
}
