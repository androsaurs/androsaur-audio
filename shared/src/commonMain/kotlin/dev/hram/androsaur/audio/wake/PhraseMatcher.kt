package dev.hram.androsaur.audio.wake

/**
 * Decides whether recognized text contains the wake phrase as whole words, in order.
 *
 * A word of the phrase may list alternatives with `|` (`петя|пети`), and each variant may carry a
 * tolerance `~N` (`петя~2`): a heard word matches when it starts with the same letter and is at most
 * N edits away. Speech recognizers often hear a name slightly wrong; the shared first letter keeps
 * other names out (`катя` is not `петя`).
 */
class PhraseMatcher(phrase: String) {
    private class Variant(val text: String, val tolerance: Int)

    private val words: List<List<Variant>> = split(phrase).map { token ->
        token.split('|').filter { it.isNotEmpty() }.map { part ->
            val at = part.indexOf('~')
            if (at < 0) Variant(normalizeWord(part), 0) else Variant(normalizeWord(part.substring(0, at)), part.substring(at + 1).toInt())
        }.filter { it.text.isNotEmpty() }
    }

    init { require(words.isNotEmpty() && words.all { it.isNotEmpty() }) { "Empty wake phrase" } }

    fun matches(text: String): Boolean {
        val heard = split(text).map { normalizeWord(it) }
        if (heard.size < words.size) return false
        for (start in 0..heard.size - words.size) {
            if (words.indices.all { accepts(words[it], heard[start + it]) }) return true
        }
        return false
    }

    private fun accepts(variants: List<Variant>, heard: String) = variants.any { v ->
        heard == v.text || (v.tolerance > 0 && heard.isNotEmpty() && heard[0] == v.text[0] && editDistance(heard, v.text) <= v.tolerance)
    }

    private fun split(text: String) = text.split(' ', '\t', '\n', ',', '.', '!', '?').filter { it.isNotEmpty() }

    private fun normalizeWord(word: String) = word.lowercase().replace('ё', 'е')

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
