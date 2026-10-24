package dev.hram.androsaur.audio

import android.content.Context
import dev.hram.androsaur.audio.microwakeword.MicroWakeWord
import dev.hram.androsaur.audio.wake.WakeWordEngine
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A microWakeWord model: its `<name>.tflite` and ESPHome-style `<name>.json` live in `assets/wakeword/`. */
class MicroWakeWordConfig(
    val model: String,
    val wakeWord: String,
    val probabilityCutoff: Float,
    val featureStepSizeMs: Int,
    val slidingWindowSize: Int,
) {
    companion object {
        private const val DIR = "wakeword"

        fun names(context: Context): List<String> =
            (context.assets.list(DIR) ?: emptyArray()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

        fun load(context: Context, model: String): MicroWakeWordConfig {
            val json = JSONObject(context.assets.open("$DIR/$model.json").bufferedReader().use { it.readText() })
            val micro = json.getJSONObject("micro")
            return MicroWakeWordConfig(
                model = json.getString("model"),
                wakeWord = json.getString("wake_word"),
                probabilityCutoff = micro.getDouble("probability_cutoff").toFloat(),
                featureStepSizeMs = micro.getInt("feature_step_size"),
                slidingWindowSize = micro.getInt("sliding_window_size"),
            )
        }

        fun modelBuffer(context: Context, config: MicroWakeWordConfig): ByteBuffer {
            val bytes = context.assets.open("$DIR/${config.model}").use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        }
    }
}

class MicroWakeWordEngine(private val config: MicroWakeWordConfig, modelBuffer: ByteBuffer) : WakeWordEngine {
    override val name = "microwakeword:${config.wakeWord}"
    override val sampleRate = 16_000
    override val frameLength = 512
    private val detector = MicroWakeWord(modelBuffer, config.featureStepSizeMs, config.probabilityCutoff, config.slidingWindowSize)

    override fun process(frame: ShortArray): Boolean {
        if (!detector.processAudio(frame)) return false
        detector.reset()
        return true
    }

    override fun close() = detector.close()
}
