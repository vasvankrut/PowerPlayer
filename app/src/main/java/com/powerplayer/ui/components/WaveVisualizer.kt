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
 * «Бегущая лента» (сейсмограф/кардиограмма) в стиле Poweramp.
 *
 * Экран разбит на НЕПОДВИЖНУЮ сетку слотов на всю ширину; ползунок едет слева направо:
 *  - i < currentIndex:  ярко-белая статичная история — пик амплитуды в окне времени слота;
 *  - i == currentIndex: живой пульс от liveEnergy + мягкое свечение;
 *  - i > currentIndex:  плоские серые точки-заглушки 1.5dp — строгая тишина до правого края.
 *
 * Исправленные баги:
 *  - убран коэффициент 1.002f в peakInWindow, который растягивал временную ось и сжимал волну
 *    к левому углу (окна слотов теперь точно совпадают с сеткой);
 *  - будущие слоты получают строго 1.5dp без прибавки baseline — серый «забор» справа не прыгает.
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

        // Шаг 1 — фиксированная сетка слотов на ВСЮ ширину экрана.
        val barWidth = 3.5.dp.toPx()
        val gap = 2.0.dp.toPx()
        val barStep = barWidth + gap
        val count = (width / barStep).toInt().coerceIn(1, 512)
        val timePerBar = durationMs.toFloat() / count

        // Шаг 2 — позиция ползунка: плавно едет слева (0) направо (count - 1).
        val fraction = progressFraction.coerceIn(0f, 1f)
        val currentIndex = (count * fraction).toInt().coerceIn(0, count - 1)

        // Фильтр будущего: в историю попадают ТОЛЬКО сыгранные сэмплы.
        val currentMs = (durationMs * fraction).toLong()
        val history = samples.filter { it.positionMs <= currentMs }

        val centerY = height / 2f
        val maxAmp = height * 0.5f
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        val historyColor = Color.White
        val currentColor = Color.White
        val unplayedColor = Color.Gray.copy(alpha = 0.4f)
        val dotHeight = 1.5.dp.toPx()

        // Пик амплитуды в окне [loMs, hiMs] через бинарный поиск по отсортированным history.
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
            val x = i * barStep
            val isFuture = i > currentIndex
            val isCurrent = i == currentIndex

            // Высота по месту слота относительно ползунка.
            val amp = when {
                isFuture -> 0f
                isCurrent -> liveEnergy.coerceIn(0f, 1f)
                else -> peakInWindow(i * timePerBar, (i + 1) * timePerBar)
            } * maxAmp

            val barColor = when {
                isCurrent -> currentColor
                isFuture -> unplayedColor
                else -> historyColor
            }

            if (isFuture) {
                // Будущее: плоская точка-заглушка ровно 1.5dp — тишина до правого края.
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, centerY - dotHeight / 2f),
                    size = Size(barWidth, dotHeight),
                    cornerRadius = CornerRadius(dotHeight / 2f, dotHeight / 2f)
                )
                continue
            }

            val h = (amp + 2f).coerceAtLeast(3f)

            // Свечение текущего слота (3x ширина, полупрозрачное).
            if (isCurrent) {
                drawRoundRect(
                    color = currentColor.copy(alpha = 0.25f),
                    topLeft = Offset(x - barWidth, centerY - h * 1.7f),
                    size = Size(barWidth * 3f, h * 3.4f),
                    cornerRadius = CornerRadius(barWidth * 1.5f, barWidth * 1.5f)
                )
            }

            // Скруглённая полоска — от центра вверх и вниз.
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - h),
                size = Size(barWidth, h * 2f),
                cornerRadius = cornerRadius
            )
        }
    }
}
