package dev.hram.androsaur.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.hram.androsaur.audio.level.AudioLevel
import dev.hram.androsaur.audio.level.SilenceDetector
import dev.hram.androsaur.audio.wake.WakeGate
import dev.hram.androsaur.audio.wake.WakeWordEngine

/** The only reader of the microphone in the Androsaur system; everything else receives events from it. */
class MicrophoneListener(
    private val engine: WakeWordEngine,
    private val onWake: (heard: String?, levelDbfs: Double) -> Unit,
    private val onSilence: (silent: Boolean) -> Unit,
    private val onError: (code: String) -> Unit,
    /** Elapsed-realtime time until which the engine must not hear the microphone (the assistant is speaking and would wake itself). */
    private val mutedUntil: () -> Long = { 0L },
) {
    @Volatile var levelDbfs = AudioLevel.FLOOR_DBFS; private set
    @Volatile var frames = 0L; private set
    @Volatile var silent = false; private set
    /** Moving average of the time the engine spends on one frame, in milliseconds. */
    @Volatile var engineMsPerFrame = 0.0; private set
    /** Frame duration, for comparing engine cost against real time. */
    val frameMs: Double get() = engine.frameLength * 1000.0 / engine.sampleRate
    @Volatile private var stopped = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before creating the listener
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(engine.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { onError("microphone_unsupported"); return }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, engine.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, engine.frameLength * 2 * 4))
        } catch (_: SecurityException) { onError("microphone_permission_denied"); return }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); onError("microphone_unavailable"); return }
        thread = Thread({ loop(record) }, "audio-capture").also { it.start() }
    }

    fun stop() { stopped = true; thread?.join(1000); thread = null }

    private fun loop(record: AudioRecord) {
        val frame = ShortArray(engine.frameLength)
        val silence = ShortArray(engine.frameLength)
        val gate = WakeGate(cooldownMs = 2000)
        // About two seconds of exact zeros means another app took the microphone.
        val detector = SilenceDetector(thresholdFrames = 2 * engine.sampleRate / engine.frameLength)
        try {
            record.startRecording()
            while (!stopped) {
                val read = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read < 0) { onError("microphone_read_failed:$read"); break }
                if (read < frame.size) continue
                frames++
                levelDbfs = AudioLevel.dbfs(frame)
                val wasSilent = detector.silent
                detector.onFrame(AudioLevel.isDigitalSilence(frame))
                if (detector.silent != wasSilent) { silent = detector.silent; onSilence(silent) }
                val began = System.nanoTime()
                // While muted the engine gets silence instead, so what it heard before the speech ends cleanly and does not trigger afterwards.
                val muted = SystemClock.elapsedRealtime() < mutedUntil()
                val detected = engine.process(if (muted) silence else frame) && !muted
                engineMsPerFrame += ((System.nanoTime() - began) / 1e6 - engineMsPerFrame) * 0.05
                if (detected && gate.accept(SystemClock.elapsedRealtime())) onWake(engine.lastHeard, levelDbfs)
            }
        } catch (e: IllegalStateException) {
            onError("microphone_stopped:${e.javaClass.simpleName}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
