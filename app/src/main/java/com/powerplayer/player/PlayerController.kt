package com.powerplayer.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

class PlayerController(private val context: Context) {

    private var player: MediaPlayer? = null

    val audioSessionId: Int
        get() = player?.audioSessionId ?: 0

    var onCompletion: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        return MediaPlayer().also { p ->
            player = p
            p.setOnCompletionListener { onCompletion?.invoke() }
            p.setOnErrorListener { _, _, _ -> onError?.invoke(); true }
        }
    }

    fun play(uri: Uri, onPrepared: () -> Unit) {
        val p = ensurePlayer()
        try {
            p.reset()
            p.setDataSource(context, uri)
            p.setOnPreparedListener {
                it.start()
                onPrepared()
            }
            p.prepareAsync()
        } catch (_: Exception) {
            onError?.invoke()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun resume() {
        player?.takeIf { !it.isPlaying }?.start()
    }

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
    }

    fun currentPosition(): Long = player?.currentPosition?.toLong() ?: 0L

    fun duration(): Long = player?.duration?.toLong() ?: 0L

    fun release() {
        player?.release()
        player = null
    }
}
