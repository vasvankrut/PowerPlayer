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
 * Waveseek в стиле Poweramp — НЕ статичный: полоса волны едет влево под неподвижной
 * линией playhead (в Poweramp это дефолтный режим; «Static Waveseek» с зумом-аутом всей
 * песни — отдельный режим, и именно он выглядит статичным).
 *
 * Как это работает:
 *  - окно времени [currentMs - WINDOW_BACK_MS, currentMs + WINDOW_FORWARD_MS] едет вправо
 *    по мере воспроизведения, поэтому бары физически уезжают влево за линией;
 *  - линия playhead всегда на одном месте (PLAYHEAD_FRAC от левого края) и не двигается;
 *  - слева от линии — сыгранное (белое), справа — непроигранное (серое), граница цвета
 *    попиксельно проходит по линии;
 *  - высоты баров — пик амплитуды из headless-анализа MediaCodec (analyzeTrack), то есть
 *    это реальная форма трека, а не шум;
 *  - сам столбец под линией никогда не «прыгает» — двигается только сам таймлайн.
 */
private const val BAR_WIDTH_DP = 3f
private const val BAR_GAP_DP = 1.5f
private const val WINDOW_BACK_MS = 6_000L
private const val WINDOW_FORWARD_MS = 2_000L
private const val WINDOW_MS = 8_000f // = WINDOW_BACK_MS + WINDOW_FORWARD_MS

/** Неподвижная линия playhead, доля ширины от левого края. */
private const val PLAYHEAD_FRAC = 0.25f

/** Начало окна в мс при заданной доле трека: так, чтобы линия попадала ровно в currentMs. */
private fun windowStartMs(fraction: Float, durationMs: Long): Float =
    durationMs.toFloat() * fraction - PLAYHEAD_FRAC * WINDOW_MS

private fun fractionAtX(
    x: Float,
    width: Float,
    baseFraction: Float,
    durationMs: Long
): Float {
    if (width <= 0f || durationMs <= 0L) return baseFraction
    val ms = windowStartMs(baseFraction, durationMs) + x * WINDOW_MS / width
    return (ms / durationMs.toFloat()).coerceIn(0f, 1f)
}

/** Драг: сдвиг окна задаётся смещением пальца от точки захвата — без накопления ошибки. */
private fun fractionFromDrag(
    baseFraction: Float,
    baseX: Float,
    x: Float,
    width: Float,
    durationMs: Long
): Float {
    if (width <= 0f || durationMs <= 0L) return baseFraction
    val ms = durationMs.toFloat() * baseFraction + (x - baseX) * WINDOW_MS / width
    return (ms / durationMs.toFloat()).coerceIn(0f, 1f)
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
    var dragBaseFraction by remember { mutableFloatStateOf(0f) }
    var dragBaseX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val updDurationMs by rememberUpdatedState(durationMs)
    val updSeekStart by rememberUpdatedState(onSeekStart)
    val updSeekPreview by rememberUpdatedState(onSeekPreview)
    val updSeekCommit by rememberUpdatedState(onSeekCommit)

    val target = progressFraction.coerceIn(0f, 1f)
    // Плавная лента: поллер обновляет позицию раз в 250мс, tween превращает это в
    // непрерывное скольжение волны, а не в ступеньки.
    val smoothed by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 260, easing = LinearEasing)
    )
    val displayFraction = if (isDragging) dragFraction else smoothed
    // pointerInput(Unit) не перезапускается — читать позицию окна из gesture-лямбд
    // можно только через rememberUpdatedState, иначе там останется значение
    // из самой первой композиции.
    val updDisplayFraction by rememberUpdatedState(displayFraction)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val f = fractionAtX(
                        pos.x, size.width.toFloat(), updDisplayFraction, updDurationMs
                    )
                    dragFraction = f
                    dragBaseFraction = f
                    updSeekStart()
                    updSeekCommit(f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        isDragging = true
                        dragBaseX = pos.x
                        dragBaseFraction = updDisplayFraction
                        dragFraction = updDisplayFraction
                        updSeekStart()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragFraction = fractionFromDrag(
                            dragBaseFraction, dragBaseX, change.position.x,
                            size.width.toFloat(), updDurationMs
                        )
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
        val playheadX = width * PLAYHEAD_FRAC

        // Анализ ещё идёт (или трек без данных) — тонкая линия вместо пустоты.
        if (durationMs <= 0L || samples.isEmpty()) {
            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = minBar
            )
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 1.5.dp.toPx()
            )
            return@Canvas
        }

        val gap = BAR_GAP_DP.dp.toPx()
        val count = (width / (BAR_WIDTH_DP.dp.toPx() + gap)).toInt().coerceIn(1, 512)
        // Шаг считаем от ширины, чтобы сетка заканчивалась ровно у правого края без «забора».
        val step = width / count
        val barWidth = (step - gap).coerceAtLeast(1f)

        val pxPerMs = width / WINDOW_MS
        val startMs = windowStartMs(displayFraction, durationMs)
        val durMs = durationMs.toFloat()
        val msPerBar = step / pxPerMs

        val pastColor = Color.White.copy(alpha = 0.95f)
        val futureColor = Color.White.copy(alpha = 0.30f)
        val outsideColor = Color.White.copy(alpha = 0.16f)

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
            val msStart = startMs + x / pxPerMs
            val msEnd = msStart + msPerBar

            // За пределами трека реальных данных нет — плоские едва заметные точки.
            if (msEnd <= 0f || msStart >= durMs) {
                drawRoundRect(
                    color = outsideColor,
                    topLeft = Offset(x, centerY - minBar / 2f),
                    size = Size(barWidth, minBar),
                    cornerRadius = CornerRadius(minBar / 2f, minBar / 2f)
                )
                continue
            }

            val peak = peakIn(msStart, msEnd).coerceIn(0f, 1f)
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
                // Бар под линией — разрезаем ровно по границе цвета.
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

        // Линия неподвижна; пульсирует только её свечение — по текущей громкости.
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