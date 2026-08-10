package com.powerplayer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powerplayer.ui.CoverFx
import com.powerplayer.ui.components.WaveVisualizer
import com.powerplayer.ui.theme.Black
import com.powerplayer.ui.theme.White
import com.powerplayer.ui.theme.WhiteDim
import com.powerplayer.ui.theme.WhiteFaint
import com.powerplayer.viewmodel.EnergySample
import com.powerplayer.viewmodel.PlayerUiState
import com.powerplayer.viewmodel.PlayerViewModel

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onPickFolder: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {
        BackgroundLayer(art = state.art)

        when {
            state.isLoading -> LoadingView()
            !state.folderPicked -> FolderPickerView(onPickFolder)
            state.noTracks -> NoTracksView(onPickFolder)
            else -> PlayerLayout(state = state, samples = samples, viewModel = viewModel)
        }
    }
}

@Composable
private fun BackgroundLayer(art: Bitmap?) {
    if (art != null) {
        val bg by remember(art) { mutableStateOf(CoverFx.blurredBackground(art)) }
        Image(
            bitmap = bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PlayerLayout(
    state: PlayerUiState,
    samples: List<EnergySample>,
    viewModel: PlayerViewModel
) {
    val track = state.tracks.getOrNull(state.currentIndex) ?: return
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxSize()) {
        // ── Верхняя половина: обложка на всю высоту, текст поверх неё снизу слева ──
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AlbumArt(art = state.art, modifier = Modifier.fillMaxSize())

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Black.copy(alpha = 0.72f)
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Text(
                    text = track.title,
                    color = White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track.artist,
                    color = WhiteDim,
                    fontSize = 16.sp,
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
                    samples = samples,
                    durationMs = state.durationMs,
                    progressFraction = progress,
                    onSeekStart = viewModel::onSeekStart,
                    onSeekPreview = viewModel::onSeekPreview,
                    onSeekCommit = viewModel::onSeekCommit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
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
    Box(modifier) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = WhiteFaint, fontSize = 120.sp)
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = White,
            strokeWidth = 3.dp,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Загружаю музыку...",
            color = WhiteDim,
            fontSize = 15.sp
        )
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
