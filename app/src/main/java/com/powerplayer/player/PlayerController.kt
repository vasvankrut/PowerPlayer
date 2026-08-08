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
import kotlin.math.abs

/**
 * Собственный конвейер проигрывания: MediaExtractor -> MediaCodec -> AudioTrack.
 * PCM-сэмплы, которые мы сами отдаём в AudioTrack, используются для живой визуализации.
 * Не требует ни одного разрешения.
 */
class PlayerController(private val context: Context) {

    private val _bars = MutableStateFlow<List<Float>>(emptyList())
    val bars: StateFlow<List<Float>> = _bars.asStateFlow()
    private val smooth = FloatArray(BAR_COUNT)

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
        smooth.fill(0f)
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
                    emitBars(pcm)
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

    private fun emitBars(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val perBar = maxOf(1, pcm.size / BAR_COUNT)
        val raw = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            var maxV = 0f
            var sum = 0f
            var i = b * perBar
            val end = minOf(i + perBar, pcm.size)
            while (i < end) {
                val a = abs(pcm[i].toInt()) / 32768f
                if (a > maxV) maxV = a
                sum += a
                i++
            }
            val n = maxOf(1, end - b * perBar)
            raw[b] = (maxV * 0.7f + (sum / n) * 0.3f).coerceIn(0f, 1f)
        }
        val out = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            smooth[b] += (raw[b] * 1.25f - smooth[b]) * 0.45f
            out[b] = smooth[b].coerceIn(0f, 1f)
        }
        _bars.value = out.toList()
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
        const val BAR_COUNT = 64
    }
}
