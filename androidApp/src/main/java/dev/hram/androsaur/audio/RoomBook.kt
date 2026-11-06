package dev.hram.androsaur.audio

import android.content.SharedPreferences
import dev.hram.androsaur.audio.command.Room
import org.json.JSONArray
import org.json.JSONObject

/** The rooms with speakers the assistant can speak on («передай в детскую»), as the hub names them; the main app keeps the list fresh. */
class RoomBook(private val prefs: SharedPreferences) {
    fun all(): List<Room> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Room(o.getString("id"), o.getString("name"), o.getJSONArray("words").let { w -> List(w.length()) { w.getString(it) } })
        }
    }.getOrDefault(emptyList())

    /** Replaces the whole list. Returns an error code, or null. */
    fun replace(list: List<Room>): String? {
        if (list.size > MAX) return "too_many_rooms"
        if (list.any { it.id.isBlank() || it.id.length > 40 || it.name.isBlank() || it.name.length > 40 || it.words.isEmpty() || it.words.size > 10 || it.words.any { w -> w.length > 60 } }) return "invalid_room"
        prefs.edit().putString(KEY, toJson(list).toString()).apply()
        return null
    }

    fun toJson(list: List<Room> = all()): JSONArray {
        val array = JSONArray()
        list.forEach { r -> array.put(JSONObject().put("id", r.id).put("name", r.name).put("words", JSONArray(r.words))) }
        return array
    }

    companion object { const val MAX = 20; private const val KEY = "rooms" }
}

/**
 * Messages for a speaker that were recorded and wait for the main app to take them to the hub. They are kept in memory only
 * (never filed as notes): the last few, and not longer than [KEEP_MS].
 */
class AnnouncementBox {
    private val items = java.util.LinkedHashMap<String, Pair<Long, ByteArray>>()

    @Synchronized fun add(wav: ByteArray): String {
        val now = android.os.SystemClock.elapsedRealtime()
        items.entries.removeAll { now - it.value.first > KEEP_MS }
        while (items.size >= MAX) items.remove(items.keys.first())
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        items[id] = now to wav
        return id
    }

    @Synchronized fun get(id: String): ByteArray? = items[id]?.second

    @Synchronized fun remove(id: String): Boolean = items.remove(id) != null

    companion object { private const val MAX = 3; private const val KEEP_MS = 10 * 60_000L }
}
