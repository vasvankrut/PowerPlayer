package com.powerplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Собственный конвейер проигрывания: MediaExtractor -> MediaCodec -> AudioTrack.
 * PCM-сэмплы, которые мы сами отдаём в AudioTrack, используются для живого
 * частотного спектрометра (FFT): левая часть — басы, правая — высокие частоты.
 * Не требует ни одного разрешения.
 */
class PlayerController(private val context: Context) {

    private val _bars = MutableStateFlow<List<Float>>(emptyList())
    val bars: StateFlow<List<Float>> = _bars.asStateFlow()
    private val smoothed = FloatArray(BAR_COUNT)
    private val peaks = FloatArray(BAR_COUNT) { 1f }
    private val window = FloatArray(FFT_N)
    private var windowFill = 0

    @Volatile
    private var extractor: MediaExtractor? = null
    @Volatile
    private var codec: MediaCodec? = null
    @Volatile
    private var track: AudioTrack? = null
    @Volatile
    private var decodeThread: Thread? = null
    @Volatile
    private var stopped = true
    @Volatile
    private var paused = false
    @Volatile
    private var playing = false
    @Volatile
    private var sampleRate = 44100
    @Volatile
    private var channelCount = 2
    @Volatile
    private var durationMs = 0L
    @Volatile
    private var positionMs = 0L
    @Volatile
    private var seekBaseMs = 0L
    @Volatile
    private var seekBaseFrames = 0L
    @Volatile
    private var pendingSeekMs = -1L

    var onCompletion: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    fun play(uri: Uri, onPrepared: () -> Unit) {
        stopInternal()
        stopped = false
        paused = false
        playing = false
        pendingSeekMs = -1L
        positionMs = 0L
        seekBaseMs = 0L
        seekBaseFrames = 0L
        durationMs = 0L
        smoothed.fill(0f)
        peaks.fill(1f)
        window.fill(0f)
        windowFill = 0
        _bars.value = emptyList()
        val thread = Thread {
            try {
                setup(uri)
                onPrepared()
                val at = track ?: return@Thread
                at.play()
                playing = true
                decodeLoop()
            } catch (e: Exception) {
                if (!stopped) onError?.invoke()
            } finally {
                teardown()
            }
        }
        decodeThread = thread
        thread.start()
    }

    fun pause() {
        positionMs = currentPosition()
        paused = true
        playing = false
        try {
            track?.pause()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        paused = false
        playing = true
        try {
            track?.play()
        } catch (_: Exception) {
        }
    }

    fun isPlaying(): Boolean = playing && !stopped

    fun duration(): Long = durationMs

    fun currentPosition(): Long {
        val at = track
        if (at == null || !playing) return positionMs
        val frames = at.playbackHeadPosition.toLong()
        return (seekBaseMs + (frames - seekBaseFrames) * 1000L / sampleRate).coerceIn(0L, durationMs)
    }

    fun seekTo(ms: Long) {
        pendingSeekMs = ms.coerceAtLeast(0L)
    }

    fun release() {
        stopInternal()
    }

    private fun setup(uri: Uri) {
        val ex = MediaExtractor().also { it.setDataSource(context, uri, null) }
        extractor = ex

        var trackIdx = -1
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                trackIdx = i
                break
            }
        }
        if (trackIdx < 0) throw IllegalStateException("no audio track")

        val format = ex.getTrackFormat(trackIdx)
        ex.selectTrack(trackIdx)

        sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2
        channelCount = channels
        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / 1000L
        } else 0L

        val cd = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        cd.configure(format, null, null, 0)
        cd.start()
        codec = cd

        val minBuf = maxOf(1024, AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT))
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
    }

    private fun decodeLoop() {
        val ex = extractor ?: return
        val cd = codec ?: return
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!stopped && !outputDone) {
            while (paused && !stopped) {
                Thread.sleep(40)
            }
            if (stopped) break
            maybeSeek()

            if (!inputDone) {
                val inIdx = cd.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val inBuf = cd.getInputBuffer(inIdx)!!
                    val size = ex.readSampleData(inBuf, 0)
                    if (size < 0) {
                        cd.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        cd.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            val outIdx = cd.dequeueOutputBuffer(info, 5000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val outBuf = cd.getOutputBuffer(outIdx)!!
                    val pcm = ShortArray(info.size / 2)
                    outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    appendPcm(pcm)
                    at.write(pcm, 0, pcm.size)
                }
                cd.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        if (!stopped && outputDone) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
            try {
                at.stop()
            } catch (_: Exception) {
            }
            playing = false
            positionMs = durationMs
            onCompletion?.invoke()
        }
    }

    private fun maybeSeek() {
        val target = pendingSeekMs
        if (target < 0) return
        pendingSeekMs = -1L
        val ex = extractor ?: return
        val cd = codec ?: return
        val at = track ?: return
        try {
            ex.seekTo(target * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            cd.flush()
            val wasPaused = paused
            at.pause()
            at.flush()
            if (!wasPaused) at.play()
            seekBaseMs = target.coerceIn(0L, durationMs)
            seekBaseFrames = at.playbackHeadPosition.toLong()
            positionMs = seekBaseMs
        } catch (_: Exception) {
        }
    }

    private fun appendPcm(pcm: ShortArray) {
        val ch = channelCount
        var i = 0
        while (windowFill < FFT_N && i < pcm.size) {
            val mono = if (ch >= 2 && i + 1 < pcm.size) {
                (pcm[i].toFloat() + pcm[i + 1].toFloat()) / 2f / 32768f
            } else {
                pcm[i].toFloat() / 32768f
            }
            window[windowFill++] = mono
            i += ch
        }
        if (windowFill >= FFT_N) {
            emitSpectrum()
            System.arraycopy(window, FFT_N / 2, window, 0, FFT_N / 2)
            windowFill = FFT_N / 2
        }
    }

    private fun emitSpectrum() {
        val re = FloatArray(FFT_N)
        val im = FloatArray(FFT_N)
        for (k in 0 until FFT_N) re[k] = window[k]
        fft(re, im)

        val mag = FloatArray(FFT_N / 2)
        for (k in 0 until FFT_N / 2) {
            val r = re[k] / FFT_N
            val i = im[k] / FFT_N
            mag[k] = sqrt(r * r + i * i)
        }

        val nyquist = sampleRate / 2
        val fMin = 20f
        val fMax = minOf(nyquist.toFloat(), 20000f)
        val logRatio = ln(fMax / fMin)

        val raw = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val fLo = fMin * exp(logRatio * b.toFloat() / BAR_COUNT)
            val fHi = fMin * exp(logRatio * (b + 1).toFloat() / BAR_COUNT)
            val binLo = (fLo / nyquist * FFT_N).toInt().coerceIn(1, FFT_N / 2 - 1)
            val binHi = (fHi / nyquist * FFT_N).toInt().coerceIn(binLo, FFT_N / 2 - 1)
            var sum = 0f
            for (bin in binLo..binHi) {
                sum += mag[bin]
            }
            raw[b] = sum / (binHi - binLo + 1)
        }

        val out = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val v = raw[b] * SPECTRUM_GAIN
            val decayed = peaks[b] * PEAK_DECAY
            peaks[b] = maxOf(decayed, v, 1e-4f)
            smoothed[b] += (v / peaks[b] - smoothed[b]) * SMOOTH
            out[b] = smoothed[b].coerceIn(0f, 1f)
        }
        _bars.value = out.toList()
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val idx = i + k
                    val uRe = re[idx]
                    val uIm = im[idx]
                    val o = idx + len / 2
                    val vRe = re[o] * curRe - im[o] * curIm
                    val vIm = re[o] * curIm + im[o] * curRe
                    re[idx] = uRe + vRe
                    im[idx] = uIm + vIm
                    re[o] = uRe - vRe
                    im[o] = uIm - vIm
                    val ncRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = ncRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun stopInternal() {
        stopped = true
        paused = false
        playing = false
        pendingSeekMs = -1L
        val t = decodeThread
        decodeThread = null
        if (t !== Thread.currentThread()) {
            try {
                t?.join(1500)
            } catch (_: InterruptedException) {
            }
        }
        teardown()
    }

    @Synchronized
    private fun teardown() {
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
        extractor = null
    }

    private companion object {
        const val BAR_COUNT = 96
        const val FFT_N = 2048
        const val SPECTRUM_GAIN = 4f
        const val PEAK_DECAY = 0.98f
        const val SMOOTH = 0.35f
    }
}
