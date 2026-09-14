package com.powerplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.powerplayer.viewmodel.EnergySample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Собственный конвейер проигрывания: MediaExtractor -> MediaCodec -> AudioTrack.
 * PCM-сэмплы, которые мы сами отдаём в AudioTrack, используются для «бегущей ленты»:
 * плеер эмитит значение громкости/баса в реальном времени, а UI рисует её как
 * сейсмограф-историю, которая появляется на линии прогресса и уплывает влево.
 * Не требует ни одного разрешения.
 */
class PlayerController(private val context: Context) {

    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy.asStateFlow()
    private val _energyPosMs = MutableStateFlow(0L)
    val energyPosMs: StateFlow<Long> = _energyPosMs.asStateFlow()
    private var energyPeak = 1e-4f
    private var energySmooth = 0f

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
        energyPeak = 1e-4f
        energySmooth = 0f
        _energy.value = 0f
        _energyPosMs.value = 0L
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
                    appendPcm(pcm, info.presentationTimeUs)
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
            energyPeak = 1e-4f
            energySmooth = 0f
        } catch (_: Exception) {
        }
    }

    private fun appendPcm(pcm: ShortArray, presentationTimeUs: Long) {
        val ch = channelCount
        var i = 0
        var sumSq = 0.0
        var count = 0
        while (i < pcm.size) {
            val mono = if (ch >= 2 && i + 1 < pcm.size) {
                (pcm[i].toFloat() + pcm[i + 1].toFloat()) / 2f / 32768f
            } else {
                pcm[i].toFloat() / 32768f
            }
            sumSq += mono * mono
            count++
            i += ch
        }
        if (count == 0) return
        var rms = sqrt(sumSq / count).toFloat()
        energyPeak = maxOf(energyPeak * 0.995f, rms, 1e-4f)
        rms = (rms / energyPeak).coerceIn(0f, 1f)
        energySmooth += (rms - energySmooth) * 0.55f
        _energy.value = sqrt(energySmooth)
        _energyPosMs.value = if (presentationTimeUs > 0) presentationTimeUs / 1000L else currentPosition()
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

    /**
     * Полный пред-анализ трека: декодирует файл headless (MediaCodec без AudioTrack)
     * и возвращает амплитудную волну всей песни. Нужен, чтобы визуализатор Poweramp-style
     * показывал всё будущее трека, а не только уже сыгранную часть.
     */
    companion object {
        fun analyzeTrack(context: Context, uri: Uri, bucketMs: Long = 80L): List<EnergySample> {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                var trackIdx = -1
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("audio/")) {
                        trackIdx = i
                        break
                    }
                }
                if (trackIdx < 0) return emptyList()

                val fmt = ex.getTrackFormat(trackIdx)
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    fmt.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                ex.selectTrack(trackIdx)

                val bucketCount = if (durUs > 0) {
                    ((durUs / 1000L + bucketMs - 1) / bucketMs).toInt()
                } else 4000
                val capped = bucketCount.coerceIn(1, 8192)

                val sums = FloatArray(capped)
                val counts = IntArray(capped)

                val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(fmt, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone && !Thread.interrupted()) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(5000)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val size = ex.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 5000)
                        if (outIdx >= 0) {
                            if (info.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                val pcm = ShortArray(info.size / 2)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                                if (pcm.isNotEmpty()) {
                                    var sumSq = 0.0
                                    for (s in pcm) {
                                        val v = s / 32768f
                                        sumSq += v * v
                                    }
                                    val rms = sqrt(sumSq / pcm.size).toFloat()
                                    val ms = if (info.presentationTimeUs > 0) {
                                        info.presentationTimeUs / 1000L
                                    } else 0L
                                    val bi = ((ms / bucketMs).toInt()).coerceIn(0, capped - 1)
                                    sums[bi] += rms * rms
                                    counts[bi]++
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                } finally {
                    try {
                        codec.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        codec.release()
                    } catch (_: Exception) {
                    }
                }

                var peak = 1e-4f
                for (i in 0 until capped) {
                    if (counts[i] > 0) peak = maxOf(peak, sqrt(sums[i] / counts[i]).toFloat())
                }

                val result = ArrayList<EnergySample>(capped)
                for (i in 0 until capped) {
                    if (counts[i] > 0) {
                        val rms = sqrt(sums[i] / counts[i]).toFloat()
                        val norm = sqrt((rms / peak).coerceIn(0f, 1f))
                        result.add(EnergySample(i * bucketMs, norm))
                    }
                }
                return result
            } finally {
                try {
                    ex.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
