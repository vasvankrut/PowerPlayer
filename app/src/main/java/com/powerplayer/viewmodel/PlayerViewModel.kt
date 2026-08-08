package com.powerplayer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.powerplayer.data.AlbumArt
import com.powerplayer.data.FolderPrefs
import com.powerplayer.data.Track
import com.powerplayer.data.TrackScanner
import com.powerplayer.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val folderPicked: Boolean = false,
    val isLoading: Boolean = false,
    val noTracks: Boolean = false,
    val art: Bitmap? = null
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val player = PlayerController(app)
    private var poller: Job? = null
    private var folderUri: Uri? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _bars = MutableStateFlow<List<Float>>(emptyList())
    val bars: StateFlow<List<Float>> = _bars.asStateFlow()

    @Volatile
    var dragging = false
        private set

    init {
        player.onCompletion = { next(auto = true) }
        player.onError = { next(auto = true) }
        viewModelScope.launch {
            player.bars.collect { _bars.value = it }
        }
        restoreFolder()
    }

    private fun restoreFolder() {
        val app: Application = getApplication()
        val saved = FolderPrefs.load(app) ?: return
        val uri = Uri.parse(saved)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri }
        if (granted) pickFolder(uri)
    }

    fun pickFolder(uri: Uri) {
        folderUri = uri
        FolderPrefs.save(getApplication(), uri.toString())
        _state.update { it.copy(isLoading = true, noTracks = false) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                TrackScanner.scanFolder(getApplication(), uri)
            }
            _state.update { it.copy(isLoading = false) }
            if (found.isEmpty()) {
                _state.update {
                    it.copy(folderPicked = true, noTracks = true, tracks = emptyList(), currentIndex = -1)
                }
            } else {
                _state.update {
                    it.copy(folderPicked = true, noTracks = false, tracks = found, currentIndex = 0)
                }
                playTrack(0)
            }
        }
    }

    fun togglePlay() {
        val s = _state.value
        if (s.currentIndex < 0) return
        if (s.isPlaying) {
            player.pause()
            _state.update { it.copy(isPlaying = false) }
        } else {
            player.resume()
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun next() = next(auto = false)

    private fun next(auto: Boolean) {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        val idx = (s.currentIndex + 1) % s.tracks.size
        if (auto && s.tracks.size == 1) {
            playTrack(idx)
            return
        }
        _state.update { it.copy(currentIndex = idx) }
        playTrack(idx)
    }

    fun prev() {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        if (s.positionMs > 3000) {
            player.seekTo(0)
            _state.update { it.copy(positionMs = 0) }
            return
        }
        val idx = if (s.currentIndex - 1 < 0) s.tracks.lastIndex else s.currentIndex - 1
        _state.update { it.copy(currentIndex = idx) }
        playTrack(idx)
    }

    fun onSeekStart() {
        dragging = true
    }

    fun onSeekPreview(fraction: Float) {
        val dur = _state.value.durationMs
        if (dur <= 0) return
        _state.update { it.copy(positionMs = (dur * fraction.coerceIn(0f, 1f)).toLong()) }
    }

    fun onSeekCommit(fraction: Float) {
        val dur = _state.value.durationMs
        dragging = false
        if (dur <= 0) return
        val target = (dur * fraction.coerceIn(0f, 1f)).toLong()
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    private fun playTrack(index: Int) {
        val s = _state.value
        val track = s.tracks.getOrNull(index) ?: return

        val uri = Uri.parse(track.uri)
        player.play(uri) {
            _state.update {
                it.copy(isPlaying = true, durationMs = player.duration().coerceAtLeast(track.durationMs))
            }
        }
        _state.update { it.copy(positionMs = 0, durationMs = track.durationMs, art = null) }

        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { AlbumArt.load(getApplication(), uri, folderUri) }
            if (_state.value.currentIndex == index) {
                _state.update { it.copy(art = bmp) }
            }
        }

        startPositionPoller()
    }

    private fun startPositionPoller() {
        poller?.cancel()
        poller = viewModelScope.launch {
            while (isActive) {
                if (!dragging) {
                    _state.update {
                        it.copy(
                            positionMs = player.currentPosition(),
                            isPlaying = player.isPlaying()
                        )
                    }
                }
                delay(250)
            }
        }
    }

    override fun onCleared() {
        poller?.cancel()
        player.release()
        super.onCleared()
    }
}
