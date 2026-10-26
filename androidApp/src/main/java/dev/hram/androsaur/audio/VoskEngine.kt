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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val make = {
        if (grammar) Recognizer(model, sampleRate.toFloat(), JSONArray(WakePhrase.grammarPhrases(phrase) + "[unk]").toString())
        else Recognizer(model, sampleRate.toFloat())
    }
    private var recognizer = make()
    /** The next recognizer, built in the background so the microphone thread never waits for it. */
    @Volatile private var spare: Recognizer? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vosk-spare") }

    init { prepare() }

    override fun process(frame: ShortArray): Boolean {
        val final = recognizer.acceptWaveForm(frame, frame.size)
        val json = JSONObject(if (final) recognizer.result else recognizer.partialResult)
        val text = json.optString(if (final) "text" else "partial")
        if (final && text.isNotEmpty()) onUtterance(text)
        val hit = matcher.matches(text)
        if (hit) lastHeard = text
        if (hit || (final && text.isNotEmpty())) renew()
        return hit
    }

    /**
     * A clean recognizer for the next utterance. Vosk adapts to the voice it has been hearing, and reset() keeps that: after a
     * grown-up talked, a child's «железяка» right after was missed (2 of 5 in one stream, 5 of 5 with a new recognizer per
     * utterance, no false wakes on recorded background talk). Without a spare ready yet, it falls back to reset().
     */
    private fun renew() {
        val next = spare
        if (next == null) { recognizer.reset(); return }
        spare = null
        val old = recognizer
        recognizer = next
        worker.execute { old.close() }
        prepare()
    }

    private fun prepare() = runCatching { worker.execute { if (spare == null) spare = make() } }

    override fun close() {
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
        spare?.close()
        recognizer.close()
    }
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
