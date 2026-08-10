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
 * «Бегущая лента»: каждая новая высота (громкость/бас) «записывается» одной точкой
 * на линии прогресса и уплывает влево, образуя сейсмограф-историю трека.
 * Слева от ползунка — ярко-белая история, справа — серая тишина (ещё не сыгранное).
 */
@Composable
fun WaveVisualizer(
    samples: List<EnergySample>,
    durationMs: Long,
    progressFraction: Float,
    barCount: Int = 120,
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

        val count = barCount.coerceAtLeast(16)
        val timePerBar = durationMs.toFloat() / count
        val gap = 1.dp.toPx()
        val slot = (width - gap * (count - 1)) / count
        val barWidth = slot.coerceAtLeast(1.5f)
        val barStep = barWidth + gap

        val centerY = height / 2f
        val maxAmp = height * 0.5f
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        val currentMs = durationMs * progressFraction
        val currentIndex = (currentMs / timePerBar).toInt().coerceIn(0, count - 1)

        val historyColor = Color.White.copy(alpha = 0.92f)
        val playedColor = Color.White
        val unplayedColor = Color(0xFF9E9E9E).copy(alpha = 0.38f)

        fun peakInWindow(loMs: Float, hiMs: Float): Float {
            if (samples.isEmpty()) return 0f
            var lo = 0
            var hi = samples.size - 1
            val a = (loMs * 1.002f).toLong()
            val b = (hiMs * 1.002f).toLong()
            while (lo < hi) {
                val mid = (lo + hi) / 2
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

        val baseline = maxAmp * 0.015f
        drawLine(
            color = unplayedColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        for (i in 0 until count) {
            val lo = i * timePerBar
            val hi = (i + 1) * timePerBar
            val amp = peakInWindow(lo, hi) * maxAmp
            val x = i * barStep

            val isFuture = i > currentIndex
            val barColor = when {
                i == currentIndex -> playedColor
                isFuture -> unplayedColor
                else -> historyColor
            }
            val h = (amp + baseline).coerceAtLeast(2.5f)

            if (i == currentIndex) {
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
