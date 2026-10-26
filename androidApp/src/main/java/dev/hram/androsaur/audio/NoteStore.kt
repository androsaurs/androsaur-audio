package dev.hram.androsaur.audio

import android.content.Context
import dev.hram.androsaur.audio.notes.Note
import dev.hram.androsaur.audio.notes.NoteBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Voice notes on disk: one WAV per note and an index, so the queue survives the service being killed. */
class NoteStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val lock = Any()

    fun file(id: String) = File(dir, "$id.wav")

    fun all(nowMs: Long = System.currentTimeMillis()): List<Note> = synchronized(lock) {
        val loaded = read()
        val kept = NoteBook.purge(loaded, nowMs)
        if (kept.size != loaded.size) { write(kept); sweep(kept) }
        kept
    }

    /** Stores the sound (16-bit mono WAV bytes) as a new note. */
    fun add(to: String, from: String, wav: ByteArray, durationMs: Long, toId: String? = null, fromId: String? = null, nowMs: Long = System.currentTimeMillis()): Note = synchronized(lock) {
        val note = Note(UUID.randomUUID().toString().replace("-", "").take(12), to, from, nowMs, durationMs, null, toId, fromId)
        file(note.id).writeBytes(wav)
        val kept = NoteBook.purge(read() + note, nowMs)
        write(kept); sweep(kept)
        note
    }

    fun get(id: String): Note? = all().firstOrNull { it.id == id }

    fun markHeard(id: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(heardAtMs = nowMs) else it })
    }

    /** Ties a note made before ids to the person it was for (or from). False when there is no such note. */
    fun address(id: String, toId: String?, fromId: String?): Boolean = synchronized(lock) {
        val before = read()
        if (before.none { it.id == id }) return false
        write(before.map { if (it.id == id) it.copy(toId = toId ?: it.toId, fromId = fromId ?: it.fromId) else it })
        true
    }

    /** Keeps the transcription of a note (from the hub). False when there is no such note. */
    fun setText(id: String, text: String?): Boolean = synchronized(lock) {
        val before = read()
        if (before.none { it.id == id }) return false
        write(before.map { if (it.id == id) it.copy(text = text?.trim()?.ifEmpty { null }) else it })
        true
    }

    /** Notes for or from the person [from] now belong to [into] (the same person under the house's id). Returns how many changed. */
    fun readdress(from: String, into: String): Int = synchronized(lock) {
        val before = read()
        val after = before.map { n -> n.copy(toId = if (n.toId == from) into else n.toId, fromId = if (n.fromId == from) into else n.fromId) }
        val changed = before.zip(after).count { (a, b) -> a != b }
        if (changed > 0) write(after)
        changed
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val before = read()
        val after = before.filter { it.id != id }
        write(after); sweep(after)
        after.size != before.size
    }

    private fun sweep(kept: List<Note>) {
        val ids = kept.map { it.id }.toSet()
        dir.listFiles { f -> f.extension == "wav" }?.filter { it.nameWithoutExtension !in ids }?.forEach { it.delete() }
    }

    private fun read(): List<Note> = try {
        val array = JSONArray(index.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Note(o.getString("id"), o.getString("to"), o.optString("from"), o.getLong("createdAtMs"), o.getLong("durationMs"),
                if (o.has("heardAtMs")) o.getLong("heardAtMs") else null, o.optString("toId").ifEmpty { null }, o.optString("fromId").ifEmpty { null },
                o.optString("text").ifEmpty { null })
        }.filter { file(it.id).exists() }
    } catch (_: Exception) { emptyList() }

    private fun write(notes: List<Note>) {
        val array = JSONArray()
        notes.forEach {
            array.put(JSONObject().put("id", it.id).put("to", it.to).put("from", it.from).put("createdAtMs", it.createdAtMs)
                .put("durationMs", it.durationMs).apply {
                    it.heardAtMs?.let { h -> put("heardAtMs", h) }; it.toId?.let { v -> put("toId", v) }; it.fromId?.let { v -> put("fromId", v) }; it.text?.let { v -> put("text", v) }
                })
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(index)
    }
}
