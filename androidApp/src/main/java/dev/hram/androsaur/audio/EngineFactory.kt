package dev.hram.androsaur.audio

import android.content.Context
import dev.hram.androsaur.audio.wake.EngineSpec
import dev.hram.androsaur.audio.wake.NoWakeWordEngine
import dev.hram.androsaur.audio.wake.WakeWordEngine

/** Builds the engine for a setting. Creation can take seconds (model unpacking and loading): call off the main thread. */
class EngineFactory(private val context: Context) {
    private val vosk = VoskModelStore(context)

    fun create(spec: EngineSpec, phrase: String, onUtterance: (String) -> Unit = {}): WakeWordEngine = when (spec) {
        EngineSpec.None -> NoWakeWordEngine()
        EngineSpec.Vosk -> VoskEngine(vosk.model(), grammar = false, phrase = phrase, onUtterance = onUtterance)
        EngineSpec.VoskGrammar -> VoskEngine(vosk.model(), grammar = true, phrase = phrase, onUtterance = onUtterance)
        is EngineSpec.MicroWakeWord -> {
            val config = MicroWakeWordConfig.load(context, spec.model)
            MicroWakeWordEngine(config, MicroWakeWordConfig.modelBuffer(context, config))
        }
    }

    /** Engines offered to the user, as setting strings. */
    fun available(): List<String> =
        listOf(EngineSpec.None.id, EngineSpec.Vosk.id, EngineSpec.VoskGrammar.id) + MicroWakeWordConfig.names(context).map { EngineSpec.MicroWakeWord(it).id }

    fun close() = vosk.close()
}
