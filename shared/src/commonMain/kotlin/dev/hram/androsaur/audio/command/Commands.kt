package dev.hram.androsaur.audio.command

/**
 * Somebody a note can be left for. [id] is what the person is called («жена», «Маша»); [forms] are extra spoken forms;
 * [person] is who the notes are filed under when it differs from [id] («жена» is filed under «Маша», the name the camera knows).
 */
data class Recipient(val id: String, val forms: List<String> = emptyList(), val person: String? = null) {
    /** The name a note for this recipient is addressed to. */
    val addressee: String get() = person?.takeIf { it.isNotBlank() } ?: id
}

/** What was understood from a spoken command. [recipient] is null when the verb was heard but not for whom. */
data class Command(val recipient: Recipient?, val heardWord: String?, val withFor: Boolean) {
    /** «Записка жене» / «Записка для жены»: the recipient as the speaker said it, to repeat it back. */
    fun echo(): String? = heardWord?.let { "Записка " + (if (withFor) "для " else "") + it }
}

/**
 * Understands «передай жене…», «скажи Маше…», «оставь записку для мамы», «запиши сообщение папе»: a verb of leaving a note,
 * then within a few words the recipient. A recognizer hears names and cases only roughly, so a heard word matches a recipient
 * when it is the same word or starts with the same letter and is one edit away («жене», «жену», «жены» all reach «жена»).
 */
class CommandParser(private val recipients: List<Recipient>) {
    /** [verbSeen]: the verb was heard in an earlier utterance and this one only names the recipient. */
    fun parse(text: String, verbSeen: Boolean = false): Command? {
        val words = text.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9-]+")).filter { it.isNotEmpty() }
        val verbAt = if (verbSeen) -1 else words.indexOfFirst { w -> VERBS.any { close(w, it) } }
        // The verb is required: other commands will come («включи радио») and a name in other speech must not start a note.
        if (!verbSeen && verbAt < 0) return null
        val from = verbAt + 1
        val window = words.subList(from, minOf(words.size, from + MAX_WORDS_AFTER_VERB))
        var best: Triple<Recipient, String, Int>? = null
        var bestScore = Int.MAX_VALUE
        window.forEachIndexed { i, word ->
            if (word in FILLERS) return@forEachIndexed
            recipients.forEach { r ->
                val score = (listOf(r.id) + r.forms).minOf { form -> distance(word, form.lowercase().replace('ё', 'е')) }
                if (score < bestScore) { bestScore = score; best = Triple(r, word, from + i) }
            }
        }
        val hit = best?.takeIf { bestScore <= 1 }
        return if (hit == null) Command(null, null, false)
        else Command(hit.first, hit.second, hit.third > 0 && words[hit.third - 1] == "для")
    }

    /** 0 for the same word, 1 for a near one with the same first letter, a large number otherwise. */
    private fun distance(heard: String, form: String): Int {
        if (heard == form) return 0
        if (form.length < 3 || heard.isEmpty() || heard[0] != form[0]) return NO_MATCH
        return if (edits(heard, form) <= 1) 1 else NO_MATCH
    }

    private fun close(heard: String, verb: String) = heard == verb || (heard.isNotEmpty() && heard[0] == verb[0] && edits(heard, verb) <= 1)

    private fun edits(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }

    companion object {
        /** The words a recognizer limited to this command has to know, besides the recipients. */
        val vocabulary: List<String> get() = VERBS + FILLERS

        private val VERBS = listOf("передай", "передайте", "скажи", "скажите", "запиши", "запишите", "оставь", "оставьте", "отправь", "отправьте", "напиши", "напишите", "записку", "записка", "сообщение")
        /** Words between the verb and the recipient that are never a recipient. */
        private val FILLERS = setOf("для", "что", "записку", "записка", "сообщение", "мне", "пожалуйста", "а", "ему", "ей")
        private const val MAX_WORDS_AFTER_VERB = 5
        private const val NO_MATCH = 99
    }
}

/** The case forms of a name or a word, written out, for a recognizer that is limited to a list of words (it does not guess forms). */
object Declension {
    fun forms(word: String): List<String> {
        val w = word.trim().lowercase().replace('ё', 'е')
        if (w.length < 2 || ' ' in w) return listOf(w).filter { it.isNotEmpty() }
        val stem = w.dropLast(1)
        val extra = when (w.last()) {
            // after ж ш ч щ г к х the genitive ends in и, not ы («Маши»)
            'а' -> listOf("е", "у", if (stem.lastOrNull()?.let { it in "жшчщгкх" } == true) "и" else "ы").map { stem + it }
            'я' -> listOf("е", "ю", "и").map { stem + it } + if (w.endsWith("ия")) listOf(stem + "и") else emptyList()
            'й' -> listOf("ю", "я", "е").map { stem + it }
            'ь' -> listOf("и", "я", "ю").map { stem + it }
            'о', 'е' -> listOf("у", "а").map { stem + it }
            'и', 'ы', 'у', 'ю' -> emptyList()
            else -> listOf("у", "а", "е").map { w + it }
        }
        return (listOf(w) + extra).distinct()
    }
}
