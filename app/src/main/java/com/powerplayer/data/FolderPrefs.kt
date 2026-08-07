package com.powerplayer.data

import android.content.Context

object FolderPrefs {
    private const val PREFS = "powerplayer"
    private const val KEY_FOLDER = "music_folder"

    fun save(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER, uri)
            .apply()
    }

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER, null)
}
