package com.powerplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.powerplayer.viewmodel.EnergySample
import kotlin.math.abs

/**
 * Waveseek в стиле Poweramp — НЕ статичный: полоса волны едет влево под неподвижной
 * линией playhead (в Poweramp это дефолтный режим; «Static Waveseek» с зумом-аутом всей
 * песни — отдельный режим, и именно он выглядит статичным).
 *
 * Как это работает:
 *  - окно времени длиной WINDOW_MS едет вправо по мере воспроизведения, поэтому бары
 *    физически уезжают влево за линией;
 *  - линия playhead стоит РОВНО ПОСЕРЕДИНЕ (PLAYHEAD_FRAC = 0.5) и не двигается;
 *  - слева от линии — сыгранное (белое), справа — непроигранное (серое), граница цвета
 *    попиксельно проходит по линии;
 *  - высоты баров — пик амплитуды из headless-анализа MediaCodec (analyzeTrack), то есть
 *    это реальная форма трека, а не шум;
 *  - сам столбец под линией никогда не «прыгает» — двигается только сам таймлайн.
 *
 * Про smoothness: позиция приходит из поллера раз в 250мс. Раньше она сглаживалась
 * tween'ом, который перезапускался на каждом обновлении — скорость рвалась, отсюда
 * «дёрганье». Теперь лента живёт по собственным покадровым часам (withFrameNanos),
 * а к реальной позиции мягко подтягивается с маленьким усилением — движение
 * непрерывное, но без вечного отставания.
 */
private const val BAR_WIDTH_DP = 3f
private const val BAR_GAP_DP = 1.5f
private const val WINDOW_MS = 8_000f

/** Неподвижная линия playhead ровно посередине полосы. */
private const val PLAYHEAD_FRAC = 0.5f

/**
 * Порядок синхронизации ленты с реальной позицией. Позиция, которую отдаёт AudioTrack,
 * меняется СТУПЕНЯМИ по ~260мс (буфер ~46КБ), а поллер читает её раз в 250мс. Если просто
 * подтягивать ленту к этой лестнице с усилением, лента после каждого скачка догоняет
 * его рывком — это и есть «резкое движение». Поэтому:
 *  - DEAD_MS — «мёртвая зона»: рассогласование меньше этого вообще не трогаем, ступеньки
 *    остаются внутри зоны и лента едет абсолютно ровно;
 *  - SYNC_RATE_MS_PER_S — когда рассогласование всё-таки вылезло (буферизация, уход часов),
 *    лента закрывает его с небольшой постоянной скоростью, а не рывком;
 *  - SNAP_MS — настоящая перемотка, там мгновенно.
 * ВАЖНО: не поднимать усиление подстройки (вариант 0.10): чем оно больше, тем сильнее
 * лента дёргается вслед за лестницей позиции.
 */
private const val DEAD_MS = 120f
private const val SYNC_RATE_MS_PER_S = 40f
private const val SNAP_MS = 800f

/** Амплитуда в произвольный момент времени с ЛИНЕЙНОЙ интерполяцией по соседним бакетам.
 *  Важно: не максимум по окну (тогда при прокрутке высота бара менялась ступеньками) —
 *  непрерывная функция даёт непрерывное движение. */
private fun sampleAt(samples: List<EnergySample>, ms: Float): Float {
    if (samples.isEmpty()) return 0f
    val lastIdx = samples.size - 1
    if (ms <= samples[0].positionMs) return samples[0].value
    if (ms >= samples[lastIdx].positionMs) return samples[lastIdx].value
    var lo = 0
    var hi = lastIdx
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (samples[mid].positionMs <= ms) lo = mid else hi = mid
    }
    val a = samples[lo]
    val b = samples[hi]
    val span = (b.positionMs - a.positionMs).toFloat()
    if (span <= 0f) return b.value
    val t = (ms - a.positionMs).toFloat() / span
    return a.value + (b.value - a.value) * t
}

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
    positionStampNs: Long,
    isPlaying: Boolean,
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
    // Позиция ленты. Её НЕЛЬЗЯ читать в теле композета: иначе каждый кадр вызывает
    // рекомпозицию всего WaveVisualizer. Читаем только в фазе отрисовки и в жестах.
    val smoothState = remember { mutableFloatStateOf(0f) }

    val updDurationMs by rememberUpdatedState(durationMs)
    val updSeekStart by rememberUpdatedState(onSeekStart)
    val updSeekPreview by rememberUpdatedState(onSeekPreview)
    val updSeekCommit by rememberUpdatedState(onSeekCommit)
    val updPosMs by rememberUpdatedState(progressFraction.coerceIn(0f, 1f) * durationMs.toFloat())
    val updPosStampNs by rememberUpdatedState(positionStampNs)
    val updPlaying by rememberUpdatedState(isPlaying)
    val updDragging by rememberUpdatedState(isDragging)
    val updDragFraction by rememberUpdatedState(dragFraction)
    val updDurationFloat by rememberUpdatedState(durationMs.toFloat())

    // Часы ленты. Позиция отдаётся лестницей, поэтому:
    //  1) продлеваем её вперёд на время с последнего опроса — уходит систематическое
    //     отставание в 250мс, и она становится почти прямой;
    //  2) держим мёртвую зону — ступеньки лестницы остаются внутри неё и не дёргают ленту;
    //  3) вне зоны догоняем постоянной небольшой скоростью, перемотку — мгновенно.
    LaunchedEffect(Unit) {
        var lastNs = 0L
        var started = false
        while (true) {
            withFrameNanos {
                val nowNs = System.nanoTime()
                val duration = updDurationFloat
                if (!started) {
                    started = true
                    lastNs = nowNs
                    smoothState.floatValue = updPosMs
                } else {
                    val dt = ((nowNs - lastNs) / 1_000_000f).coerceIn(0f, 200f)
                    lastNs = nowNs
                    if (updDragging) {
                        smoothState.floatValue = updDragFraction * duration
                    } else {
                        val leadMs = if (updPlaying) {
                            ((nowNs - updPosStampNs) / 1_000_000f).coerceIn(0f, 1_500f)
                        } else {
                            0f
                        }
                        val refMs = (updPosMs + leadMs).coerceIn(0f, maxOf(duration, 0f))
                        val next = if (updPlaying) smoothState.floatValue + dt else smoothState.floatValue
                        val err = refMs - next
                        smoothState.floatValue = when {
                            abs(err) > SNAP_MS -> refMs
                            abs(err) > DEAD_MS -> {
                                val step = minOf(
                                    SYNC_RATE_MS_PER_S * dt / 1000f,
                                    abs(err) - DEAD_MS
                                )
                                (next + if (err > 0f) step else -step)
                                    .coerceIn(0f, maxOf(duration, 0f))
                            }
                            else -> next.coerceIn(0f, maxOf(duration, 0f))
                        }
                    }
                }
            }
        }
    }

    // Позиция окна для жестов — читается только из самих жестов, не из композиции.
    val currentFraction: () -> Float = {
        val d = durationMs
        if (d <= 0L) 0f else (smoothState.floatValue / d.toFloat()).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val f = fractionAtX(
                        pos.x, size.width.toFloat(), currentFraction(), updDurationMs
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
                        val f = currentFraction()
                        isDragging = true
                        dragBaseX = pos.x
                        dragBaseFraction = f
                        dragFraction = f
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
        val durMs = durationMs.toFloat()
        val displayFraction = if (durationMs <= 0L) 0f
        else (smoothState.floatValue / durMs).coerceIn(0f, 1f)
        val startMs = windowStartMs(displayFraction, durationMs)
        val msPerBar = step / pxPerMs

        val pastColor = Color.White.copy(alpha = 0.95f)
        val futureColor = Color.White.copy(alpha = 0.30f)
        val outsideColor = Color.White.copy(alpha = 0.16f)

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

            // Три непрерывные выборки (центр и края бара), максимум из них. Все они —
            // непрерывные функции времени, поэтому высота бара меняется плавно, а не
            // ступеньками, как было при max() по скользящему окну.
            val msCenter = (msStart + msEnd) * 0.5f
            val edge = msPerBar * 0.5f
            val peak = maxOf(
                sampleAt(samples, msCenter),
                sampleAt(samples, msCenter - edge),
                sampleAt(samples, msCenter + edge)
            ).coerceIn(0f, 1f)
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