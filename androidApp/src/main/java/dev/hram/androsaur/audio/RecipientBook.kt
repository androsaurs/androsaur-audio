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
            Recipient(o.getString("id"), o.optJSONArray("forms")?.let { f -> List(f.length()) { f.getString(it) } } ?: emptyList(), o.optString("person").ifEmpty { null },
                o.optString("personId").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    /** Replaces the whole list. Returns an error code, or null. */
    fun replace(list: List<Recipient>): String? {
        if (list.size > MAX) return "too_many_recipients"
        if (list.any { it.id.isBlank() || it.id.length > 40 || it.forms.size > 10 || it.forms.any { f -> f.length > 40 } }) return "invalid_recipient"
        val array = JSONArray()
        list.forEach { r ->
            array.put(JSONObject().put("id", r.id.trim()).put("forms", JSONArray(r.forms)).apply { r.person?.let { put("person", it) }; r.personId?.let { put("personId", it) } })
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

    /**
     * The people the camera knows, as (permanent id, current name). A person already in the list keeps their place and takes
     * the current name (a rename; the old spoken forms go, and roles filed under the old name follow); a name in the list
     * without an id gets the id; a new person is added at the end. Returns how many entries changed.
     */
    fun addPeople(people: List<Pair<String, String>>): Int {
        val have = all()
        val (list, changed) = Recipient.withPeople(have, people, MAX)
        if (changed > 0) replace(list)
        return changed
    }

    /** The person [from] is the person [into]: their entry takes the id, or goes when [into] has one already. */
    fun merge(from: String, into: String): Boolean {
        val have = all()
        val merged = Recipient.merged(have, from, into)
        if (merged != have) replace(merged)
        return merged != have
    }

    fun toJson(): JSONArray {
        val array = JSONArray()
        all().forEach { r -> array.put(JSONObject().put("id", r.id).put("forms", JSONArray(r.forms)).apply { r.person?.let { put("person", it) }; r.personId?.let { put("personId", it) } }) }
        return array
    }

    companion object { const val MAX = 40; private const val KEY = "recipients" }
}
