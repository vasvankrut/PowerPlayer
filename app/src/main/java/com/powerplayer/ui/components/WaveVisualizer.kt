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

/**
 * «Бегущая лента» (сейсмограф): экран разбит на неподвижную сетку слотов на всю ширину.
 *  - i < currentIndex: ярко-белая, статичная по высоте история уже сыгранных секунд.
 *  - i == currentIndex: живой пульс (линия прогресса едет слева направо).
 *  - i > currentIndex: плоские серые «будущие» слоты — тишина, которая съедается ползунком.
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
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || durationMs <= 0L) {
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, height / 2f),
                end = Offset(width, height / 2f),
                strokeWidth = 2f
            )
            return@Canvas
        }

        // Шаг 1 — фиксированная сетка на всю ширину экрана.
        val barWidth = 3.5.dp.toPx()
        val gap = 2.dp.toPx()
        val barStep = barWidth + gap
        val count = (width / barStep).toInt().coerceIn(8, 512)
        val timePerBar = durationMs.toFloat() / count

        // Шаг 2 — текущая позиция воспроизведения на экране (едет слева направо).
        val currentIndex = (count * progressFraction).toInt().coerceIn(0, count - 1)

        // История — только сыгранные сэмплы (всё, что «в будущем», исключается).
        val currentMs = durationMs * progressFraction
        val history = samples.filter { it.positionMs <= currentMs }

        val centerY = height / 2f
        val maxAmp = height * 0.5f
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        val historyColor = Color.White.copy(alpha = 0.92f)
        val playedColor = Color.White
        val unplayedColor = Color(0xFF9E9E9E).copy(alpha = 0.38f)

        fun peakInWindow(loMs: Float, hiMs: Float): Float {
            if (history.isEmpty()) return 0f
            var lo = 0
            var hi = history.size - 1
            val a = (loMs * 1.002f).toLong()
            val b = (hiMs * 1.002f).toLong()
            while (lo < hi) {
                val mid = (lo + hi) / 2
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

        val baseline = maxAmp * 0.015f
        drawLine(
            color = unplayedColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        // Шаг 3 — высота и цвет по месту слота относительно currentIndex.
        for (i in 0 until count) {
            val lo = i * timePerBar
            val hi = (i + 1) * timePerBar
            val x = i * barStep

            val isFuture = i > currentIndex
            val isCurrent = i == currentIndex

            val amp = when {
                isFuture -> 0f
                isCurrent -> liveEnergy.coerceIn(0f, 1f)
                else -> peakInWindow(lo, hi)
            } * maxAmp

            val barColor = when {
                isCurrent -> playedColor
                isFuture -> unplayedColor
                else -> historyColor
            }
            val h = (amp + baseline).coerceAtLeast(if (isFuture) 1.5f else 2.5f)

            if (isCurrent) {
                drawRoundRect(
                    color = playedColor.copy(alpha = 0.25f),
                    topLeft = Offset(x - barWidth, centerY - h * 1.7f),
                    size = Size(barWidth * 3f, h * 3.4f),
                    cornerRadius = CornerRadius(barWidth * 1.5f, barWidth * 1.5f)
                )
            }

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - h),
                size = Size(barWidth, h * 2f),
                cornerRadius = cornerRadius
            )
        }
    }
}
