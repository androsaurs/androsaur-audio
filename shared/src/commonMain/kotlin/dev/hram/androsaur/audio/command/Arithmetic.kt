package dev.hram.androsaur.audio.command

/**
 * «Сколько будет семь умножить на восемь»: an arithmetic question heard as words, turned into an expression such as `7 * 8`
 * (numbers and `+ - * /` separated by spaces). Children use the assistant as a calculator. The answer is the main app's.
 */
object Arithmetic {
    /**
     * The expression in [words] (lower case, ё as е), or null when they are not «number, operation, number…». Words around the
     * example («сколько», «будет», «а») are ignored; a bare «на» between numbers is multiplication («семь на восемь»).
     */
    fun parse(words: List<String>): String? {
        val out = mutableListOf<String>()
        var number = mutableListOf<String>()
        var op: String? = null
        fun closeNumber(): Boolean {
            if (number.isEmpty()) return true
            val n = RussianNumbers.value(number) ?: return false
            number = mutableListOf()
            if (out.isNotEmpty() && out.last() !in OPS) return false // two numbers in a row
            out += n.toString()
            return true
        }
        for (w in words) {
            when {
                RussianNumbers.isNumberWord(w) || w.toLongOrNull() != null -> {
                    if (op != null) { out += op; op = null }
                    number += w
                }
                OPERATIONS[w] != null -> {
                    if (!closeNumber() || out.isEmpty() || op != null) return null
                    op = OPERATIONS[w]
                }
                w == "на" -> {
                    // «умножить на», «разделить на»: the operation is already known. Otherwise «семь на восемь».
                    if (op != null) continue
                    if (number.isEmpty()) return null
                    if (!closeNumber()) return null
                    op = "*"
                }
                w in FILLER -> {}
                // A word that is not of the example: before it, it is ignored; after a whole example, the example is over.
                else -> if (op != null) return null else if (number.isNotEmpty() || out.isNotEmpty()) break
            }
        }
        if (op != null || !closeNumber()) return null
        if (out.size < 3) return null
        return out.joinToString(" ")
    }

    private val OPERATIONS = mapOf(
        "плюс" to "+", "прибавить" to "+", "прибавь" to "+", "добавить" to "+", "добавь" to "+",
        "минус" to "-", "отнять" to "-", "отними" to "-", "вычесть" to "-", "вычти" to "-",
        "умножить" to "*", "умножь" to "*", "помножить" to "*", "помножь" to "*",
        "разделить" to "/", "раздели" to "/", "поделить" to "/", "подели" to "/", "делить" to "/",
    )
    private val OPS = setOf("+", "-", "*", "/")
    private val FILLER = setOf("сколько", "будет", "а", "ну", "если", "равно", "получится", "это", "посчитай", "скажи")

    /** The words of arithmetic questions, for a recognizer limited to a list of words. */
    val vocabulary: List<String> get() = (OPERATIONS.keys + FILLER + RussianNumbers.words + "на").toList()
}

/** Russian cardinal numbers in words: «сто двадцать три», «две тысячи пять», «один миллион». */
object RussianNumbers {
    private val small = mapOf(
        "ноль" to 0, "нуль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80,
        "девяносто" to 90, "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900,
    )
    private val scales = mapOf(
        "тысяча" to 1_000L, "тысячи" to 1_000L, "тысяч" to 1_000L, "тысячу" to 1_000L,
        "миллион" to 1_000_000L, "миллиона" to 1_000_000L, "миллионов" to 1_000_000L,
        "миллиард" to 1_000_000_000L, "миллиарда" to 1_000_000_000L, "миллиардов" to 1_000_000_000L,
    )

    val words: Set<String> get() = small.keys + scales.keys

    fun isNumberWord(w: String) = w in small || w in scales

    /**
     * The value of number words in a row, or null when they do not make one number («пять семь», «тысяча тысяча»).
     * Digits are taken as they are.
     */
    fun value(words: List<String>): Long? {
        if (words.size == 1) words[0].toLongOrNull()?.let { return it }
        var total = 0L
        var group = 0L
        var lastSmall = Int.MAX_VALUE // the order of the last word inside the group, so «двадцать пять» is fine and «пять двадцать» is not
        var lastScale = Long.MAX_VALUE
        for (w in words) {
            val s = small[w]
            val scale = scales[w]
            when {
                s != null -> {
                    // 3 hundreds, 2 tens, 1 «десять…девятнадцать», 0 ones; each must be smaller than the one before, and after
                    // «одиннадцать» nothing more of the group.
                    val order = when { s >= 100 -> 3; s >= 20 -> 2; s >= 10 -> 1; else -> 0 }
                    val allowed = if (order == 1) lastSmall > 2 else lastSmall > order
                    if (!allowed) return null
                    group += s
                    lastSmall = if (order == 1) 0 else order
                }
                scale != null -> {
                    if (scale >= lastScale) return null
                    total += (if (group == 0L) 1L else group) * scale
                    group = 0L
                    lastSmall = Int.MAX_VALUE
                    lastScale = scale
                }
                else -> return null
            }
        }
        return total + group
    }
}
