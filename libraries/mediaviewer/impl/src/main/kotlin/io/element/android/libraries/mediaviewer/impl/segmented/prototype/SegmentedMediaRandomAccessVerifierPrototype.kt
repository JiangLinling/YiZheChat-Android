/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.prototype

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.EOFException

/**
 * Opaque capability minted only after Media3 has parsed the authenticated init and media fragment together.
 * It is bound to the exact manifest resource descriptors and lease context that were checked.
 */
internal class SegmentedMediaRandomAccessProofPrototype private constructor(
    private val init: SegmentedMediaResourcePrototype,
    private val segment: SegmentedMediaResourcePrototype,
    private val context: SegmentedMediaReadContextPrototype,
) {
    internal fun matches(
        expectedInit: SegmentedMediaResourcePrototype,
        expectedSegment: SegmentedMediaResourcePrototype,
        expectedContext: SegmentedMediaReadContextPrototype,
    ): Boolean = init == expectedInit && segment == expectedSegment && context == expectedContext

    internal companion object {
        fun verify(
            validatedManifest: ValidatedSegmentedMediaManifestPrototype,
            segmentIndex: Int,
            verifiedInitBytes: ByteArray,
            verifiedSegmentBytes: ByteArray,
            context: SegmentedMediaReadContextPrototype,
            authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        ): SegmentedMediaRandomAccessResultPrototype = SegmentedMediaRandomAccessVerifierImplementation.verify(
            validatedManifest = validatedManifest,
            segmentIndex = segmentIndex,
            verifiedInitBytes = verifiedInitBytes,
            verifiedSegmentBytes = verifiedSegmentBytes,
            context = context,
            authorizationGate = authorizationGate,
            mintProof = { init, segment -> SegmentedMediaRandomAccessProofPrototype(init, segment, context) },
        )
    }
}

internal enum class SegmentedMediaRandomAccessRejectionPrototype {
    AUTHORIZATION_DENIED,
    INVALID_CONTEXT,
    INVALID_SEGMENT_INDEX,
    BYTE_LENGTH_MISMATCH,
    INVALID_INIT_BOX_LAYOUT,
    INVALID_SEGMENT_BOX_LAYOUT,
    UNSUPPORTED_TRACK_LAYOUT,
    PARSER_REJECTED,
    FIRST_VIDEO_SAMPLE_NOT_INDEPENDENT,
}

internal sealed interface SegmentedMediaRandomAccessResultPrototype {
    data class Verified(val proof: SegmentedMediaRandomAccessProofPrototype) :
        SegmentedMediaRandomAccessResultPrototype

    data class Rejected(val reason: SegmentedMediaRandomAccessRejectionPrototype) :
        SegmentedMediaRandomAccessResultPrototype
}

/**
 * Minimal WP08 verifier for the H264/AAC fMP4 subset. Inputs are bounded by the production manifest limits and are
 * exposed to Media3 as a two-resource stream, avoiding an additional concatenated whole-media allocation.
 *
 * A small outer-box preflight prevents ambiguous/truncated byte ranges. Media3 remains the format authority for
 * nested MP4 parsing, tfhd/trun default resolution, track matching, and final sample dependency flags.
 */
internal object SegmentedMediaRandomAccessVerifierPrototype {
    fun verify(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        segmentIndex: Int,
        verifiedInitBytes: ByteArray,
        verifiedSegmentBytes: ByteArray,
        context: SegmentedMediaReadContextPrototype,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
    ): SegmentedMediaRandomAccessResultPrototype = SegmentedMediaRandomAccessProofPrototype.verify(
        validatedManifest = validatedManifest,
        segmentIndex = segmentIndex,
        verifiedInitBytes = verifiedInitBytes,
        verifiedSegmentBytes = verifiedSegmentBytes,
        context = context,
        authorizationGate = authorizationGate,
    )
}

private object SegmentedMediaRandomAccessVerifierImplementation {
    fun verify(
        validatedManifest: ValidatedSegmentedMediaManifestPrototype,
        segmentIndex: Int,
        verifiedInitBytes: ByteArray,
        verifiedSegmentBytes: ByteArray,
        context: SegmentedMediaReadContextPrototype,
        authorizationGate: SegmentedMediaAuthorizationGatePrototype,
        mintProof: (SegmentedMediaResourcePrototype, SegmentedMediaResourcePrototype) ->
            SegmentedMediaRandomAccessProofPrototype,
    ): SegmentedMediaRandomAccessResultPrototype {
        if (context.ownerToken.isBlank() || context.leaseId.isBlank() || context.generation <= 0L) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.INVALID_CONTEXT)
        }
        if (!authorizationGate.isAuthorized(
                context,
                SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_ADMISSION,
            )
        ) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.AUTHORIZATION_DENIED)
        }
        val manifest = validatedManifest.manifest
        val segment = manifest.segments.getOrNull(segmentIndex)
            ?: return rejected(SegmentedMediaRandomAccessRejectionPrototype.INVALID_SEGMENT_INDEX)
        if (segment.index != segmentIndex) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.INVALID_SEGMENT_INDEX)
        }
        if (
            manifest.init.plainLength != verifiedInitBytes.size.toLong() ||
            segment.plainLength != verifiedSegmentBytes.size.toLong()
        ) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.BYTE_LENGTH_MISMATCH)
        }
        if (!hasExactTopLevelBoxes(verifiedInitBytes, listOf("ftyp", "moov"))) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.INVALID_INIT_BOX_LAYOUT)
        }
        if (!hasExactTopLevelBoxes(verifiedSegmentBytes, listOf("moof", "mdat"))) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.INVALID_SEGMENT_BOX_LAYOUT)
        }
        if (!authorizationGate.isAuthorized(
                context,
                SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_PROGRESS,
            )
        ) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.AUTHORIZATION_DENIED)
        }

        val parseResult = parse(verifiedInitBytes, verifiedSegmentBytes)
        if (parseResult is ParseResult.Rejected) return rejected(parseResult.reason)
        parseResult as ParseResult.Accepted
        if (!parseResult.firstVideoSampleIsKeyFrame) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.FIRST_VIDEO_SAMPLE_NOT_INDEPENDENT)
        }
        if (!authorizationGate.isAuthorized(
                context,
                SegmentedMediaAuthorizationPurposePrototype.VERIFICATION_DELIVERY,
            )
        ) {
            return rejected(SegmentedMediaRandomAccessRejectionPrototype.AUTHORIZATION_DENIED)
        }
        return SegmentedMediaRandomAccessResultPrototype.Verified(
            mintProof(manifest.init, segment)
        )
    }

    private fun parse(initBytes: ByteArray, segmentBytes: ByteArray): ParseResult {
        val inputLength = Math.addExact(initBytes.size.toLong(), segmentBytes.size.toLong())
        val extractor = FragmentedMp4Extractor(DefaultSubtitleParserFactory())
        val output = CapturingExtractorOutput()
        val reader = PairByteArrayDataReader(initBytes, segmentBytes)
        val input = DefaultExtractorInput(reader, 0L, inputLength)
        return try {
            if (!extractor.sniff(input)) return ParseResult.Rejected(
                SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED
            )
            input.resetPeekPosition()
            extractor.init(output)
            val seekPosition = PositionHolder()
            while (true) {
                when (extractor.read(input, seekPosition)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> return ParseResult.Rejected(
                        SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED
                    )
                }
            }
            if (input.position != inputLength || !output.isSupportedTrackLayout()) {
                ParseResult.Rejected(SegmentedMediaRandomAccessRejectionPrototype.UNSUPPORTED_TRACK_LAYOUT)
            } else if (!output.hasFirstVideoSample()) {
                ParseResult.Rejected(SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED)
            } else {
                ParseResult.Accepted(output.firstVideoSampleIsKeyFrame())
            }
        } catch (_: EOFException) {
            ParseResult.Rejected(SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED)
        } catch (_: RuntimeException) {
            ParseResult.Rejected(SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED)
        } catch (_: java.io.IOException) {
            ParseResult.Rejected(SegmentedMediaRandomAccessRejectionPrototype.PARSER_REJECTED)
        } finally {
            extractor.release()
        }
    }

    private fun hasExactTopLevelBoxes(bytes: ByteArray, expectedTypes: List<String>): Boolean {
        var position = 0L
        val observedTypes = mutableListOf<String>()
        while (position < bytes.size.toLong()) {
            val start = position
            if (bytes.size.toLong() - start < 8L) return false
            val size32 = readUnsignedInt(bytes, start.toInt())
            val type = String(bytes, start.toInt() + 4, 4, Charsets.US_ASCII)
            val headerSize: Long
            val boxSize: Long
            when (size32) {
                0L -> return false
                1L -> {
                    if (bytes.size.toLong() - start < 16L) return false
                    val largeSize = readUnsignedLongOrNull(bytes, start.toInt() + 8) ?: return false
                    headerSize = 16L
                    boxSize = largeSize
                }
                else -> {
                    headerSize = 8L
                    boxSize = size32
                }
            }
            if (boxSize < headerSize) return false
            val end = try {
                Math.addExact(start, boxSize)
            } catch (_: ArithmeticException) {
                return false
            }
            if (end > bytes.size.toLong()) return false
            observedTypes += type
            position = end
        }
        return position == bytes.size.toLong() && observedTypes == expectedTypes
    }

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun readUnsignedLongOrNull(bytes: ByteArray, offset: Int): Long? {
        if ((bytes[offset].toInt() and 0x80) != 0) return null
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL) }
        return value
    }

    private fun SegmentedMediaAuthorizationGatePrototype.isAuthorized(
        context: SegmentedMediaReadContextPrototype,
        purpose: SegmentedMediaAuthorizationPurposePrototype,
    ): Boolean = check(context, purpose) == SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED

    private fun rejected(reason: SegmentedMediaRandomAccessRejectionPrototype) =
        SegmentedMediaRandomAccessResultPrototype.Rejected(reason)

    private sealed interface ParseResult {
        data class Accepted(val firstVideoSampleIsKeyFrame: Boolean) : ParseResult
        data class Rejected(val reason: SegmentedMediaRandomAccessRejectionPrototype) : ParseResult
    }

    private class PairByteArrayDataReader(
        private val first: ByteArray,
        private val second: ByteArray,
    ) : DataReader {
        private var position = 0L
        private val totalLength = first.size.toLong() + second.size.toLong()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == totalLength) return C.RESULT_END_OF_INPUT
            val source = if (position < first.size) first else second
            val sourcePosition = if (source === first) position.toInt() else (position - first.size).toInt()
            val count = minOf(length, source.size - sourcePosition)
            source.copyInto(buffer, offset, sourcePosition, sourcePosition + count)
            position += count
            return count
        }
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        private val tracks = linkedMapOf<Int, CapturingTrackOutput>()
        private var conflictingTrackType = false

        override fun track(id: Int, type: Int): TrackOutput {
            val existing = tracks[id]
            if (existing != null) {
                if (existing.type != type) conflictingTrackType = true
                return existing
            }
            return CapturingTrackOutput(type).also { tracks[id] = it }
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit

        fun isSupportedTrackLayout(): Boolean {
            val video = tracks.values.filter { it.type == C.TRACK_TYPE_VIDEO }
            val audio = tracks.values.filter { it.type == C.TRACK_TYPE_AUDIO }
            val unknown = tracks.values.filter { it.type != C.TRACK_TYPE_VIDEO && it.type != C.TRACK_TYPE_AUDIO }
            return !conflictingTrackType && video.size == 1 && audio.size <= 1 && unknown.isEmpty() &&
                video.single().format?.sampleMimeType == "video/avc" &&
                audio.all { it.format?.sampleMimeType == "audio/mp4a-latm" }
        }

        fun firstVideoSampleIsKeyFrame(): Boolean = tracks.values
            .single { it.type == C.TRACK_TYPE_VIDEO }
            .firstSampleFlags
            ?.let { flags -> flags and C.BUFFER_FLAG_KEY_FRAME != 0 }
            ?: false

        fun hasFirstVideoSample(): Boolean = tracks.values
            .single { it.type == C.TRACK_TYPE_VIDEO }
            .firstSampleFlags != null
    }

    private class CapturingTrackOutput(val type: Int) : TrackOutput {
        var format: Format? = null
        var firstSampleFlags: Int? = null
        private val scratch = ByteArray(8 * 1024)

        override fun format(format: Format) {
            this.format = format
        }

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
