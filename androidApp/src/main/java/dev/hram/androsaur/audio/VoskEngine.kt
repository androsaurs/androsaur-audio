package dev.hram.androsaur.audio

import android.content.Context
import dev.hram.androsaur.audio.wake.PhraseMatcher
import dev.hram.androsaur.audio.wake.WakePhrase
import dev.hram.androsaur.audio.wake.WakeWordEngine
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Speech recognizer plus a text match for the wake phrase. With [grammar] the recognizer is limited
 * to the phrase and "[unk]": cheaper, but it forces anything that starts like the phrase into it.
 * Without it the whole vocabulary is recognized and only real "привет петя" matches.
 * The model is shared between engine instances; [VoskModelStore] owns it.
 */
class VoskEngine(
    model: Model,
    grammar: Boolean,
    /** The wake phrase in [WakePhrase] syntax. */
    phrase: String = WakePhrase.DEFAULT,
    /** Called with every finished utterance, for tuning; the caller decides what to keep. */
    private val onUtterance: (String) -> Unit = {},
) : WakeWordEngine {
    override val name = if (grammar) "vosk-grammar" else "vosk"
    override val sampleRate = 16_000
    override val frameLength = 512
    private val matcher = PhraseMatcher(phrase)
    @Volatile override var lastHeard: String? = null; private set
    private val recognizer =
        if (grammar) Recognizer(model, sampleRate.toFloat(), JSONArray(WakePhrase.grammarPhrases(phrase) + "[unk]").toString())
        else Recognizer(model, sampleRate.toFloat())

    override fun process(frame: ShortArray): Boolean {
        val final = recognizer.acceptWaveForm(frame, frame.size)
        val json = JSONObject(if (final) recognizer.result else recognizer.partialResult)
        val text = json.optString(if (final) "text" else "partial")
        if (final && text.isNotEmpty()) onUtterance(text)
        if (!matcher.matches(text)) return false
        lastHeard = text
        recognizer.reset()
        return true
    }

    override fun close() = recognizer.close()
}

/** Unpacks the model zip from assets on first use and keeps one loaded [Model] for the service's lifetime. */
class VoskModelStore(private val context: Context) {
    private var model: Model? = null

    @Synchronized fun model(): Model = model ?: Model(ensureUnpacked().path).also { model = it }

    @Synchronized fun close() { model?.close(); model = null }

    private fun ensureUnpacked(): File {
        val root = File(context.filesDir, "vosk")
        val modelDir = File(root, MODEL_DIR)
        val marker = File(root, "$MODEL_DIR.complete")
        if (marker.exists() && modelDir.isDirectory) return modelDir
        root.deleteRecursively(); root.mkdirs()
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(context.assets.open(MODEL_ZIP).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name)
                require(target.canonicalPath.startsWith(rootPath)) { "Unsafe path in model archive" }
                if (entry.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
            }
        }
        require(modelDir.isDirectory) { "Model archive has no $MODEL_DIR" }
        marker.writeText("ok")
        return modelDir
    }

    companion object {
        const val MODEL_DIR = "vosk-model-small-ru-0.22"
        const val MODEL_ZIP = "$MODEL_DIR.zip"
    }
}
