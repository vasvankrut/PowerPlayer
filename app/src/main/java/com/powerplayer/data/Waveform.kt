package com.powerplayer.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Строит амплитудную волну трека, декодируя его собственным MediaCodec.
 * Работает без системного Visualizer и без каких-либо разрешений.
 */
object Waveform {

    fun compute(context: Context, uri: Uri, bins: Int = 96): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i
                    break
                }
            }
            if (trackIdx < 0) return null

            val format = extractor.getTrackFormat(trackIdx)
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmEncoding = codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, -1)
            val bytesPerSample = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                else -> 2
            }

            val info = MediaCodec.BufferInfo()
            val offsets = ArrayList<Long>(4096)
            val samples = ArrayList<Int>(4096)
            val amps = ArrayList<Float>(4096)
            var sampleOffset = 0L
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val sampleCount = info.size / bytesPerSample
                        val peak = peakAmplitude(outBuf, sampleCount, bytesPerSample)
                        offsets.add(sampleOffset)
                        samples.add(sampleCount)
                        amps.add(peak)
                        sampleOffset += sampleCount
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            return finalize(offsets, samples, amps, bins)
        } catch (_: Exception) {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            return null
        }
    }

    private fun peakAmplitude(buf: ByteBuffer, sampleCount: Int, bytesPerSample: Int): Float {
        val b = buf.order(ByteOrder.LITTLE_ENDIAN)
        var maxAbs = 0f
        for (i in 0 until sampleCount) {
            val sample = when (bytesPerSample) {
                1 -> ((b.get().toInt() and 0xFF) - 128) / 128f
                3 -> pcm24(b) / 8388608f
                else -> b.short / 32768f
            }
            val a = abs(sample)
            if (a > maxAbs) maxAbs = a
        }
        return maxAbs.coerceIn(0f, 1f)
    }

    private fun pcm24(b: ByteBuffer): Int {
        val lo = b.get().toInt() and 0xFF
        val mid = b.get().toInt() and 0xFF
        val hi = b.get().toInt() and 0xFF
        var v = (hi shl 16) or (mid shl 8) or lo
        if (v and 0x800000 != 0) v = v or -0x1000000
        return v
    }

    private fun finalize(
        offsets: ArrayList<Long>,
        samples: ArrayList<Int>,
        amps: ArrayList<Float>,
        bins: Int
    ): FloatArray {
        val bars = FloatArray(bins)
        if (offsets.isEmpty()) return bars
        val total = offsets.last() + samples.last().toLong()
        if (total <= 0) return bars

        val binSum = FloatArray(bins)
        val binCount = IntArray(bins)
        for (i in offsets.indices) {
            val center = offsets[i] + samples[i] / 2f
            val frac = (center / total).coerceIn(0f, 1f)
            val bin = (frac * bins).toInt().coerceIn(0, bins - 1)
            binSum[bin] += amps[i]
            binCount[bin]++
        }

        for (i in 0 until bins) {
            bars[i] = if (binCount[i] > 0) (binSum[i] / binCount[i]).coerceIn(0f, 1f) else 0f
        }

        var last = -1
        for (i in 0 until bins) {
            if (bars[i] > 0f) {
                if (last >= 0 && i - last > 1) {
                    val step = (bars[i] - bars[last]) / (i - last)
                    var v = bars[last]
                    for (j in last + 1 until i) {
                        v += step
                        bars[j] = v.coerceIn(0f, 1f)
                    }
                }
                last = i
            }
        }

        var max = 0f
        for (i in 0 until bins) if (bars[i] > max) max = bars[i]
        if (max <= 0f) return bars

        val gain = 0.85f / max
        for (i in 0 until bins) bars[i] = (bars[i] * gain).coerceIn(0f, 1f)
        return bars
    }
}
