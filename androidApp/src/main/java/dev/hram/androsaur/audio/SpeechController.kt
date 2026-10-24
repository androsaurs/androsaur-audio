package dev.hram.androsaur.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import dev.hram.androsaur.audio.speech.BenchPhrases
import dev.hram.androsaur.audio.speech.BenchRow
import dev.hram.androsaur.audio.speech.BenchSummary
import dev.hram.androsaur.audio.speech.SpeakerSpec
import dev.hram.androsaur.audio.speech.SpeechTiming
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Speech of the assistant: keeps the chosen [Speaker], says phrases one at a time, mutes the microphone while it talks (so it
 * does not wake itself with its own voice) and measures the engines against the same ten phrases.
 */
class SpeechController(
    context: Context,
    private val prefs: SharedPreferences,
    private val record: (String, Array<Pair<String, String>>) -> Unit,
) {
    private val factory = SpeakerFactory(context)
    val mouth = Mouth()
    /** Elapsed-realtime time until which the microphone engine hears silence; the maximum while speaking. */
    @Volatile var micMutedUntil = 0L; private set

    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "speech") }
    private var generation = 0
    private var speaker: Speaker? = null
    private var spec: SpeakerSpec = SpeakerSpec.parse(prefs.getString(KEY_SPEAKER, null) ?: "") ?: SpeakerSpec.DEFAULT
    private var state = "none"
    private var error: String? = null
    private var loadMs = 0L
    private var closed = false
    @Volatile private var last: JSONObject? = null

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED)
        private set(value) { prefs.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply() }
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 2).coerceIn(1, 4)
        private set(value) { prefs.edit().putInt(KEY_THREADS, value.coerceIn(1, 4)).apply() }

    fun available(): List<String> = factory.available()

    /** Loads the remembered engine; called when the service starts. */
    fun start() = load(spec)

    /** Changes the engine, the speed, the number of threads, or all; validated before anything is stored. Returns an error code or null. */
    fun configure(engineId: String?, newSpeed: Float?, newThreads: Int?): String? {
        val wanted = engineId?.let { SpeakerSpec.parse(it) ?: return "unknown_speaker" }
        if (wanted != null && wanted.id !in available()) return "speaker_not_available"
        if (newSpeed != null && newSpeed !in MIN_SPEED..MAX_SPEED) return "invalid_speed"
        if (newThreads != null && newThreads !in 1..4) return "invalid_threads"
        newSpeed?.let { speed = it }
        val threadsChanged = newThreads != null && newThreads != threads
        newThreads?.let { threads = it }
        if (wanted != null) prefs.edit().putString(KEY_SPEAKER, wanted.id).apply()
        // Threads are fixed when the voice is loaded, so changing them loads it again.
        if (wanted != null || (threadsChanged && spec is SpeakerSpec.Piper)) load(wanted ?: spec)
        return null
    }

    private fun load(wanted: SpeakerSpec) {
        val mine = synchronized(lock) {
            if (closed) return
            speaker?.stop()
            spec = wanted; state = "loading"; error = null
            ++generation
        }
        exec.execute {
            val old = synchronized(lock) { if (mine != generation) return@execute; speaker.also { speaker = null } }
            old?.close()
            val began = SystemClock.elapsedRealtime()
            val built = runCatching { factory.create(wanted, threads) }
            synchronized(lock) {
                if (mine != generation || closed) { built.getOrNull()?.close(); return@synchronized }
                built.onSuccess { speaker = it; state = "ready"; loadMs = it.loadMs; record("speaker_ready", arrayOf("speaker" to wanted.id, "loadMs" to it.loadMs.toString())) }
                    .onFailure {
                        state = "failed"; error = "${it.javaClass.simpleName}: ${it.message}".take(200)
                        record("speaker_failed", arrayOf("speaker" to wanted.id, "reason" to (error ?: "")))
                    }
                if (built.isFailure) loadMs = SystemClock.elapsedRealtime() - began
            }
        }
    }

    /** Says the text; it replaces what is being said now. Returns an error code, or null when speaking has started. */
    fun speak(text: String, requestedSpeed: Float? = null): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "empty_text"
        if (clean.length > MAX_CHARS) return "text_too_long"
        if (requestedSpeed != null && requestedSpeed !in MIN_SPEED..MAX_SPEED) return "invalid_speed"
        val current = synchronized(lock) { if (state != "ready") return "speaker_not_ready"; speaker }
        current?.stop()
        val usedSpeed = requestedSpeed ?: speed
        exec.execute {
            val sp = synchronized(lock) { if (state == "ready") speaker else null } ?: return@execute
            say(sp, clean, usedSpeed)
        }
        return null
    }

    fun stop() { synchronized(lock) { speaker }?.stop() }

    /** Says the text and returns when it has been said (or failed); for callers that go on to other sound. Error code or null. */
    fun speakAndWait(text: String, requestedSpeed: Float? = null): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "empty_text"
        val current = synchronized(lock) { if (state != "ready") return "speaker_not_ready"; speaker }
        current?.stop()
        val usedSpeed = requestedSpeed ?: speed
        val done = exec.submit<Boolean> {
            val sp = synchronized(lock) { if (state == "ready") speaker else null }
            sp != null && say(sp, clean, usedSpeed) != null
        }
        return try { if (done.get()) null else "speech_failed" } catch (e: Exception) { "speech_failed" }
    }

    /** Speaks with a given engine and keeps the microphone deaf meanwhile. */
    private fun say(sp: Speaker, text: String, usedSpeed: Float): SpeechTiming? {
        micMutedUntil = Long.MAX_VALUE
        mouth.speaking = true
        record("speech_started", arrayOf("speaker" to sp.id, "chars" to text.length.toString()))
        try {
            val timing = sp.speak(text, usedSpeed, mouth)
            record("speech_finished", arrayOf("speaker" to sp.id, "firstAudioMs" to timing.firstAudioMs.toString(), "audioMs" to timing.audioMs.toString()))
            last = JSONObject().put("speaker", sp.id).put("chars", text.length).put("firstAudioMs", timing.firstAudioMs).put("audioMs", timing.audioMs)
                .put("synthMs", timing.synthMs).put("speed", usedSpeed.toDouble())
            return timing
        } catch (e: Exception) {
            record("speech_failed", arrayOf("speaker" to sp.id, "reason" to "${e.javaClass.simpleName}: ${e.message}".take(160)))
            return null
        } finally {
            mouth.speaking = false; mouth.level = 0f
            micMutedUntil = SystemClock.elapsedRealtime() + MUTE_TAIL_MS
        }
    }

    /** What is said and how wide the mouth is right now; cheap, for fast polling. */
    fun live(): JSONObject = JSONObject().put("speaking", mouth.speaking).put("mouth", Math.round(mouth.level * 100) / 100.0)
        .put("approximate", mouth.approximate)

    fun status(): JSONObject {
        val json = JSONObject()
        synchronized(lock) {
            json.put("speaker", spec.id).put("state", state).put("loadMs", loadMs).put("name", speaker?.name ?: JSONObject.NULL)
            error?.let { json.put("error", it) }
        }
        json.put("speed", speed.toDouble()).put("threads", threads).put("available", JSONArray(available()))
            .put("speaking", mouth.speaking).put("mouth", Math.round(mouth.level * 100) / 100.0).put("approximateMouth", mouth.approximate)
            .put("micMuted", SystemClock.elapsedRealtime() < micMutedUntil)
        last?.let { json.put("last", it) }
        return json
    }

    // ---- Benchmark: the same ten phrases through each engine ----

    private class BenchEngine(val id: String, val name: String, val loadMs: Long, val warmupMs: Long, val summary: BenchSummary?, val error: String?, val played: Boolean)

    private val benchLock = Any()
    private val results = LinkedHashMap<String, BenchEngine>()
    private var benchRunning = false
    private var benchCancel = false
    private var benchProgress = ""
    private var benchSpeaker: Speaker? = null

    /** Runs the benchmark in the background over [engines] (default: all available), optionally saying every phrase aloud. */
    fun benchStart(engines: List<String>?, play: Boolean): String? {
        val wanted = engines?.map { SpeakerSpec.parse(it) ?: return "unknown_speaker" } ?: available().map { SpeakerSpec.parse(it)!! }
        if (wanted.isEmpty()) return "nothing_to_measure"
        val offered = available()
        if (wanted.any { it.id !in offered }) return "speaker_not_available"
        synchronized(benchLock) {
            if (benchRunning) return "bench_running"
            benchRunning = true; benchCancel = false; results.clear(); benchProgress = "начинаю"
        }
        val usedSpeed = speed; val usedThreads = threads
        Thread({
            try {
                for (item in wanted) {
                    if (synchronized(benchLock) { benchCancel }) break
                    benchOne(item, usedSpeed, usedThreads, play)
                }
            } finally {
                synchronized(benchLock) { benchRunning = false; benchProgress = ""; benchSpeaker = null }
            }
        }, "speech-bench").start()
        return null
    }

    fun benchCancel() { synchronized(benchLock) { benchCancel = true; benchSpeaker }?.stop() }

    private fun benchOne(item: SpeakerSpec, usedSpeed: Float, usedThreads: Int, play: Boolean) {
        fun progress(text: String) = synchronized(benchLock) { benchProgress = "${item.id}: $text" }
        progress("загрузка")
        val sp = try { factory.create(item, usedThreads) } catch (e: Throwable) {
            synchronized(benchLock) { results[item.id] = BenchEngine(item.id, item.id, 0, 0, null, "${e.javaClass.simpleName}: ${e.message}".take(160), false) }
            return
        }
        synchronized(benchLock) { benchSpeaker = sp }
        try {
            progress("прогрев")
            val warmupMs = sp.measure("Раз.", usedSpeed).synthMs
            val measured = ArrayList<BenchRow>()
            for ((i, phrase) in BenchPhrases.ALL.withIndex()) {
                if (synchronized(benchLock) { benchCancel }) return
                progress("замер ${i + 1}/${BenchPhrases.ALL.size}")
                measured += BenchRow(phrase, sp.measure(phrase, usedSpeed))
            }
            // Playing comes after the measuring, so the measurements run on a quiet processor; it adds the wait before the first sound.
            val rows = if (!play) measured else measured.mapIndexed { i, row ->
                if (synchronized(benchLock) { benchCancel }) return
                progress("вслух ${i + 1}/${BenchPhrases.ALL.size}")
                val said = say(sp, row.phrase, usedSpeed)
                BenchRow(row.phrase, SpeechTiming(row.timing.synthMs, row.timing.audioMs, said?.firstAudioMs ?: 0))
            }
            synchronized(benchLock) { results[item.id] = BenchEngine(item.id, sp.name, sp.loadMs, warmupMs, BenchSummary(rows), null, play) }
        } catch (e: Throwable) {
            synchronized(benchLock) { results[item.id] = BenchEngine(item.id, sp.name, sp.loadMs, 0, null, "${e.javaClass.simpleName}: ${e.message}".take(160), play) }
        } finally {
            synchronized(benchLock) { benchSpeaker = null }
            sp.close()
        }
    }

    fun benchJson(): JSONObject = synchronized(benchLock) {
        JSONObject().put("running", benchRunning).put("progress", benchProgress).put("speed", speed.toDouble()).put("threads", threads)
            .put("phrases", JSONArray(BenchPhrases.ALL))
            .put("engines", JSONArray(results.values.map { e ->
                val json = JSONObject().put("speaker", e.id).put("name", e.name).put("loadMs", e.loadMs).put("warmupMs", e.warmupMs).put("played", e.played)
                e.error?.let { json.put("error", it) }
                e.summary?.let { s ->
                    json.put("rtf", round2(s.rtf)).put("worstRtf", round2(s.worstRtf)).put("totalSynthMs", s.totalSynthMs).put("totalAudioMs", s.totalAudioMs)
                    if (e.played) json.put("worstFirstAudioMs", s.worstFirstAudioMs)
                    json.put("rows", JSONArray(s.rows.map { r ->
                        JSONObject().put("phrase", r.phrase).put("chars", r.phrase.length).put("synthMs", r.timing.synthMs).put("audioMs", r.timing.audioMs)
                            .put("rtf", round2(r.timing.rtf)).apply { if (e.played) put("firstAudioMs", r.timing.firstAudioMs) }
                    }))
                }
                json
            }))
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    fun close() {
        benchCancel()
        val old = synchronized(lock) { closed = true; generation++; speaker.also { speaker = null } }
        old?.stop()
        exec.execute { old?.close() }
        exec.shutdown()
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
        const val MAX_CHARS = 600
        /** The microphone stays deaf this long after the last sound, for the tail of the room echo. */
        const val MUTE_TAIL_MS = 600L
        private const val KEY_SPEAKER = "speaker"
        private const val KEY_SPEED = "speech_speed"
        private const val KEY_THREADS = "speech_threads"
    }
}
