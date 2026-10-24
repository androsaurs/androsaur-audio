package dev.hram.androsaur.audio.speech

/** Which speech engine to use; the string form is what settings and the API carry. */
sealed class SpeakerSpec(val id: String) {
    /** A Piper voice run by sherpa-onnx on the phone; [voice] names its folder in the assets (`denis`). */
    class Piper(val voice: String) : SpeakerSpec("piper:$voice")
    /** Android's own speech service: the default engine, or the installed one named by its package (RHVoice, Google). */
    class System(val engine: String?) : SpeakerSpec(if (engine == null) "system" else "system:$engine")

    override fun equals(other: Any?) = other is SpeakerSpec && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        /** The Russian Piper voices that `scripts/fetch-tts.sh` puts into the app. */
        val VOICES = listOf("denis", "dmitri", "irina", "ruslan")
        val DEFAULT: SpeakerSpec = Piper("denis")
        private val packageName = Regex("[A-Za-z0-9_.]{1,120}")

        fun parse(id: String): SpeakerSpec? = when {
            id == "system" -> System(null)
            id.startsWith("system:") -> id.removePrefix("system:").takeIf { packageName.matches(it) }?.let { System(it) }
            id.startsWith("piper:") -> id.removePrefix("piper:").takeIf { it in VOICES }?.let { Piper(it) }
            else -> null
        }
    }
}
