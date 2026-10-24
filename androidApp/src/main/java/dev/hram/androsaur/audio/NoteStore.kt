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
    fun add(to: String, from: String, wav: ByteArray, durationMs: Long, nowMs: Long = System.currentTimeMillis()): Note = synchronized(lock) {
        val note = Note(UUID.randomUUID().toString().replace("-", "").take(12), to, from, nowMs, durationMs)
        file(note.id).writeBytes(wav)
        val kept = NoteBook.purge(read() + note, nowMs)
        write(kept); sweep(kept)
        note
    }

    fun get(id: String): Note? = all().firstOrNull { it.id == id }

    fun markHeard(id: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(heardAtMs = nowMs) else it })
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
                if (o.has("heardAtMs")) o.getLong("heardAtMs") else null)
        }.filter { file(it.id).exists() }
    } catch (_: Exception) { emptyList() }

    private fun write(notes: List<Note>) {
        val array = JSONArray()
        notes.forEach {
            array.put(JSONObject().put("id", it.id).put("to", it.to).put("from", it.from).put("createdAtMs", it.createdAtMs)
                .put("durationMs", it.durationMs).apply { it.heardAtMs?.let { h -> put("heardAtMs", h) } })
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(index)
    }
}
