package dev.hram.androsaur.audio

import dev.hram.androsaur.audio.speech.SpeechTiming

/** What the mouth on the screen should do now; written by the speaker, read by the screens and the API. */
class Mouth {
    @Volatile var speaking = false
    /** 0 closed .. 1 open. */
    @Volatile var level = 0f
    /** True when the speaker cannot see its sound (system speech) and the level is only a pulse while it talks. */
    @Volatile var approximate = false
}

/** A speech engine. Calls come one at a time from one thread, except [stop], which may come from any. */
interface Speaker : AutoCloseable {
    val id: String
    val name: String
    /** How long creating the engine took (loading the voice), in milliseconds. */
    val loadMs: Long

    /** Speaks and returns when the sound has ended or [stop] was called. The timing is the whole call: [SpeechTiming.firstAudioMs] is the wait before sound. */
    fun speak(text: String, speed: Float, mouth: Mouth): SpeechTiming

    /** Makes the sound without playing it, to measure how fast the engine is. */
    fun measure(text: String, speed: Float): SpeechTiming

    /** Cuts the current [speak] short. */
    fun stop()
}
