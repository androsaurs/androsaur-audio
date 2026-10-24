package dev.hram.androsaur.audio.wake

/** Which wake-word engine to run; the string form is what settings and the API carry. */
sealed class EngineSpec(val id: String) {
    data object None : EngineSpec("none")
    /** Free recognition over the whole vocabulary; the phrase is looked for in the text. */
    data object Vosk : EngineSpec("vosk")
    /** Recognition limited to the wake phrase by a grammar: cheaper, but any speech starting like it matches. */
    data object VoskGrammar : EngineSpec("vosk-grammar")
    /** A microWakeWord model, named by its asset (`okay_nabu` for `okay_nabu.tflite` + `okay_nabu.json`). */
    class MicroWakeWord(val model: String) : EngineSpec("mww:$model")

    override fun equals(other: Any?) = other is EngineSpec && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        private val modelName = Regex("[a-z0-9_]{1,60}")

        fun parse(id: String): EngineSpec? = when {
            id == "none" -> None
            id == "vosk" -> Vosk
            id == "vosk-grammar" -> VoskGrammar
            id.startsWith("mww:") -> id.removePrefix("mww:").takeIf { modelName.matches(it) }?.let { MicroWakeWord(it) }
            else -> null
        }
    }
}
