package com.powerplayer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.colorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powerplayer.ui.components.WaveVisualizer
import com.powerplayer.ui.theme.Black
import com.powerplayer.ui.theme.White
import com.powerplayer.ui.theme.WhiteDim
import com.powerplayer.ui.theme.WhiteFaint
import com.powerplayer.viewmodel.PlayerUiState
import com.powerplayer.viewmodel.PlayerViewModel

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onPickFolder: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bars by viewModel.bars.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {
        BackgroundLayer(art = state.art)

        when {
            !state.folderPicked -> FolderPickerView(onPickFolder)
            state.noTracks -> NoTracksView(onPickFolder)
            else -> PlayerLayout(state = state, bars = bars, viewModel = viewModel)
        }
    }
}

@Composable
private fun BackgroundLayer(art: Bitmap?) {
    if (art != null) {
        Image(
            bitmap = art.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(45.dp)
                .colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Black.copy(alpha = 0.6f))
        )
    }
}

@Composable
private fun PlayerLayout(
    state: PlayerUiState,
    bars: List<Float>,
    viewModel: PlayerViewModel
) {
    val track = state.tracks.getOrNull(state.currentIndex) ?: return
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxSize()) {
        // ── Верхняя половина: обложка, поверх неё название и исполнитель ──
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AlbumArt(art = state.art, modifier = Modifier.fillMaxSize())

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Black.copy(alpha = 0.9f)))
                    )
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = track.title,
                    color = White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track.artist,
                    color = WhiteDim,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── Нижняя половина: волна, поверх неё кнопки, под ней таймкоды ──
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 24.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                WaveVisualizer(
                    bars = bars,
                    progressFraction = progress,
                    onSeekStart = viewModel::onSeekStart,
                    onSeekPreview = viewModel::onSeekPreview,
                    onSeekCommit = viewModel::onSeekCommit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                        .padding(horizontal = 28.dp)
                )

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    IconButton(
                        onClick = viewModel::prev,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = null,
                            tint = White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    IconButton(
                        onClick = viewModel::togglePlay,
                        modifier = Modifier.size(84.dp)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = White,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    IconButton(
                        onClick = viewModel::next,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = null,
                            tint = White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(state.positionMs), color = White, fontSize = 14.sp)
                Text(formatTime(state.durationMs), color = WhiteDim, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AlbumArt(art: Bitmap?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f)
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(1.dp, WhiteFaint, RoundedCornerShape(16.dp))
                        .background(White.copy(alpha = 0.04f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = WhiteFaint, fontSize = 120.sp)
                }
            }
        }
    }
}

@Composable
private fun FolderPickerView(onPickFolder: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PowerPlayer",
            color = White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "выберите папку с музыкой",
            color = WhiteDim,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onPickFolder,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = White)
        ) {
            Text(
                text = "Выбрать папку",
                color = Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun NoTracksView(onPickFolder: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "В этой папке нет музыки",
            color = White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "попробуйте выбрать другую",
            color = WhiteDim,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onPickFolder,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = White)
        ) {
            Text(
                text = "Выбрать другую папку",
                color = Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
