/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaFmp4FixturePrototype
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class SecureSegmentDataSourceTest {
    @Test
    fun `DataSource reads exact init then fragment without crossing resource boundary`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val session = preparedFakeSession(worker, finalizer, RecordingDecoderLifecycle())
            val dataSource = session.dataSourceFactory().createDataSource()
            val total = dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            val destination = ByteArray(total.toInt())

            val first = dataSource.read(destination, 0, destination.size)
            val second = dataSource.read(destination, first, destination.size - first)
            val end = dataSource.read(ByteArray(1), 0, 1)

            assertThat(first).isEqualTo(SegmentedMediaFmp4FixturePrototype.init.size)
            assertThat(second).isEqualTo(SegmentedMediaFmp4FixturePrototype.segment.size)
            assertThat(end).isEqualTo(C.RESULT_END_OF_INPUT)
            assertThat(destination).isEqualTo(
                SegmentedMediaFmp4FixturePrototype.init + SegmentedMediaFmp4FixturePrototype.segment
            )
            dataSource.close()
            assertThat(session.closeAndAwaitQuiescence().succeeded).isTrue()
        } finally {
            worker.close()
            finalizer.close()
        }
    }

    @Test
    fun `actual Media3 fragmented MP4 extractor consumes authenticated DataSource`() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val finalizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val session = preparedFakeSession(worker, finalizer, RecordingDecoderLifecycle())
            val dataSource = session.dataSourceFactory().createDataSource()
            val length = dataSource.open(DataSpec.Builder().setUri(session.uri).build())
            val input = DefaultExtractorInput(dataSource, 0L, length)
            val extractor = FragmentedMp4Extractor(DefaultSubtitleParserFactory())
            val output = CapturingOutput()

            assertThat(extractor.sniff(input)).isTrue()
            input.resetPeekPosition()
            extractor.init(output)
            val seek = PositionHolder()
            while (true) {
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> error("D1 fixture unexpectedly requested an extractor seek.")
                }
            }

            assertThat(input.position).isEqualTo(length)
            assertThat(output.firstVideoSampleFlags).isNotNull()
            assertThat(checkNotNull(output.firstVideoSampleFlags) and C.BUFFER_FLAG_KEY_FRAME).isNotEqualTo(0)
            dataSource.close()
            assertThat(session.closeAndAwaitQuiescence().succeeded).isTrue()
        } finally {
            worker.close()
            finalizer.close()
        }
    }

    private class CapturingOutput : ExtractorOutput {
        private val tracks = linkedMapOf<Int, CapturingTrack>()
        val firstVideoSampleFlags: Int?
            get() = tracks.values.singleOrNull { it.type == C.TRACK_TYPE_VIDEO }?.firstSampleFlags

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CapturingTrack(type) }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class CapturingTrack(val type: Int) : TrackOutput {
        private val scratch = ByteArray(8 * 1024)
        var firstSampleFlags: Int? = null

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            input.read(scratch, 0, minOf(length, scratch.size))

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (firstSampleFlags == null) firstSampleFlags = flags
        }
    }
}
