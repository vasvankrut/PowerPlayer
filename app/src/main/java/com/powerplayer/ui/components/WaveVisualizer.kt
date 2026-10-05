package com.powerplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Полоса прогресса в стиле Poweramp: волна ВСЕГО трека на всю ширину экрана,
 * симметричная относительно центральной оси, сыгранное — белое, непроигранное — серое.
 * Тонкие бары со скруглёнными концами, равномерно распределённые от края до края.
 * Высота баров = пик амплитуды из headless-анализа MediaCodec (analyzeTrack), она НИКОГДА
 * не анимируется сама по себе: полоски не прыгают, картинка трека статична и читается.
 * Живой отклик даёт только свечение playhead, яркость которого следует за текущей громкостью.
 */
private const val BAR_WIDTH_DP = 3f
private const val BAR_GAP_DP = 1.5f

private fun fractionAtX(x: Float, width: Float): Float {
    if (width <= 0f) return 0f
    return (x / width).coerceIn(0f, 1f)
}

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
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val updSeekStart by rememberUpdatedState(onSeekStart)
    val updSeekPreview by rememberUpdatedState(onSeekPreview)
    val updSeekCommit by rememberUpdatedState(onSeekCommit)

    val target = progressFraction.coerceIn(0f, 1f)
    // Ползунок едет плавно, а не прыжками по 250мс-обновлениям поллера.
    val smoothed by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 260, easing = LinearEasing)
    )
    val playheadFraction = if (isDragging) dragFraction else smoothed

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val f = fractionAtX(pos.x, size.width.toFloat())
                    dragFraction = f
                    updSeekStart()
                    updSeekCommit(f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        isDragging = true
                        dragFraction = fractionAtX(pos.x, size.width.toFloat())
                        updSeekStart()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragFraction = fractionAtX(change.position.x, size.width.toFloat())
                        updSeekPreview(dragFraction)
                    },
                    onDragEnd = {
                        updSeekCommit(dragFraction)
                        isDragging = false
                    },
                    onDragCancel = {
                        updSeekCommit(dragFraction)
                        isDragging = false
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerY = height / 2f
        val minBar = 2.dp.toPx()
        // maxAmp >= minBar, иначе coerceIn(minBar, maxAmp) бросил бы исключение на низком холсте.
        val maxAmp = (height / 2f - 1.5.dp.toPx()).coerceAtLeast(minBar)

        // Анализ ещё идёт (или трек без данных) — тонкая линия вместо пустоты.
        if (durationMs <= 0L || samples.isEmpty()) {
            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = minBar
            )
            return@Canvas
        }

        val gap = BAR_GAP_DP.dp.toPx()
        val count = (width / (BAR_WIDTH_DP.dp.toPx() + gap)).toInt().coerceIn(1, 512)
        // Шаг считаем от ширины, чтобы сетка заканчивалась ровно у правого края без «забора».
        val step = width / count
        val barWidth = (step - gap).coerceAtLeast(1f)
        val msPerBar = durationMs.toFloat() / count
        val playheadX = (width * playheadFraction).coerceIn(0f, width)

        val pastColor = Color.White.copy(alpha = 0.95f)
        val futureColor = Color.White.copy(alpha = 0.30f)

        fun peakIn(lowMs: Float, highMs: Float): Float {
            val a = lowMs.toLong()
            val b = highMs.toLong()
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
            val peak = peakIn(i * msPerBar, (i + 1) * msPerBar).coerceIn(0f, 1f)
            val h = (peak * maxAmp).coerceIn(minBar, maxAmp)
            val top = centerY - h

            val left = x
            val right = x + barWidth
            if (right <= playheadX) {
                drawRoundRect(
                    color = pastColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, h * 2f),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            } else if (left >= playheadX) {
                drawRoundRect(
                    color = futureColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, h * 2f),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            } else {
                // Бар под playhead — разрезаем его ровно по границе цвета.
                val playedW = (playheadX - left).coerceAtLeast(0.5f)
                val restW = (right - playheadX).coerceAtLeast(0.5f)
                drawRoundRect(
                    color = pastColor,
                    topLeft = Offset(left, top),
                    size = Size(playedW, h * 2f),
                    cornerRadius = CornerRadius(
                        (playedW / 2f).coerceAtMost(h),
                        (playedW / 2f).coerceAtMost(h)
                    )
                )
                drawRoundRect(
                    color = futureColor,
                    topLeft = Offset(playheadX, top),
                    size = Size(restW, h * 2f),
                    cornerRadius = CornerRadius(
                        (restW / 2f).coerceAtMost(h),
                        (restW / 2f).coerceAtMost(h)
                    )
                )
            }
        }

        // Свечение playhead отражает живую громкость — единственное, что двигается во времени.
        val glow = liveEnergy.coerceIn(0f, 1f)
        if (glow > 0.02f) {
            val glowWidth = 7.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.05f + 0.16f * glow),
                topLeft = Offset(playheadX - glowWidth / 2f, 0f),
                size = Size(glowWidth, height),
                cornerRadius = CornerRadius(glowWidth / 2f, glowWidth / 2f)
            )
        }
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, height),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}