package com.powerplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

private const val WINDOW_BACK_MS = 6_000L
private const val WINDOW_FORWARD_MS = 500L

private fun fractionAtX(x: Float, width: Float, currentFraction: Float, durationMs: Long): Float {
    if (width <= 0f || durationMs <= 0L) return 0f
    val windowDur = (WINDOW_BACK_MS + WINDOW_FORWARD_MS).toFloat()
    val playheadX = width * (WINDOW_BACK_MS.toFloat() / windowDur)
    val targetMs = durationMs.toFloat() * currentFraction + (x - playheadX) * windowDur / width
    return (targetMs / durationMs).coerceIn(0f, 1f)
}

private fun fractionFromDrag(startFraction: Float, startX: Float, x: Float, width: Float, durationMs: Long): Float {
    if (width <= 0f || durationMs <= 0L) return startFraction
    val windowDur = (WINDOW_BACK_MS + WINDOW_FORWARD_MS).toFloat()
    val targetMs = durationMs.toFloat() * startFraction + (x - startX) * windowDur / width
    return (targetMs / durationMs).coerceIn(0f, 1f)
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
    var lastFraction by remember { mutableFloatStateOf(0f) }
    var dragStartFraction by remember { mutableFloatStateOf(0f) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    val currentProgress by rememberUpdatedState(progressFraction)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val updSeekStart by rememberUpdatedState(onSeekStart)
    val updSeekPreview by rememberUpdatedState(onSeekPreview)
    val updSeekCommit by rememberUpdatedState(onSeekCommit)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    lastFraction = fractionAtX(pos.x, size.width, currentProgress, currentDurationMs)
                    updSeekStart()
                    updSeekCommit(lastFraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragStartX = pos.x
                        dragStartFraction = currentProgress
                        updSeekStart()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        lastFraction = fractionFromDrag(
                            dragStartFraction, dragStartX, change.position.x, size.width, currentDurationMs
                        )
                        updSeekPreview(lastFraction)
                    },
                    onDragEnd = { updSeekCommit(lastFraction) },
                    onDragCancel = { updSeekCommit(lastFraction) }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        if (durationMs <= 0L) {
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, height / 2f),
                end = Offset(width, height / 2f),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val barWidth = 3.5.dp.toPx()
        val gap = 2.0.dp.toPx()
        val barStep = barWidth + gap
        val count = (width / barStep).toInt().coerceIn(1, 512)
        val maxAmp = height * 0.5f
        val centerY = height / 2f
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val dotHeight = 1.5.dp.toPx()

        val windowDur = (WINDOW_BACK_MS + WINDOW_FORWARD_MS).toFloat()
        val playheadX = width * (WINDOW_BACK_MS.toFloat() / windowDur)
        val pxPerMs = width / windowDur
        val currentMs = durationMs.toFloat() * progressFraction.coerceIn(0f, 1f)
        val playheadSlot = (playheadX / barStep).toInt().coerceIn(0, count - 1)

        fun peakIn(lowMs: Float, highMs: Float): Float {
            if (samples.isEmpty()) return 0f
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

        val pastColor = Color.White.copy(alpha = 0.9f)
        val futureColor = Color.White.copy(alpha = 0.28f)
        val outsideColor = Color.Gray.copy(alpha = 0.35f)

        for (i in 0 until count) {
            val x = i * barStep
            val msStart = currentMs + (x - playheadX) / pxPerMs
            val msEnd = currentMs + (x + barStep - playheadX) / pxPerMs

            if (msEnd <= 0f || msStart >= durationMs.toFloat()) {
                drawRoundRect(
                    color = outsideColor,
                    topLeft = Offset(x, centerY - dotHeight / 2f),
                    size = Size(barWidth, dotHeight),
                    cornerRadius = CornerRadius(dotHeight / 2f, dotHeight / 2f)
                )
                continue
            }

            val peak = peakIn(msStart, msEnd)

            if (i == playheadSlot) {
                val factor = maxOf(liveEnergy.coerceIn(0f, 1f), peak * 0.6f, 0.3f)
                val h = factor * maxAmp
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(x - barWidth, centerY - h * 1.7f),
                    size = Size(barWidth * 3f, h * 3.4f),
                    cornerRadius = CornerRadius(barWidth * 1.5f, barWidth * 1.5f)
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, centerY - h),
                    size = Size(barWidth, h * 2f),
                    cornerRadius = cornerRadius
                )
                continue
            }

            val isPast = (msStart + msEnd) * 0.5f <= currentMs
            val h = if (isPast) {
                (peak * maxAmp).coerceAtLeast(2f)
            } else {
                (peak * maxAmp).coerceAtLeast(3f).coerceAtMost(maxAmp * 0.35f)
            }
            drawRoundRect(
                color = if (isPast) pastColor else futureColor,
                topLeft = Offset(x, centerY - h),
                size = Size(barWidth, h * 2f),
                cornerRadius = cornerRadius
            )
        }

        drawLine(
            color = Color.White,
            start = Offset(playheadX, centerY - maxAmp * 0.9f),
            end = Offset(playheadX, centerY + maxAmp * 0.9f),
            strokeWidth = 1.dp.toPx()
        )
    }
}