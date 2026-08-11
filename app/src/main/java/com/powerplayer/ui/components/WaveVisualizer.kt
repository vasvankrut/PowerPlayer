package com.powerplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.powerplayer.viewmodel.EnergySample

@Composable
fun WaveVisualizer(
    samples: List<EnergySample>,
    durationMs: Long,
    progressFraction: Float,
    liveEnergy: Float,
    onSeekStart: () -> Unit,
    onSeekPreview: (Float) -> Unit,
    onSeekCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var lastFraction by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    lastFraction = (pos.x / size.width).coerceIn(0f, 1f)
                    onSeekStart()
                    onSeekCommit(lastFraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onSeekStart() },
                    onDrag = { change, _ ->
                        change.consume()
                        lastFraction = (change.position.x / size.width).coerceIn(0f, 1)
                        onSeekPreview(lastFraction)
                    },
                    onDragEnd = { onSeekCommit(lastFraction) },
                    onDragCancel = { onSeekCommit(lastFraction) }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || durationMs <= 0L) {
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val barWidth = 3.5.dp.toPx()
        val gap = 2.0.dp.toPx()
        val step = barWidth + gap
        val count = (w / step).toInt().coerceAtLeast(1)

        val fraction = progressFraction.coerceIn(0f, 1f)
        val currentIndex = (count * fraction).toInt().coerceIn(0, count - 1)
        val currentMs = (durationMs * fraction).toLong()

        val history = samples.filter { it.positionMs <= currentMs }

        val timePerSlot = durationMs.toFloat() / count
        val centerY = h / 2f
        val maxAmp = h * 0.5f
        val cornerR = CornerRadius(barWidth / 2f, barWidth / 2f)
        val dotH = 1.5.dp.toPx()

        fun peakInWindow(loMs: Float, hiMs: Float): Float {
            if (history.isEmpty()) return 0f
            val a = loMs.toLong()
            val b = hiMs.toLong()
            var lo = 0
            var hi = history.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (history[mid].positionMs < a) lo = mid + 1 else hi = mid
            }
            var best = 0f
            var i = lo
            while (i < history.size && history[i].positionMs <= b) {
                if (history[i].value > best) best = history[i].value
                i++
            }
            return best
        }

        for (i in 0 until count) {
            val x = i * step

            when {
                i > currentIndex -> {
                    drawRoundRect(
                        color = Color.Gray.copy(alpha = 0.4f),
                        topLeft = Offset(x, centerY - dotH / 2f),
                        size = Size(barWidth, dotH),
                        cornerRadius = CornerRadius(dotH / 2f)
                    )
                }
                i == currentIndex -> {
                    val amp = liveEnergy.coerceIn(0f, 1f) * maxAmp
                    val barH = (amp + 2f).coerceAtLeast(3f)

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(x - barWidth, centerY - barH * 1.7f),
                        size = Size(barWidth * 3f, barH * 3.4f),
                        cornerRadius = CornerRadius(barWidth * 1.5f)
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, centerY - barH),
                        size = Size(barWidth, barH * 2f),
                        cornerRadius = cornerR
                    )
                }
                else -> {
                    val amp = peakInWindow(i * timePerSlot, (i + 1) * timePerSlot) * maxAmp
                    val barH = (amp + 2f).coerceAtLeast(3f)

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, centerY - barH),
                        size = Size(barWidth, barH * 2f),
                        cornerRadius = cornerR
                    )
                }
            }
        }
    }
}