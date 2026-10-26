package dev.hram.androsaur.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.hram.androsaur.audio.events.AudioEvent
import dev.hram.androsaur.audio.events.EventLog
import dev.hram.androsaur.audio.wake.EngineSpec
import dev.hram.androsaur.audio.wake.NoWakeWordEngine
import dev.hram.androsaur.audio.wake.WakePhrase
import dev.hram.androsaur.audio.wake.WakeWordEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AudioService : Service() {
    inner class LocalBinder : Binder() { val service: AudioService get() = this@AudioService }

    private val binder = LocalBinder()
    private val log = EventLog()
    private val bootId = UUID.randomUUID().toString()
    private val prefs by lazy { getSharedPreferences("audio", MODE_PRIVATE) }
    private val factory by lazy { EngineFactory(this) }
    private var server: PhoneServer? = null
    val speech by lazy { SpeechController(this, prefs) { type, data -> record(type, *data) } }
    val notes by lazy { NoteController(this, { microphone }, speech, { recipients.all() }) { type, data -> record(type, *data) } }
    val recipients by lazy { RecipientBook(prefs) }
    /** Who the camera sees now, pushed by the main app; a note left by voice is filed as «from» this person while the push is fresh. */
    @Volatile private var personName: String? = null
    @Volatile private var personId: String? = null
    @Volatile private var personAt = 0L
    /** What the assistant waits for or does besides speaking, for the face: `command` (listening for a command), `recording` (a note is being recorded), `note` (a note plays), or empty. */
    fun mode(): String = when {
        notes.state == "recording" -> "recording"
        notes.state == "playing" -> "note"
        commands.listening -> "command"
        else -> ""
    }
    fun setPerson(name: String?, id: String? = null) { personName = name?.trim()?.ifEmpty { null }; personId = id?.trim()?.ifEmpty { null }; personAt = SystemClock.elapsedRealtime() }
    private fun person(): String? = personName?.takeIf { SystemClock.elapsedRealtime() - personAt < PERSON_FRESH_MS }
    private fun currentPersonId(): String? = personId?.takeIf { SystemClock.elapsedRealtime() - personAt < PERSON_FRESH_MS }
    val ringer by lazy { Ringer { type, data -> record(type, *data) } }
    val commands by lazy { CommandListener({ words -> factory.commandRecognizer(words) }, { microphone }, recipients, speech, notes, ringer, ::person, ::currentPersonId,
        { type, data -> record(type, *data) }, { phrase }, { heard -> onWake(synchronized(engineLock) { spec.id }, heard) }) }
    private val nsd by lazy { NsdAdvertiser(this) }
    @Volatile private var lastWakeAt = 0L
    private val wakes = java.util.concurrent.atomic.AtomicLong()
    /** Number of detections since the service started (simulated ones included); the screen watches it to flash. */
    val wakeCount: Long get() = wakes.get()
    @Volatile var lastWakeText: String = ""; private set
    @Volatile var error: String? = null; private set

    /** Guards the engine fields below; engine loading runs outside it. */
    private val engineLock = Any()
    private var generation = 0
    private var engine: WakeWordEngine = NoWakeWordEngine()
    @Volatile private var microphone: MicrophoneListener? = null
    private var spec: EngineSpec = EngineSpec.None
    private var engineState = "loading"
    private var engineError: String? = null
    private var engineLoadMs = 0L
    private var destroyed = false

    /** The wake phrase in [WakePhrase] syntax; used by the vosk engines (microWakeWord models have a fixed phrase). */
    val phrase: String get() = prefs.getString(KEY_PHRASE, null)?.let { WakePhrase.normalize(it) } ?: WakePhrase.DEFAULT

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Androsaur Звук", NotificationManager.IMPORTANCE_LOW))
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notification)
        } catch (e: RuntimeException) {
            // Android 12+ refuses a microphone foreground service started from the background
            // (ForegroundServiceStartNotAllowedException, an IllegalStateException; on 14+ also SecurityException).
            Log.w(TAG, "cannot start as a microphone service", e)
            error = "foreground_start_refused"
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "microphone_permission_denied"
            stopSelf()
            return
        }
        val saved = EngineSpec.parse(prefs.getString(KEY_ENGINE, null) ?: "") ?: EngineSpec.None
        record("service_started", "engine" to saved.id)
        activate(saved)
        speech.start()
        server = PhoneServer(this, AccessToken.get(this)).also {
            try { it.start(); nsd.start(PhoneServer.PORT) }
            catch (e: java.io.IOException) { error = "http_port_busy"; Log.w(TAG, "cannot bind ${PhoneServer.PORT}", e) }
        }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("Androsaur Звук")
            .setContentText("Слушаю · wake-word «${WakePhrase.display(phrase).replaceFirstChar { it.uppercase() }}»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pending).setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = binder

    override fun onDestroy() {
        nsd.stop()
        server?.close()
        synchronized(engineLock) {
            destroyed = true
            generation++
            microphone?.stop(); microphone = null
            engine.close()
        }
        speech.close()
        factory.close()
        super.onDestroy()
    }

    /**
     * The microphone: Android's audio source, the amplification before the wake engine, and the recognition debug mode for
     * [debugMinutes] (0 switches it off), and the [quiet] test inside it. Source and gain restart the microphone. Returns an error code, or null.
     */
    fun setMicrophone(source: String?, wakeGain: Boolean?, debugMinutes: Int?, quiet: Boolean? = null): String? {
        if (source != null && source !in MicrophoneListener.Sources.ALL) return "unknown_source"
        if (debugMinutes != null && debugMinutes !in 0..720) return "invalid_debug_minutes"
        prefs.edit().apply {
            source?.let { putString(KEY_MIC_SOURCE, it) }
            wakeGain?.let { putBoolean(KEY_WAKE_GAIN, it) }
            debugMinutes?.let { putLong(KEY_DEBUG_UNTIL, if (it == 0) 0L else System.currentTimeMillis() + it * 60_000L) }
            quiet?.let { putBoolean(KEY_QUIET, it) }
        }.apply()
        record("microphone_set", "source" to (prefs.getString(KEY_MIC_SOURCE, null) ?: MicrophoneListener.Sources.DEFAULT),
            "wakeGain" to prefs.getBoolean(KEY_WAKE_GAIN, false).toString(), "debugMinutes" to (debugMinutes?.toString() ?: "-"), "quiet" to quietOn().toString())
        if (source != null || wakeGain != null) activate(synchronized(engineLock) { spec })
        return null
    }

    private fun debugOn(): Boolean = prefs.getLong(KEY_DEBUG_UNTIL, 0L) > System.currentTimeMillis()

    /**
     * The quiet test, only while debugging: a wake is written down (event `wake_test` and its sound) but not answered, so the
     * assistant does not stop listening for a command and every try of the phrase is heard and kept. Ends with the debug mode.
     */
    private fun quietOn(): Boolean = debugOn() && prefs.getBoolean(KEY_QUIET, false)

    private val debugDir by lazy { java.io.File(filesDir, "wake-debug").apply { mkdirs() } }

    /**
     * Keeps the sound of one utterance (debug mode only): `files/wake-debug/<time>-<wake|miss>.wav`, with the text, the loudest
     * raw level, the source and the gain in `index.json`; only the last [DEBUG_KEEP]. Returns that level.
     */
    @Synchronized private fun saveUtterance(text: String, wake: Boolean): Double {
        val mic = microphone ?: return dev.hram.androsaur.audio.level.AudioLevel.FLOOR_DBFS
        val (pcm, peak) = mic.takeUtterance()
        if (pcm.isEmpty()) return peak
        val bytes = ByteArray(pcm.size * 2).also { b -> pcm.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } }
        val name = "${System.currentTimeMillis()}-${if (wake) "wake" else "miss"}.wav"
        java.io.File(debugDir, name).writeBytes(dev.hram.androsaur.audio.notes.Wav.header(bytes.size, mic.sampleRate) + bytes)
        val index = java.io.File(debugDir, "index.json")
        val list = runCatching { JSONArray(index.readText()) }.getOrDefault(JSONArray())
        list.put(JSONObject().put("file", name).put("text", text).put("wake", wake).put("peakDbfs", Math.round(peak * 10) / 10.0)
            .put("source", mic.source).put("wakeGain", mic.wakeGain).put("phrase", phrase))
        val kept = JSONArray()
        val from = maxOf(0, list.length() - DEBUG_KEEP)
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (i < from) java.io.File(debugDir, o.getString("file")).delete() else kept.put(o)
        }
        index.writeText(kept.toString())
        return Math.round(peak * 10) / 10.0
    }

    /** Throws away everything the debug mode kept. */
    fun clearDebug(): Int = (debugDir.listFiles() ?: emptyArray()).count { it.delete() }

    /** What the debug mode kept: text, level, source, gain and file of each utterance. */
    fun debugIndex(): JSONArray = runCatching { JSONArray(java.io.File(debugDir, "index.json").readText()) }.getOrDefault(JSONArray())

    /** Engines the user can choose, as setting strings. */
    fun availableEngines(): List<String> = factory.available()

    /** Switches the wake-word engine and remembers the choice. Returns an error code, or null when loading has started. */
    fun setEngine(id: String): String? = configure(id, null)

    /**
     * Changes the engine, the wake phrase, or both, validating everything before anything is stored.
     * Returns an error code, or null when the change was accepted (the engine reloads in the background).
     */
    fun configure(engineId: String?, phraseText: String?): String? {
        val wanted = engineId?.let { EngineSpec.parse(it) ?: return "unknown_engine" } ?: synchronized(engineLock) { spec }
        if (engineId != null && engineId !in availableEngines()) return "engine_not_available"
        val newPhrase = phraseText?.let { WakePhrase.normalize(it) ?: return "invalid_phrase" }
        prefs.edit().putString(KEY_ENGINE, wanted.id).apply { if (newPhrase != null) putString(KEY_PHRASE, newPhrase) }.apply()
        if (newPhrase != null) getSystemService(NotificationManager::class.java).notify(1, notification())
        activate(wanted)
        return null
    }

    private fun activate(wanted: EngineSpec) {
        val usedPhrase = phrase
        val keywords = usedPhrase.split(' ').map { it.substringBefore('|').substringBefore('~').take(4) }
        val mine = synchronized(engineLock) {
            if (destroyed) return
            microphone?.stop(); microphone = null
            engine.close(); engine = NoWakeWordEngine()
            spec = wanted; engineState = "loading"; engineError = null; error = null
            ++generation
        }
        Thread({
            val began = SystemClock.elapsedRealtime()
            val built = runCatching {
                factory.create(wanted, usedPhrase) { text ->
                    if (debugOn()) {
                        // The recognition debug mode (switched on for a while by the owner): every utterance and its sound.
                        val saved = saveUtterance(text, wake = false)
                        record("heard", "text" to text, "levelDbfs" to saved.toString(), "debug" to "true")
                    } else if (keywords.any { it in text }) {
                        // Normally only utterances containing a start of a phrase word are kept: enough to tune the match without logging speech.
                        record("heard", "text" to text)
                    }
                }
            }
            val tookMs = SystemClock.elapsedRealtime() - began
            synchronized(engineLock) {
                if (mine != generation) { built.getOrNull()?.close(); return@synchronized }
                val ready = built.getOrElse { e ->
                    Log.w(TAG, "engine ${wanted.id} failed", e)
                    engineError = "${e.javaClass.simpleName}: ${e.message}".take(200)
                    record("engine_failed", "engine" to wanted.id, "reason" to (engineError ?: ""))
                    null
                }
                engine = ready ?: NoWakeWordEngine()
                engineState = if (ready != null) "ready" else "failed"
                engineLoadMs = tookMs
                microphone = MicrophoneListener(engine,
                    onWake = { heard, level -> onWake(wanted.id, heard, level) },
                    onSilence = { silent -> record(if (silent) "microphone_silent" else "microphone_recovered") },
                    onError = { code -> error = code; record("error", "code" to code) },
                    mutedUntil = { maxOf(speech.micMutedUntil, notes.micMutedUntil, ringer.micMutedUntil) },
                    source = prefs.getString(KEY_MIC_SOURCE, null) ?: MicrophoneListener.Sources.DEFAULT,
                    wakeGain = prefs.getBoolean(KEY_WAKE_GAIN, false),
                    keepUtterances = ::debugOn,
                ).also { it.start() }
                if (ready != null) record("engine_ready", "engine" to wanted.id, "loadMs" to tookMs.toString())
            }
        }, "engine-load").start()
    }

    private fun onWake(engineId: String, heard: String? = null, levelDbfs: Double? = null) {
        val data = mutableListOf("engine" to engineId)
        heard?.let { data += "heard" to it }
        levelDbfs?.let { data += "levelDbfs" to (Math.round(it * 10) / 10.0).toString() }
        if (quietOn() && engineId != "simulated") {
            record("wake_test", *data.toTypedArray())
            saveUtterance(heard.orEmpty(), wake = true)
            return
        }
        record("wake_word", *data.toTypedArray())
        if (debugOn()) saveUtterance(heard.orEmpty(), wake = true)
        // «Петя!» while the timer rings is the natural way to silence it.
        ringer.stop("wake_word")
        lastWakeText = listOfNotNull(engineId, heard).joinToString(" · ")
        wakes.incrementAndGet()
        commands.open()
        lastWakeAt = SystemClock.elapsedRealtime()
    }

    /** Input level for the on-screen meter; cheap and lock-free. */
    fun micLevelDbfs(): Double = microphone?.levelDbfs ?: dev.hram.androsaur.audio.level.AudioLevel.FLOOR_DBFS

    fun record(type: String, vararg data: Pair<String, String>) {
        synchronized(log) { log.append(type, System.currentTimeMillis(), data.toMap()) }
    }

    fun eventsSince(afterId: Long): List<AudioEvent> = synchronized(log) { log.since(afterId) }

    /** Raises the same event as a real detection, so listeners can be tested without saying the phrase. */
    fun simulateWake() = onWake("simulated")

    fun status(): JSONObject {
        val json = JSONObject().put("bootId", bootId).put("version", BuildConfig.VERSION_NAME)
            .put("latestEventId", synchronized(log) { log.latestId }).put("phrase", phrase)
        synchronized(engineLock) {
            val mic = microphone
            json.put("listening", mic != null && error == null)
            val engineJson = JSONObject().put("id", spec.id).put("name", engine.name).put("state", engineState)
                .put("sampleRate", engine.sampleRate).put("frameLength", engine.frameLength).put("loadMs", engineLoadMs)
                .put("available", JSONArray(availableEngines()))
            engineError?.let { engineJson.put("error", it) }
            if (mic != null) {
                engineJson.put("msPerFrame", Math.round(mic.engineMsPerFrame * 100) / 100.0).put("frameMs", Math.round(mic.frameMs * 10) / 10.0)
                json.put("microphone", JSONObject().put("levelDbfs", Math.round(mic.levelDbfs * 10) / 10.0)
                    .put("frames", mic.frames).put("silent", mic.silent)
                    .put("source", mic.source).put("wakeGain", mic.wakeGain)
                    .put("debugUntilMs", prefs.getLong(KEY_DEBUG_UNTIL, 0L).takeIf { it > System.currentTimeMillis() } ?: JSONObject.NULL)
                    .put("quiet", quietOn()))
            }
            json.put("engine", engineJson)
        }
        json.put("speech", JSONObject().put("speaker", speech.status().optString("speaker")).put("state", speech.status().optString("state")).put("speaking", speech.mouth.speaking))
        json.put("notes", notes.status())
        json.put("commands", JSONObject().put("listening", commands.listening).put("recipients", recipients.all().size))
        json.put("ringing", ringer.ringing)
        if (lastWakeAt != 0L) json.put("sinceLastWakeMs", SystemClock.elapsedRealtime() - lastWakeAt)
        error?.let { json.put("error", it) }
        return json
    }

    companion object {
        private const val CHANNEL = "audio"
        private const val KEY_ENGINE = "engine"
        private const val KEY_PHRASE = "phrase"
        private const val KEY_MIC_SOURCE = "mic_source"
        private const val KEY_WAKE_GAIN = "wake_gain"
        private const val KEY_DEBUG_UNTIL = "wake_debug_until"
        private const val KEY_QUIET = "wake_quiet_test"
        private const val DEBUG_KEEP = 60
        private const val TAG = "AndrosaurAudio"
        private const val PERSON_FRESH_MS = 15_000L
    }
}
