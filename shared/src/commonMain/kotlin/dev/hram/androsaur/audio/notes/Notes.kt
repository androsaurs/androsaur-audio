package dev.hram.androsaur.audio.notes

import dev.hram.androsaur.audio.level.AudioLevel

/** A voice note left for a person; the sound lives in a file, this is the record about it. */
data class Note(
    val id: String,
    /** Who the note is for, as normalised by [Recipient.normalize]. */
    val to: String,
    /** Who left it, as given (usually the name the camera recognised); empty when unknown. */
    val from: String,
    val createdAtMs: Long,
    val durationMs: Long,
    /** When it was played to the recipient, or null while it waits. */
    val heardAtMs: Long? = null,
)

object Recipient {
    /** Lower case, one space between words, ё as е: «Жене», «жене» and « ЖЕНЕ » are one recipient. Null when nothing is left. */
    fun normalize(text: String): String? =
        text.lowercase().replace('ё', 'е').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
}

/** The notes waiting and heard, with the rules of who gets what and when a note is dropped. */
object NoteBook {
    /** A note nobody has heard is dropped after this long. */
    const val PENDING_TTL_MS = 14L * 24 * 3600 * 1000
    /** A note that was heard is kept this long, so it can be played again, then dropped. */
    const val HEARD_TTL_MS = 24L * 3600 * 1000
    const val MAX_NOTES = 50

    fun pendingFor(notes: List<Note>, to: String): List<Note> =
        notes.filter { it.heardAtMs == null && it.to == to }.sortedBy { it.createdAtMs }

    /** The notes that survive at [nowMs]; if still too many, the oldest heard ones go first, then the oldest waiting. */
    fun purge(notes: List<Note>, nowMs: Long): List<Note> {
        val alive = notes.filter { n ->
            val heard = n.heardAtMs
            if (heard != null) nowMs - heard < HEARD_TTL_MS else nowMs - n.createdAtMs < PENDING_TTL_MS
        }
        if (alive.size <= MAX_NOTES) return alive
        val drop = alive.sortedWith(compareBy<Note>({ it.heardAtMs == null }, { it.createdAtMs })).take(alive.size - MAX_NOTES).toSet()
        return alive.filter { it !in drop }
    }
}

/**
 * Decides when a spoken message is over, from the level of each audio frame: it waits for the speech to begin, then ends the
 * message after a pause. The speech threshold follows the noise of the room (measured over the first frames).
 */
class RecordingGate(
    private val frameMs: Double,
    private val maxMs: Long = DEFAULT_MAX_MS,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val endPauseMs: Long = END_PAUSE_MS,
) {
    enum class Verdict { CONTINUE, DONE, NO_SPEECH, TOO_SHORT, LIMIT }

    private var elapsedMs = 0.0
    private var floorDbfs = Double.NaN
    private var calibratedFrames = 0
    private var speechMs = 0.0
    private var lastSpeechAtMs = 0.0
    private var firstSpeechAtMs = 0.0
    private var spoke = false
    /** The loudest frame so far, for diagnosing a failed recording. */
    var peakDbfs = -120.0; private set
    /** Level above which a frame counts as speech. */
    val thresholdDbfs: Double get() = if (floorDbfs.isNaN()) MIN_THRESHOLD_DBFS else maxOf(MIN_THRESHOLD_DBFS, floorDbfs + MARGIN_DB)
    val recordedMs: Long get() = elapsedMs.toLong()
    /** Time of the first and the last speech frame, in ms from the start of the recording; -1 before any speech. */
    val speechStartMs: Long get() = if (spoke) firstSpeechAtMs.toLong() else -1
    val speechEndMs: Long get() = if (spoke) lastSpeechAtMs.toLong() else -1
    /** True once a loud enough frame was heard. */
    val hasSpeech: Boolean get() = spoke
    /** Whether the heard speech is long enough to keep. */
    val longEnough: Boolean get() = speechMs >= MIN_SPEECH_MS

    fun onFrame(dbfs: Double): Verdict {
        elapsedMs += frameMs
        if (dbfs > peakDbfs) peakDbfs = dbfs
        if (calibratedFrames < CALIBRATION_FRAMES) {
            calibratedFrames++
            floorDbfs = if (floorDbfs.isNaN()) dbfs else maxOf(floorDbfs, dbfs)
        }
        val loud = calibratedFrames >= CALIBRATION_FRAMES && dbfs > thresholdDbfs
        if (loud) { if (!spoke) firstSpeechAtMs = elapsedMs - frameMs; spoke = true; speechMs += frameMs; lastSpeechAtMs = elapsedMs }
        return when {
            spoke && elapsedMs - lastSpeechAtMs >= endPauseMs -> if (speechMs < MIN_SPEECH_MS) Verdict.TOO_SHORT else Verdict.DONE
            elapsedMs >= maxMs -> if (spoke) Verdict.LIMIT else Verdict.NO_SPEECH
            !spoke && elapsedMs >= startTimeoutMs -> Verdict.NO_SPEECH
            else -> Verdict.CONTINUE
        }
    }

    companion object {
        const val DEFAULT_MAX_MS = 60_000L
        const val START_TIMEOUT_MS = 6_000L
        const val END_PAUSE_MS = 1_500L
        const val MIN_SPEECH_MS = 300.0
        const val CALIBRATION_FRAMES = 6
        const val MARGIN_DB = 12.0
        /** The phone microphone is quiet: ordinary speech at arm's length is about -50 dBFS, the room about -75. */
        const val MIN_THRESHOLD_DBFS = -62.0
    }
}

object Wav {
    /** The 44-byte header of a 16-bit mono PCM WAV with [pcmBytes] of sound. */
    fun header(pcmBytes: Int, sampleRate: Int): ByteArray {
        val out = ByteArray(44)
        fun text(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v shr (8 * i)).toByte() }
        fun short(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        text(0, "RIFF"); int(4, 36 + pcmBytes); text(8, "WAVE"); text(12, "fmt "); int(16, 16)
        short(20, 1); short(22, 1); int(24, sampleRate); int(28, sampleRate * 2); short(32, 2); short(34, 16)
        text(36, "data"); int(40, pcmBytes)
        return out
    }

    fun durationMs(pcmBytes: Int, sampleRate: Int): Long = pcmBytes / 2L * 1000 / sampleRate

    /** Level of a frame, re-exported so the recorder needs one import. */
    fun level(frame: ShortArray): Double = AudioLevel.dbfs(frame)
}

/** Makes a quiet recording audible: a phone microphone gives ordinary speech at a few hundred out of 32768. */
object Loudness {
    const val TARGET_PEAK = 0.7
    /** About 36 dB; more would only raise the hiss of the room. */
    const val MAX_GAIN = 64.0
    const val LEAD_MS = 300
    const val TAIL_MS = 500

    /** Keeps [startMs] - lead .. [endMs] + tail and raises the level so the loud parts reach [TARGET_PEAK] of full scale. */
    fun process(samples: ShortArray, sampleRate: Int, startMs: Long, endMs: Long): ShortArray {
        val perMs = sampleRate / 1000
        val from = ((startMs - LEAD_MS) * perMs).toInt().coerceIn(0, samples.size)
        val to = ((endMs + TAIL_MS) * perMs).toInt().coerceIn(from, samples.size)
        val cut = samples.copyOfRange(from, to)
        if (cut.isEmpty()) return cut
        // The 99.9th percentile of the amplitude, so one click does not decide the gain.
        val sorted = IntArray(cut.size) { kotlin.math.abs(cut[it].toInt()) }.also { it.sort() }
        val peak = sorted[(sorted.size * 0.999).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(1)
        val gain = (TARGET_PEAK * Short.MAX_VALUE / peak).coerceIn(1.0, MAX_GAIN)
        return ShortArray(cut.size) { (cut[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }
}

/**
 * Automatic gain for a speech recognizer: this phone's microphone gives ordinary speech at a few hundred out of 32768, which
 * recognizers read poorly. Follows the loudest recent frame and raises the level toward [TARGET_PEAK]; never lowers it.
 */
class AutoGain(private val maxGain: Double = 20.0, private val decay: Double = 0.97) {
    private var envelope = 1.0

    fun apply(frame: ShortArray): ShortArray {
        var peak = 0
        for (s in frame) { val a = if (s < 0) -s.toInt() else s.toInt(); if (a > peak) peak = a }
        envelope = maxOf(peak.toDouble(), envelope * decay, 1.0)
        val gain = (TARGET_PEAK / envelope).coerceIn(1.0, maxGain)
        return ShortArray(frame.size) { (frame[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    companion object { const val TARGET_PEAK = 12000.0 }
}
