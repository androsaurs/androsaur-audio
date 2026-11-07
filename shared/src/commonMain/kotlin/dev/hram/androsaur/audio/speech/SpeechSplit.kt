package dev.hram.androsaur.audio.speech

/**
 * Cuts a text into the pieces the voice is asked for one after another. The wait for the first sound is the time to make the
 * first piece, so the first sentence is cut at its first comma (or semicolon, colon, dash) when it is long; the rest is kept as
 * whole sentences: the voice makes speech faster than it plays, so later pieces are ready while the earlier one is still sounding.
 */
object SpeechSplit {
    const val LONG_FIRST = 24
    const val MIN_HEAD = 4

    fun pieces(text: String): List<String> {
        val sentences = Regex("""[^.!?…\n]+[.!?…]*""").findAll(text).map { it.value.trim() }.filter { it.any(Char::isLetterOrDigit) }.toList()
        if (sentences.isEmpty()) return emptyList()
        val first = sentences.first()
        val cut = if (first.length > LONG_FIRST) first.indexOfFirst { it in ",;:—–" }.takeIf { it >= MIN_HEAD - 1 && it < first.length - 8 } else null
        val head = if (cut == null) listOf(first) else listOf(first.substring(0, cut + 1).trim(), first.substring(cut + 1).trim())
        return head + sentences.drop(1)
    }
}
