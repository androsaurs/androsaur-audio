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
        factory.close()
        super.onDestroy()
    }

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
                    // Only utterances containing a start of a phrase word are kept: enough to tune the match without logging speech.
                    if (keywords.any { it in text }) record("heard", "text" to text)
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
                ).also { it.start() }
                if (ready != null) record("engine_ready", "engine" to wanted.id, "loadMs" to tookMs.toString())
            }
        }, "engine-load").start()
    }

    private fun onWake(engineId: String, heard: String? = null, levelDbfs: Double? = null) {
        val data = mutableListOf("engine" to engineId)
        heard?.let { data += "heard" to it }
        levelDbfs?.let { data += "levelDbfs" to (Math.round(it * 10) / 10.0).toString() }
        record("wake_word", *data.toTypedArray())
        lastWakeText = listOfNotNull(engineId, heard).joinToString(" · ")
        wakes.incrementAndGet()
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
                    .put("frames", mic.frames).put("silent", mic.silent))
            }
            json.put("engine", engineJson)
        }
        if (lastWakeAt != 0L) json.put("sinceLastWakeMs", SystemClock.elapsedRealtime() - lastWakeAt)
        error?.let { json.put("error", it) }
        return json
    }

    companion object {
        private const val CHANNEL = "audio"
        private const val KEY_ENGINE = "engine"
        private const val KEY_PHRASE = "phrase"
        private const val TAG = "AndrosaurAudio"
    }
}
