package com.powerplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object TrackScanner {

    private val EXTENSIONS = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "aiff")

    suspend fun scanFolder(context: Context, folderUri: Uri): List<Track> {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val tracks = mutableListOf<Track>()
        collectTracks(context, root, tracks)
        return tracks
    }

    private fun collectTracks(context: Context, dir: DocumentFile, out: MutableList<Track>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> collectTracks(context, child, out)
                child.isFile && isAudio(child.name) -> {
                    readMetadata(context, child)?.let { out.add(it) }
                }
            }
        }
    }

    private fun isAudio(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in EXTENSIONS
    }

    private fun readMetadata(context: Context, file: DocumentFile): Track? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: file.name?.substringBeforeLast('.')
                ?: "Неизвестный трек"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Неизвестный исполнитель"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Track(file.uri.toString(), title, artist, album, durationMs)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
