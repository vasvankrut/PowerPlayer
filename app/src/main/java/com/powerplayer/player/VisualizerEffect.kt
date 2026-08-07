package com.powerplayer.player

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class VisualizerEffect(private val audioSessionId: Int) {

    private var visualizer: Visualizer? = null
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
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) updateWaveform(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) = Unit
                },
                40,
                true,
                false
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

    private fun updateWaveform(waveform: ByteArray) {
        val perBar = maxOf(1, waveform.size / BAR_COUNT)
        val raw = FloatArray(BAR_COUNT)

        for (b in 0 until BAR_COUNT) {
            var sum = 0f
            var maxV = 0f
            for (i in 0 until perBar) {
                val idx = b * perBar + i
                if (idx < waveform.size) {
                    val amp = abs(waveform[idx]) / 127f
                    sum += amp
                    if (amp > maxV) maxV = amp
                }
            }
            raw[b] = (maxV * 0.6f + (sum / perBar) * 0.4f).coerceIn(0f, 1f)
        }

        val result = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val norm = raw[b].coerceIn(0f, 1f)
            smoothed[b] += (norm - smoothed[b]) * 0.4f
            result[b] = smoothed[b].coerceIn(0f, 1f)
        }
        _bars.value = result.toList()
    }

    private companion object {
        const val BAR_COUNT = 64
    }
}
