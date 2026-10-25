package dev.hram.androsaur.audio.command

/**
 * Short commands after the wake word that need no LLM and are answered by templates: the time, the weather (today or tomorrow), a
 * timer, how much is left on it, «стоп» / «останови таймер», «повтори записку». This is the layer of fast things that stays when an
 * LLM comes. Notes for somebody («передай жене…») are [CommandParser]'s and come after these.
 */
sealed interface QuickCommand {
    data object Time : QuickCommand
    data class Weather(val tomorrow: Boolean) : QuickCommand
    /** A timer for [seconds]. */
    data class Timer(val seconds: Int) : QuickCommand
    data object TimerLeft : QuickCommand
    data object Stop : QuickCommand
    /** Play again the latest note for the person in front of the camera. */
    data object RepeatNote : QuickCommand
}

object QuickCommands {
    /** What the text asks, or null when it is none of these. */
    fun parse(text: String): QuickCommand? {
        val words = text.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9-]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val has = { w: String -> w in words }
        val starts = { p: String -> words.any { it.startsWith(p) } }
        if (words.any { it in STOP }) return QuickCommand.Stop
        if (starts("таймер")) {
            if (has("осталось") || has("сколько")) return QuickCommand.TimerLeft
            return duration(words)?.let { QuickCommand.Timer(it) }
        }
        if (has("осталось") && has("сколько")) return QuickCommand.TimerLeft
        if ((has("который") && starts("час")) || (has("сколько") && starts("врем")) || (has("какое") && has("время"))) return QuickCommand.Time
        if (starts("погод") || starts("прогноз")) return QuickCommand.Weather(tomorrow = has("завтра"))
        // «Повтори записку», «ещё раз сообщение» — but not «передай … записку», which is a note for somebody.
        val again = starts("повтор") || (has("еще") && has("раз"))
        if (again && (starts("запис") || starts("сообщен")) && words.none { it in NOTE_VERBS }) return QuickCommand.RepeatNote
        return null
    }

    /** The length of a timer in seconds from words such as «на десять минут», «полчаса», «полторы минуты», «час»; minutes when no unit. */
    fun duration(words: List<String>): Int? {
        if ("полчаса" in words) return 30 * 60
        if ("полминуты" in words) return 30
        var number: Double? = null
        var unit = 60
        var i = 0
        while (i < words.size) {
            val w = words[i]
            w.toIntOrNull()?.let { number = (number ?: 0.0) + it }
            NUMBERS[w]?.let { number = (number ?: 0.0) + it }
            when {
                w.startsWith("секунд") -> { unit = 1; break }
                w.startsWith("минут") -> { unit = 60; break }
                w == "час" || w == "часа" || w == "часов" -> { unit = 3600; break }
            }
            i++
        }
        val n = number ?: if (words.any { it == "час" }) 1.0 else return null
        val seconds = (n * unit).toInt()
        return seconds.takeIf { it in 1..MAX_SECONDS }
    }

    /** All the words these commands are made of, for a recognizer limited to a list of words. */
    val vocabulary: List<String> get() =
        (STOP + NOTE_VERBS + NUMBERS.keys + listOf(
            "таймер", "таймера", "на", "поставь", "заведи", "засеки", "минут", "минуту", "минуты", "секунд", "секунду", "секунды",
            "час", "часа", "часов", "полчаса", "полминуты", "осталось", "сколько", "который", "времени", "время", "какое", "сейчас",
            "погода", "погоду", "погоды", "погодой", "прогноз", "какая", "завтра", "сегодня", "что", "с", "есть", "мне", "меня", "у",
            "повтори", "повтори-ка", "еще", "раз", "записку", "записка", "сообщение", "последнюю", "последнее", "проиграй",
            "для", "не", "надо",
        )).distinct()

    private const val MAX_SECONDS = 3 * 3600
    private val STOP = setOf("стоп", "хватит", "отмена", "отмени", "замолчи", "тихо", "выключи", "останови", "остановить", "отключи", "сбрось")
    private val NOTE_VERBS = setOf("передай", "передайте", "скажи", "скажите", "запиши", "запишите", "оставь", "оставьте", "отправь", "отправьте", "напиши", "напишите")
    private val NUMBERS: Map<String, Double> = mapOf(
        "один" to 1.0, "одна" to 1.0, "одну" to 1.0, "два" to 2.0, "две" to 2.0, "три" to 3.0, "четыре" to 4.0, "пять" to 5.0,
        "шесть" to 6.0, "семь" to 7.0, "восемь" to 8.0, "девять" to 9.0, "десять" to 10.0, "одиннадцать" to 11.0, "двенадцать" to 12.0,
        "тринадцать" to 13.0, "четырнадцать" to 14.0, "пятнадцать" to 15.0, "шестнадцать" to 16.0, "семнадцать" to 17.0,
        "восемнадцать" to 18.0, "девятнадцать" to 19.0, "двадцать" to 20.0, "тридцать" to 30.0, "сорок" to 40.0, "пятьдесят" to 50.0,
        "шестьдесят" to 60.0, "девяносто" to 90.0, "полтора" to 1.5, "полторы" to 1.5,
    )
}
