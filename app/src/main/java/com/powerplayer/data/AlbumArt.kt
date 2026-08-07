package com.powerplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object AlbumArt {

    private val COVER_NAMES = listOf(
        "cover.jpg", "cover.png", "Cover.jpg", "Cover.png",
        "folder.jpg", "folder.png", "Folder.jpg", "Folder.png",
        "albumart.jpg", "albumart.png", "AlbumArt.jpg",
        "album.jpg", "album.png", "Album.jpg",
        "front.jpg", "front.png", "Front.jpg"
    )

    suspend fun load(context: Context, trackUri: Uri, treeUri: Uri?): Bitmap? {
        embeddedArt(context, trackUri)?.let { return it }
        if (treeUri != null) {
            folderCover(context, trackUri, treeUri)?.let { return it }
        }
        return null
    }

    private fun embeddedArt(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun folderCover(context: Context, trackUri: Uri, treeUri: Uri): Bitmap? {
        return try {
            val trackId = DocumentsContract.getDocumentId(trackUri)
            val parentId = trackId.substringBeforeLast('/', trackId)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            for (name in COVER_NAMES) {
                val coverUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$parentId/$name")
                val file = DocumentFile.fromSingleUri(context, coverUri)
                if (file != null && file.exists() && file.isFile) {
                    context.contentResolver.openInputStream(coverUri)?.use { input ->
                        return BitmapFactory.decodeStream(input)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
