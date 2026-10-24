package dev.hram.androsaur.audio.wake

/** Decides whether recognized text contains the wake phrase as whole words. */
class PhraseMatcher(phrase: String) {
    private val words = normalize(phrase)

    init { require(words.isNotEmpty()) { "Empty wake phrase" } }

    fun matches(text: String): Boolean {
        val heard = normalize(text)
        if (heard.size < words.size) return false
        for (start in 0..heard.size - words.size) {
            if (words.indices.all { heard[start + it] == words[it] }) return true
        }
        return false
    }

    private fun normalize(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(' ', '\t', '\n', ',', '.', '!', '?').filter { it.isNotEmpty() }
}
