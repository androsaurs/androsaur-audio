package dev.hram.androsaur.audio

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import dev.hram.androsaur.audio.speech.SpeakerSpec

/** Builds the speech engine for a setting. Creating one loads a voice (seconds): call off the main thread. */
class SpeakerFactory(private val context: Context) {
    fun create(spec: SpeakerSpec, threads: Int): Speaker = when (spec) {
        is SpeakerSpec.Piper -> PiperSpeaker(context, spec.voice, threads)
        is SpeakerSpec.System -> SystemSpeaker(context, spec.engine)
    }

    /** Engines offered to the user, as setting strings: the Piper voices that are in the app and the speech services installed on the phone. */
    fun available(): List<String> {
        val piper = SpeakerSpec.VOICES.filter { voice -> context.assets.list("tts/voices/$voice")?.contains("model.onnx") == true }
            .map { SpeakerSpec.Piper(it).id }
        val installed = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }.distinct().map { SpeakerSpec.System(it).id }
        return piper + SpeakerSpec.System(null).id + installed
    }
}
