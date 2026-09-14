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
import androidx.compose.ui.graphics.drawscope.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.powerplayer.viewmodel.EnergySample

/**
 * Poweramp-style: на экране видна ВСЯ волна трека — прошлое ярко-белое,
 * будущее приглушённое (видно, что будет дальше). Ползунок-игла плавно
 * плывёт слева направо по неподвижной шкале слотов.
 */
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
                        lastFraction = (change.position.x / size.width).coerceIn(0f, 1f)
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

        // Неподвижная сетка слотов на всю ширину экрана.
        val barWidth = 3.5.dp.toPx()
        val gap = 2.0.dp.toPx()
        val step = barWidth + gap
        val count = (w / step).toInt().coerceAtLeast(1)

        // Плавная позиция ползунка-иглы (может быть дробной).
        val fraction = progressFraction.coerceIn(0f, 1f)
        val floatIndex = count * fraction
        val currentIndex = floatIndex.toInt().coerceIn(0, count - 1)

        val timePerSlot = durationMs.toFloat() / count
        val centerY = h / 2f
        val maxAmp = h * 0.42f
        val cornerR = CornerRadius(barWidth / 2f, barWidth / 2f)

        fun peakInWindow(loMs: Float, hiMs: Float): Float {
            if (samples.isEmpty()) return 0f
            val a = loMs.toLong()
            val b = hiMs.toLong()
            var lo = 0
            var hi = samples.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (samples[mid].positionMs < a) lo = mid + 1 else hi = mid
            }
            var best = 0f
            var i = lo
            while (i < samples.size && samples[i].positionMs <= b) {
                if (samples[i].value > best) best = samples[i].value
                i++
            }
            return best
        }

        for (i in 0 until count) {
            val x = i * step
            val isFuture = i > currentIndex
            val isCurrent = i == currentIndex

            // Вся волна видна: высота слота — реальный пик амплитуды из анализа трека.
            val waveAmp = peakInWindow(i * timePerSlot, (i + 1) * timePerSlot) * maxAmp
            val amp = if (isCurrent) maxOf(waveAmp, liveEnergy * maxAmp) else waveAmp
            val barH = (amp + 1.5f).coerceAtLeast(2.5f)

            val color = when {
                isCurrent -> Color.White
                isFuture -> Color.White.copy(alpha = 0.30f)
                else -> Color.White
            }

            if (isCurrent) {
                // Свечение вокруг живой точки ползунка.
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(x - barWidth, centerY - barH * 1.7f),
                    size = Size(barWidth * 3f, barH * 3.4f),
                    cornerRadius = CornerRadius(barWidth * 1.5f, barWidth * 1.5f)
                )
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - barH),
                size = Size(barWidth, barH * 2f),
                cornerRadius = cornerR
            )
        }

        // Гладко плывущая игла-ползунок (линия воспроизведения).
        val needleX = floatIndex * step
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(needleX, centerY - maxAmp),
            end = Offset(needleX, centerY + maxAmp),
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(needleX, centerY - maxAmp),
            end = Offset(needleX, centerY + maxAmp),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
    }
}