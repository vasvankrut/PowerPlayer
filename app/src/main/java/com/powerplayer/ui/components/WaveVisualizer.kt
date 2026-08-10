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

@Composable
fun WaveVisualizer(
    bars: List<Float>,
    progressFraction: Float,
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
        if (bars.isEmpty()) {
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val centerY = size.height / 2f
        val maxAmplitude = size.height * 0.5f
        val gap = 1.dp.toPx()
        val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedColor = Color.White
        val unplayedColor = Color(0xFF9E9E9E).copy(alpha = 0.4f)
        val playedX = size.width * progressFraction

        for (i in bars.indices) {
            val x = i * (barWidth + gap)
            val amp = bars[i] * maxAmplitude
            val played = x + barWidth / 2f <= playedX
            drawRoundRect(
                color = if (played) playedColor else unplayedColor,
                topLeft = Offset(x, centerY - amp),
                size = Size(barWidth, amp * 2f),
                cornerRadius = cornerRadius
            )
        }
    }
}
