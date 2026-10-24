package dev.hram.androsaur.audio

import android.content.SharedPreferences
import dev.hram.androsaur.audio.command.Recipient
import org.json.JSONArray
import org.json.JSONObject

/** Who a note can be left for by voice: roles («жена») and names, in the order that decides ties. Kept in the preferences. */
class RecipientBook(private val prefs: SharedPreferences) {
    fun all(): List<Recipient> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Recipient(o.getString("id"), o.optJSONArray("forms")?.let { f -> List(f.length()) { f.getString(it) } } ?: emptyList(), o.optString("person").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    /** Replaces the whole list. Returns an error code, or null. */
    fun replace(list: List<Recipient>): String? {
        if (list.size > MAX) return "too_many_recipients"
        if (list.any { it.id.isBlank() || it.id.length > 40 || it.forms.size > 10 || it.forms.any { f -> f.length > 40 } }) return "invalid_recipient"
        val array = JSONArray()
        list.forEach { r ->
            array.put(JSONObject().put("id", r.id.trim()).put("forms", JSONArray(r.forms)).apply { r.person?.let { put("person", it) } })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        return null
    }

    /** Adds the names that are not in the list yet, at the end (the main app offers the people the camera knows). */
    fun addNames(names: List<String>): Int {
        val have = all()
        val known = have.map { it.id.lowercase() }.toSet()
        val fresh = names.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in known }.distinct()
        if (fresh.isEmpty() || have.size + fresh.size > MAX) return 0
        replace(have + fresh.map { Recipient(it) })
        return fresh.size
    }

    fun toJson(): JSONArray {
        val array = JSONArray()
        all().forEach { r -> array.put(JSONObject().put("id", r.id).put("forms", JSONArray(r.forms)).apply { r.person?.let { put("person", it) } }) }
        return array
    }

    companion object { const val MAX = 40; private const val KEY = "recipients" }
}
