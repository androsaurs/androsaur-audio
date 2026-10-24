package dev.hram.androsaur.audio

import android.content.Context
import java.util.UUID

/** Bearer code of the local server. Never print it or log it. */
object AccessToken {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)
        return prefs.getString("access_token", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("access_token", it).apply()
        }
    }
}
