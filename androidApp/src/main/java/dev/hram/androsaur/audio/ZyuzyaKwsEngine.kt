package dev.hram.androsaur.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.hram.androsaur.audio.wake.WakeWordEngine
import dev.hram.androsaur.audio.wake.ZyuzyaProfile

/** Finds the sound of «Зюзя» using Russian subword tokens, without Vosk's word lexicon. */
class ZyuzyaKwsEngine(context: Context, phrase: String) : WakeWordEngine {
    init { require(ZyuzyaProfile.supports(phrase)) { "phrase_not_supported_by_zyuzya_profile" } }
    override val name = "kws:zyuzya"
    override val sampleRate = 16_000
    override val frameLength = 512
    override val listenDuringCommands = true
    @Volatile override var lastHeard: String? = null; private set
    private val samples = FloatArray(frameLength)
    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/ru/encoder.int8.onnx",
                    decoder = "kws/ru/decoder.onnx",
                    joiner = "kws/ru/joiner.int8.onnx",
                ),
                tokens = "kws/ru/tokens.txt",
                numThreads = 2,
            ),
            maxActivePaths = 8,
            keywordsFile = "kws/zyuzya.keywords",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.1f,
            numTrailingBlanks = 1,
        ),
    )
    private val stream = spotter.createStream()

    override fun process(frame: ShortArray): Boolean {
        require(frame.size == frameLength)
        for (i in frame.indices) samples[i] = frame[i] / 32768f
        stream.acceptWaveform(samples, sampleRate)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val result = spotter.getResult(stream)
            if (result.keyword.isNotEmpty()) {
                lastHeard = result.keyword
                spotter.reset(stream)
                return true
            }
        }
        return false
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
