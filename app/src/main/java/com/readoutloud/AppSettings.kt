package com.readoutloud

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("readoutloud", Context.MODE_PRIVATE)
    private val defaultLang = Locale.getDefault().language.take(2)

    var lastUri: Uri?
        get() = prefs.getString("last_uri", null)?.toUri()
        set(value) = prefs.edit().putString("last_uri", value?.toString()).apply()

    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(value) = prefs.edit().putFloat("speech_rate", value).apply()

    var savedLineIndex: Int
        get() = prefs.getInt("saved_line_index", -1)
        set(value) = prefs.edit().putInt("saved_line_index", value).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 1.0f)
        set(value) = prefs.edit().putFloat("volume", value).apply()

    var isSpeaker: Boolean
        get() = prefs.getBoolean("is_speaker", true)
        set(value) = prefs.edit().putBoolean("is_speaker", value).apply()

    var language: String
        get() = prefs.getString("language", null) ?: defaultLang
        set(value) = prefs.edit().putString("language", value).apply()

    var selectedVoice: String?
        get() = prefs.getString("selected_voice", null)
        set(value) = prefs.edit().putString("selected_voice", value).apply()

    var downloadedVoices: List<String>
        get() = prefs.getStringSet("downloaded_voices", emptySet())?.toList() ?: emptyList()
        set(value) = prefs.edit().putStringSet("downloaded_voices", value.toSet()).apply()
}
