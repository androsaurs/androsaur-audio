package dev.hram.androsaur.audio.events

class AudioEvent(val id: Long, val atMs: Long, val type: String, val data: Map<String, String>)

/** Bounded in-memory journal of what the audio service did. Not thread-safe: callers synchronize. */
class EventLog(private val capacity: Int = 200) {
    private val items = ArrayDeque<AudioEvent>()
    var latestId = 0L
        private set

    fun append(type: String, nowMs: Long, data: Map<String, String> = emptyMap()): AudioEvent {
        val event = AudioEvent(++latestId, nowMs, type, data)
        items.addLast(event)
        while (items.size > capacity) items.removeFirst()
        return event
    }

    fun since(afterId: Long, limit: Int = 100): List<AudioEvent> = items.filter { it.id > afterId }.take(limit)
}
