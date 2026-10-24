package dev.hram.androsaur.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import dev.hram.androsaur.audio.notes.Loudness
import dev.hram.androsaur.audio.notes.Note
import dev.hram.androsaur.audio.notes.NoteBook
import dev.hram.androsaur.audio.notes.RecordingGate
import dev.hram.androsaur.audio.notes.Recipient
import dev.hram.androsaur.audio.notes.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Voice notes: records a message after a beep, keeps it for a person, plays it back (after saying who it is from).
 * One thing at a time: recording, playing and speech exclude each other. The microphone engine is deaf meanwhile.
 */
class NoteController(
    context: Context,
    private val microphone: () -> MicrophoneListener?,
    private val speech: SpeechController,
    private val record: (String, Array<Pair<String, String>>) -> Unit,
) {
    private val store = NoteStore(context)
    @Volatile var micMutedUntil = 0L; private set
    @Volatile private var phase = "idle"
    @Volatile private var stopRequested = false
    @Volatile private var track: AudioTrack? = null
    private val lock = Any()

    /** Starts recording a note for [toText]; the beep says when to talk. Returns an error code, or null when it has started. */
    /** What the controller is doing: idle, recording or playing. */
    val state: String get() = phase

    /**
     * [beep] says when to talk with a sound; a caller that tells it in words passes false. [onDone] is called when the recording
     * ends, with null when a note was saved or the reason it was not (`no_speech`, `too_short`, `microphone_lost`, ...).
     */
    fun startRecording(toText: String, fromText: String?, maxSeconds: Int?, beep: Boolean = true, onDone: ((String?) -> Unit)? = null): String? {
        val to = Recipient.normalize(toText) ?: return "empty_recipient"
        if (to.length > MAX_NAME) return "recipient_too_long"
        val from = fromText?.trim().orEmpty().take(MAX_NAME)
        val limitMs = (maxSeconds ?: 60) * 1000L
        if (limitMs !in 3_000..MAX_RECORD_MS) return "invalid_max_seconds"
        val mic = microphone() ?: return "microphone_unavailable"
        synchronized(lock) {
            if (phase != "idle") return "busy:$phase"
            phase = "recording"; stopRequested = false
        }
        micMutedUntil = Long.MAX_VALUE
        Thread({ recordNote(mic, to, from, limitMs, beep, onDone) }, "notes-record").start()
        return null
    }

    /** Ends the recording now; what was said so far is kept. */
    fun stopRecording(): Boolean { if (phase != "recording") return false; stopRequested = true; return true }

    private fun recordNote(mic: MicrophoneListener, to: String, from: String, limitMs: Long, beep: Boolean, onDone: ((String?) -> Unit)?) {
        record("note_recording_started", arrayOf("to" to to))
        var reason: String? = null
        var peakText = ""
        var thresholdText = ""
        try {
            speech.stop()
            if (beep) beep()
            // The beep and its tail die out before the recorder listens, so they are not in the note.
            Thread.sleep(BEEP_TAIL_MS)
            val gate = RecordingGate(mic.frameMs, maxMs = limitMs)
            val pcm = ByteArrayOutputStream()
            val verdict = arrayOfNulls<RecordingGate.Verdict>(1)
            val finished = CountDownLatch(1)
            mic.tap = { frame ->
                if (verdict[0] == null) {
                    val v = gate.onFrame(Wav.level(frame))
                    val bytes = ByteArray(frame.size * 2)
                    for (i in frame.indices) { bytes[2 * i] = frame[i].toByte(); bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte() }
                    pcm.write(bytes)
                    if (v != RecordingGate.Verdict.CONTINUE) { verdict[0] = v; finished.countDown() }
                }
            }
            try {
                while (!finished.await(100, TimeUnit.MILLISECONDS)) {
                    if (stopRequested) break
                    if (microphone() !== mic) { reason = "microphone_lost"; break }
                }
            } finally { mic.tap = null }
            peakText = Math.round(gate.peakDbfs).toString(); thresholdText = Math.round(gate.thresholdDbfs).toString()
            val v = verdict[0]
            when {
                reason != null -> {}
                v == RecordingGate.Verdict.NO_SPEECH || (v == null && !gate.hasSpeech) -> reason = "no_speech"
                v == RecordingGate.Verdict.TOO_SHORT || (v == null && !gate.longEnough) -> reason = "too_short"
            }
            if (reason == null) {
                val raw = pcm.toByteArray()
                val samples = ShortArray(raw.size / 2) { ((raw[2 * it].toInt() and 0xFF) or (raw[2 * it + 1].toInt() shl 8)).toShort() }
                val loud = Loudness.process(samples, mic.sampleRate, gate.speechStartMs, gate.speechEndMs)
                val bytes = ByteArray(loud.size * 2).also { b -> loud.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } }
                val wav = Wav.header(bytes.size, mic.sampleRate) + bytes
                val note = store.add(to, from, wav, Wav.durationMs(bytes.size, mic.sampleRate))
                record("note_recorded", arrayOf("id" to note.id, "to" to to, "durationMs" to note.durationMs.toString(),
                    "end" to (if (v == RecordingGate.Verdict.LIMIT) "limit" else if (v == null) "stopped" else "pause")))
            }
        } catch (e: Exception) {
            reason = "${e.javaClass.simpleName}: ${e.message}".take(120)
        } finally {
            mic.tap = null
            if (reason != null) record("note_record_failed", arrayOf("reason" to reason, "peakDbfs" to peakText, "thresholdDbfs" to thresholdText))
            micMutedUntil = SystemClock.elapsedRealtime() + SpeechController.MUTE_TAIL_MS
            phase = "idle"
            onDone?.let { runCatching { it(reason) } }
        }
    }

    /**
     * Plays a note: the one with [id], or the oldest waiting for [toText]. With [announce] the assistant first says who it is from.
     * Returns an error code, or null when it has started; a note that was played is marked heard.
     */
    fun play(id: String?, toText: String?, announce: Boolean): String? {
        val note = when {
            id != null -> store.get(id) ?: return "not_found"
            toText != null -> NoteBook.pendingFor(store.all(), Recipient.normalize(toText) ?: return "empty_recipient").firstOrNull() ?: return "no_notes"
            else -> return "nothing_to_play"
        }
        synchronized(lock) {
            if (phase != "idle") return "busy:$phase"
            phase = "playing"; stopRequested = false
        }
        micMutedUntil = Long.MAX_VALUE
        Thread({ playNote(note, announce) }, "notes-play").start()
        return null
    }

    fun stopPlaying() { if (phase == "playing") { stopRequested = true; track?.let { runCatching { it.pause(); it.flush() } } } }

    private fun playNote(note: Note, announce: Boolean) {
        record("note_playing", arrayOf("id" to note.id, "to" to note.to, "announce" to announce.toString()))
        var reason: String? = null
        try {
            if (announce) {
                val text = if (note.from.isEmpty()) "Тебе сообщение." else "Тебе сообщение от ${note.from}."
                // A speech failure should not lose the note: play it anyway.
                speech.speakAndWait(text)
                micMutedUntil = Long.MAX_VALUE
                Thread.sleep(150)
            }
            if (!stopRequested) {
                val bytes = store.file(note.id).readBytes()
                require(bytes.size > 44) { "empty note" }
                val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16)
                playPcm(bytes, 44, rate)
                if (!stopRequested) store.markHeard(note.id)
                record("note_played", arrayOf("id" to note.id, "to" to note.to, "complete" to (!stopRequested).toString()))
            }
        } catch (e: Exception) {
            reason = "${e.javaClass.simpleName}: ${e.message}".take(120)
            record("note_play_failed", arrayOf("id" to note.id, "reason" to reason))
        } finally {
            micMutedUntil = SystemClock.elapsedRealtime() + SpeechController.MUTE_TAIL_MS
            phase = "idle"
        }
    }

    private fun beep() {
        val rate = 16000
        val n = rate * BEEP_MS / 1000
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val fade = minOf(1.0, minOf(i, n - i) / (rate * 0.01))
            val v = (sin(2 * PI * 880 * i / rate) * 0.8 * fade * Short.MAX_VALUE).toInt().toShort()
            pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v.toInt() shr 8).toByte()
        }
        playPcm(pcm, 0, rate)
    }

    /** Plays 16-bit mono PCM from [offset] to the end and returns when the sound has ended (or the note was stopped). */
    private fun playPcm(pcm: ByteArray, offset: Int, rate: Int) {
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBytes, rate)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        try {
            t.play()
            // The speaker amplifier of many phones needs a moment to wake up and swallows the start of the sound: begin with silence.
            val lead = ByteArray(rate * LEAD_MS / 1000 * 2)
            var led = 0
            while (led < lead.size && !stopRequested) { val w = t.write(lead, led, minOf(4096, lead.size - led)); if (w < 0) break; led += w }
            var at = offset
            while (at < pcm.size && !stopRequested) {
                val wrote = t.write(pcm, at, minOf(4096, pcm.size - at))
                if (wrote < 0) throw IllegalStateException("audio write failed: $wrote")
                at += wrote
            }
            // The buffer still holds the end of the sound; wait for it to be heard.
            val frames = (pcm.size - offset + lead.size) / 2
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (!stopRequested && t.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < deadline + frames * 1000L / rate) Thread.sleep(20)
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    fun delete(id: String): Boolean = store.delete(id).also { if (it) record("note_deleted", arrayOf("id" to id)) }

    /** The notes (without sound), optionally for one person and only the ones still waiting. */
    fun list(toText: String?, pendingOnly: Boolean): JSONArray {
        val to = toText?.let { Recipient.normalize(it) }
        val array = JSONArray()
        store.all().filter { (to == null || it.to == to) && (!pendingOnly || it.heardAtMs == null) }.sortedBy { it.createdAtMs }.forEach {
            array.put(JSONObject().put("id", it.id).put("to", it.to).put("from", it.from).put("createdAtMs", it.createdAtMs)
                .put("durationMs", it.durationMs).put("heard", it.heardAtMs != null))
        }
        return array
    }

    fun status(): JSONObject {
        val waiting = JSONObject()
        store.all().filter { it.heardAtMs == null }.groupingBy { it.to }.eachCount().forEach { (to, n) -> waiting.put(to, n) }
        return JSONObject().put("state", phase).put("waiting", waiting)
    }

    companion object {
        const val MAX_NAME = 40
        const val MAX_RECORD_MS = 120_000L
        const val BEEP_MS = 250
        const val LEAD_MS = 300
        const val BEEP_TAIL_MS = 350L
    }
}
