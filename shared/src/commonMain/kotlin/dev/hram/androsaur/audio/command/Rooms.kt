package dev.hram.androsaur.audio.command

/**
 * A room with a speaker the assistant can speak on. [words] are how people call it after «в» («детская», «детскую», «детской»);
 * a one-word name is also given its case forms. [id] is the hub's, so a rename does not change where a message goes.
 */
data class Room(val id: String, val name: String, val words: List<String>)

/**
 * Understands «передай в детскую…», «скажи на колонку бабушки…», «передай в комнату Полины…»: a verb, then within a few words
 * «в» / «во» / «на» and a room name. The preposition is what tells a room (a speaker) from a person (a note): «передай Полине»
 * has none and is a note, whatever the rooms are called. A heard word fits a room name when it is the same or one edit away.
 */
class RoomParser(private val rooms: List<Room>) {
    /** [verbSeen]: the verb was heard in an earlier utterance and this one only names the place («в детскую»). */
    fun parse(text: String, verbSeen: Boolean = false): Room? {
        val words = tokens(text)
        val from = if (verbSeen) 0 else words.indexOfFirst { w -> CommandParser.VERBS.any { close(w, it) } }.let { if (it < 0) return null else it + 1 }
        // The preposition must come right after the verb («передай маме что в детской…» is a note for mama, not a room).
        var at = from
        while (at < words.size && words[at] in LEAD) at++
        if (at >= words.size) return null
        everywhere(words, at)?.let { return it }
        if (words[at] !in PREPOSITIONS) return bare(words, at)
        at++
        while (at < words.size && words[at] in FILLERS) at++
        val window = words.subList(0, minOf(words.size, at + MAX_NAME_WORDS))
        var best: Room? = null
        var bestCost = Int.MAX_VALUE
        var bestLength = 0
        for (room in rooms) for (alias in aliases(room)) {
            if (at + alias.size > window.size) continue
            var cost = 0
            for (k in alias.indices) {
                val d = distance(window[at + k], alias[k])
                if (d == NO_MATCH) { cost = NO_MATCH; break }
                cost += d
            }
            if (cost == NO_MATCH) continue
            if (cost < bestCost || (cost == bestCost && alias.size > bestLength)) { best = room; bestCost = cost; bestLength = alias.size }
        }
        return best
    }

    /** «На все колонки» / «во все комнаты»; the preposition may be lost by the recognizer, then the noun must be there. */
    private fun everywhere(words: List<String>, from: Int): Room? {
        var at = from
        val prepositionHeard = words[at] in PREPOSITIONS
        if (prepositionHeard) at++
        if (at >= words.size || words[at] !in ALL_WORDS) return null
        val noun = words.getOrNull(at + 1) in ALL_NOUNS
        return if (prepositionHeard || noun) EVERYWHERE else null
    }

    /**
     * A recognizer often drops the short «в» («передай гостиная»). Then a room is taken only when the name is one of the words
     * the owner listed, exactly: «передай полина» is a room named so, «передай Полине» (a case form we made up) stays a note.
     */
    private fun bare(words: List<String>, from: Int): Room? {
        var at = from
        while (at < words.size && words[at] in FILLERS) at++
        for (room in rooms) for (phrase in room.words) {
            val alias = tokens(phrase)
            if (alias.isNotEmpty() && at + alias.size <= words.size && alias.indices.all { words[at + it] == alias[it] }) return room
        }
        return null
    }

    private fun aliases(room: Room): List<List<String>> = room.words.flatMap { phrase ->
        val ws = tokens(phrase)
        if (ws.size == 1) Declension.forms(ws[0]).map { listOf(it) } else listOf(ws)
    }.filter { it.isNotEmpty() }

    private fun distance(heard: String, form: String): Int {
        if (heard == form) return 0
        if (form.length < 4 || heard.isEmpty() || heard[0] != form[0]) return NO_MATCH
        return if (CommandParser.edits(heard, form) <= 1) 1 else NO_MATCH
    }

    private fun close(heard: String, verb: String) = heard == verb || (heard.isNotEmpty() && heard[0] == verb[0] && CommandParser.edits(heard, verb) <= 1)

    companion object {
        /** The hub speaks on every speaker for this id. */
        const val ALL_ID = "all"
        val EVERYWHERE = Room(ALL_ID, "все колонки", emptyList())
        private val ALL_WORDS = setOf("все", "всех", "всё")
        private val ALL_NOUNS = setOf("колонки", "колонок", "колонках", "комнаты", "комнатах")
        private val PREPOSITIONS = setOf("в", "во", "на")
        /** Words between the preposition and the name that are not part of it. */
        private val FILLERS = setOf("комнату", "комнате", "комната", "колонку", "колонке", "колонка")
        /** Words allowed between the verb and the preposition: «передай сообщение в детскую». */
        private val LEAD = setOf("сообщение", "записку", "записка", "пожалуйста", "мне")
        private const val MAX_NAME_WORDS = 3
        private const val NO_MATCH = 99

        private fun tokens(text: String) = text.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9-]+")).filter { it.isNotEmpty() }

        /** The words a recognizer limited to this command has to know, besides the rest: the prepositions and the room names. */
        fun vocabulary(rooms: List<Room>): List<String> =
            (PREPOSITIONS + FILLERS + LEAD + ALL_WORDS + ALL_NOUNS + rooms.flatMap { r -> r.words.flatMap { w -> val ws = tokens(w); if (ws.size == 1) Declension.forms(ws[0]) else ws } }).distinct()
    }
}
