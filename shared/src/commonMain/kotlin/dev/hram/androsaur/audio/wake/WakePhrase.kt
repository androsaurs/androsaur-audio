package dev.hram.androsaur.audio.wake

/**
 * The wake phrase as a setting: words separated by spaces, each word may list alternatives with `|`
 * and a tolerance `~N` per alternative (see [PhraseMatcher]). Only Russian letters are accepted, because
 * the speech model is Russian. The same string drives the matcher and the vosk-grammar word list.
 */
object WakePhrase {
    const val DEFAULT = "привет~3 петя~1"
    private const val MAX_LENGTH = 80
    private const val MAX_WORDS = 4
    private const val MAX_GRAMMAR_PHRASES = 16
    private val variant = Regex("[а-яё]{2,20}(~[0-3])?")

    /** Cleans a user-supplied phrase: lower case, commas and dots dropped, spaces collapsed. Null when it is not valid. */
    fun normalize(raw: String): String? {
        val text = raw.lowercase().replace(Regex("[,.!?]"), " ").trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val words = text.split(' ')
        if (words.size > MAX_WORDS) return null
        if (!words.all { word -> word.split('|').all { variant.matches(it) } }) return null
        return text
    }

    /** Plain phrases for a recognizer grammar: alternatives expanded, tolerances dropped. */
    fun grammarPhrases(phrase: String): List<String> {
        var phrases = listOf("")
        for (word in phrase.split(' ')) {
            val options = word.split('|').map { it.substringBefore('~') }.distinct()
            phrases = phrases.flatMap { head -> options.map { if (head.isEmpty()) it else "$head $it" } }.take(MAX_GRAMMAR_PHRASES)
        }
        return phrases
    }

    /** Readable form for the notification and the screen: first alternative of each word, tolerances dropped. */
    fun display(phrase: String): String = grammarPhrases(phrase).first()
}
