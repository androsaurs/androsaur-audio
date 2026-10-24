package dev.hram.androsaur.audio.speech

/** Time to make a phrase and how long the sound is; both in milliseconds. */
class SpeechTiming(val synthMs: Long, val audioMs: Long, val firstAudioMs: Long = 0) {
    /** Real-time factor: below 1 the phone makes speech faster than it plays. */
    val rtf: Double get() = if (audioMs <= 0) 0.0 else synthMs.toDouble() / audioMs
}

class BenchRow(val phrase: String, val timing: SpeechTiming)

/** One engine over the benchmark phrases. */
class BenchSummary(val rows: List<BenchRow>) {
    val totalSynthMs: Long get() = rows.sumOf { it.timing.synthMs }
    val totalAudioMs: Long get() = rows.sumOf { it.timing.audioMs }
    /** Over all phrases together, so a long phrase counts more than a short one. */
    val rtf: Double get() = if (totalAudioMs <= 0) 0.0 else totalSynthMs.toDouble() / totalAudioMs
    val worstRtf: Double get() = rows.maxOfOrNull { it.timing.rtf } ?: 0.0
    /** The longest wait before the first sound among the phrases that report it. */
    val worstFirstAudioMs: Long get() = rows.maxOfOrNull { it.timing.firstAudioMs } ?: 0
}

/** The same ten phrases for every engine, so voices can be compared by ear and by speed. Short to long, with numbers and the names the system uses. */
object BenchPhrases {
    val ALL = listOf(
        "Привет!",
        "Да, слушаю.",
        "Я Андрозавр, ваш домашний помощник.",
        "Сейчас двадцать три часа сорок пять минут.",
        "За окном плюс восемнадцать градусов, к вечеру ожидается дождь.",
        "Женя, вы оставили свет в коридоре. Выключить?",
        "Не расслышал. Повторите, пожалуйста, ещё раз чуть громче.",
        "Напоминаю, что завтра в девять утра у вас встреча, а в одиннадцать звонок врачу.",
        "Подставка повернулась влево на тридцать градусов, камера нашла лицо и узнала Катю.",
        "Раз, два, три, четыре, пять. Вышел зайчик погулять. Вдруг охотник выбегает, прямо в зайчика стреляет.",
    )
}

/** Length of a PCM WAV file from its first bytes and its size; null when it is not a plain WAV. */
object WavInfo {
    fun durationMs(header: ByteArray, fileSize: Long): Long? {
        if (header.size < 44) return null
        fun text(at: Int) = header.copyOfRange(at, at + 4).decodeToString()
        fun int(at: Int) = (header[at].toInt() and 0xff) or ((header[at + 1].toInt() and 0xff) shl 8) or
            ((header[at + 2].toInt() and 0xff) shl 16) or ((header[at + 3].toInt() and 0xff) shl 24)
        fun short(at: Int) = (header[at].toInt() and 0xff) or ((header[at + 1].toInt() and 0xff) shl 8)
        if (text(0) != "RIFF" || text(8) != "WAVE" || text(12) != "fmt ") return null
        val channels = short(22); val rate = int(24); val bits = short(34)
        if (channels <= 0 || rate <= 0 || bits <= 0) return null
        val bytesPerSecond = rate.toLong() * channels * (bits / 8)
        return (fileSize - 44).coerceAtLeast(0) * 1000 / bytesPerSecond
    }
}
