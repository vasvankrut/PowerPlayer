package com.powerplayer.player

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class VisualizerEffect(private val audioSessionId: Int) {

    private var visualizer: Visualizer? = null
    private var runningMax = 1f
    private val smoothed = FloatArray(BAR_COUNT)

    private val _bars = MutableStateFlow<List<Float>>(emptyList())
    val bars: StateFlow<List<Float>> = _bars.asStateFlow()

    fun attach() {
        if (audioSessionId == 0) return
        val v = try {
            Visualizer(audioSessionId)
        } catch (_: Exception) {
            return
        }
        visualizer = v
        try {
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1])
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft != null) updateBars(fft)
                    }
                },
                50,
                false,
                true
            )
            v.enabled = true
        } catch (_: Exception) {
            // визуализация опциональна — при ошибке просто не показываем волну
        }
    }

    fun release() {
        visualizer?.release()
        visualizer = null
    }

    private fun updateBars(fft: ByteArray) {
        val bands = fft.size / 2
        val perBar = maxOf(1, bands / BAR_COUNT)
        val raw = FloatArray(BAR_COUNT)

        for (b in 0 until BAR_COUNT) {
            var sum = 0f
            for (i in 0 until perBar) {
                val idx = b * perBar + i
                if (idx * 2 + 2 < fft.size) {
                    val re = readShortLE(fft, idx * 2)
                    val im = readShortLE(fft, idx * 2 + 2)
                    sum += sqrt(re * re + im * im)
                }
            }
            raw[b] = sum / perBar
        }

        val frameMax = raw.maxOrNull() ?: 0f
        runningMax = runningMax * 0.92f + frameMax * 0.08f
        if (runningMax < 1f) runningMax = 1f

        val result = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val norm = (raw[b] / runningMax).coerceIn(0f, 1f)
            val logV = (Math.log10(1.0 + 9.0 * norm)).toFloat()
            smoothed[b] += (logV - smoothed[b]) * 0.35f
            result[b] = smoothed[b].coerceIn(0f, 1f)
        }
        _bars.value = result.toList()
    }

    private fun readShortLE(arr: ByteArray, offset: Int): Float {
        val lo = arr[offset].toInt() and 0xff
        val hi = arr[offset + 1].toInt() shl 8
        return (hi or lo).toFloat()
    }

    private companion object {
        const val BAR_COUNT = 48
    }
}
