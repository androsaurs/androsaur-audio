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
import dev.hram.androsaur.audio.wake.NoWakeWordEngine
import dev.hram.androsaur.audio.wake.WakeWordEngine
import org.json.JSONObject
import java.util.UUID

class AudioService : Service() {
    inner class LocalBinder : Binder() { val service: AudioService get() = this@AudioService }

    private val binder = LocalBinder()
    private val log = EventLog()
    private val bootId = UUID.randomUUID().toString()
    private val engine: WakeWordEngine = NoWakeWordEngine()
    private var microphone: MicrophoneListener? = null
    private var server: PhoneServer? = null
    private val nsd by lazy { NsdAdvertiser(this) }
    @Volatile private var lastWakeAt = 0L
    @Volatile var error: String? = null; private set

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Androsaur Звук", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("Androsaur Звук")
            .setContentText("Слушаю · wake-word «Привет, Петя»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pending).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notification)
        } catch (e: SecurityException) {
            // Android 14+ refuses a microphone foreground service without the permission or from the background.
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
        record("service_started", "engine" to engine.name)
        microphone = MicrophoneListener(engine,
            onWake = { record("wake_word", "engine" to engine.name); lastWakeAt = SystemClock.elapsedRealtime() },
            onSilence = { silent -> record(if (silent) "microphone_silent" else "microphone_recovered") },
            onError = { code -> error = code; record("error", "code" to code) },
        ).also { it.start() }
        server = PhoneServer(this, AccessToken.get(this)).also {
            try { it.start(); nsd.start(PhoneServer.PORT) }
            catch (e: java.io.IOException) { error = "http_port_busy"; Log.w(TAG, "cannot bind ${PhoneServer.PORT}", e) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = binder

    override fun onDestroy() {
        nsd.stop()
        server?.close()
        microphone?.stop()
        engine.close()
        super.onDestroy()
    }

    fun record(type: String, vararg data: Pair<String, String>) {
        synchronized(log) { log.append(type, System.currentTimeMillis(), data.toMap()) }
    }

    fun eventsSince(afterId: Long): List<AudioEvent> = synchronized(log) { log.since(afterId) }

    /** Raises the same event as a real detection, so listeners can be tested before an engine exists. */
    fun simulateWake() {
        record("wake_word", "engine" to "simulated")
        lastWakeAt = SystemClock.elapsedRealtime()
    }

    fun status(): JSONObject {
        val mic = microphone
        val json = JSONObject().put("bootId", bootId).put("version", BuildConfig.VERSION_NAME)
            .put("listening", mic != null && error == null)
            .put("engine", JSONObject().put("name", engine.name).put("sampleRate", engine.sampleRate).put("frameLength", engine.frameLength))
            .put("latestEventId", synchronized(log) { log.latestId })
        if (mic != null) json.put("microphone", JSONObject().put("levelDbfs", Math.round(mic.levelDbfs * 10) / 10.0)
            .put("frames", mic.frames).put("silent", mic.silent))
        if (lastWakeAt != 0L) json.put("sinceLastWakeMs", SystemClock.elapsedRealtime() - lastWakeAt)
        error?.let { json.put("error", it) }
        return json
    }

    companion object {
        private const val CHANNEL = "audio"
        private const val TAG = "AndrosaurAudio"
    }
}
