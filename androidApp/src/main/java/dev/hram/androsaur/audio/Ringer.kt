package dev.hram.androsaur.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * The bell of a timer: a short two-tone chime, then a pause, again and again until stopped or [maxSeconds] pass. The microphone is
 * deaf only during the chime, so in the pauses the wake word is heard — and the wake word stops the bell.
 */
class Ringer(private val record: (String, Array<Pair<String, String>>) -> Unit) {
    @Volatile var ringing = false; private set
    @Volatile var micMutedUntil = 0L; private set
    @Volatile private var thread: Thread? = null

    /** Starts ringing (again from the start if it rings). */
    fun ring(maxSeconds: Int): String? {
        if (maxSeconds !in 1..600) return "invalid_seconds"
        stop("restart")
        ringing = true
        record("ring_started", arrayOf("seconds" to maxSeconds.toString()))
        thread = Thread({ loop(maxSeconds) }, "ringer").also { it.start() }
        return null
    }

    /** Stops the bell; [reason] goes to the event (`stop`, `wake_word`, `timeout`, `restart`). Returns whether it rang. */
    fun stop(reason: String = "stop"): Boolean {
        if (!ringing) return false
        ringing = false
        thread?.interrupt()
        thread = null
        micMutedUntil = SystemClock.elapsedRealtime() + SpeechController.MUTE_TAIL_MS
        record("ring_stopped", arrayOf("reason" to reason))
        return true
    }

    private fun loop(maxSeconds: Int) {
        val until = SystemClock.elapsedRealtime() + maxSeconds * 1000L
        val chime = chime()
        try {
            while (ringing && SystemClock.elapsedRealtime() < until) {
                micMutedUntil = Long.MAX_VALUE
                play(chime)
                micMutedUntil = SystemClock.elapsedRealtime() + SpeechController.MUTE_TAIL_MS
                Thread.sleep(PAUSE_MS)
            }
            if (ringing) stop("timeout")
        } catch (_: InterruptedException) {
        } finally {
            if (micMutedUntil == Long.MAX_VALUE) micMutedUntil = SystemClock.elapsedRealtime() + SpeechController.MUTE_TAIL_MS
        }
    }

    /** «Динь-дон»: two soft tones, 0.25 s each, with a fade so they do not click. */
    private fun chime(): ShortArray {
        val n = RATE / 4
        return ShortArray(n * 2) { i ->
            val freq = if (i < n) 1047.0 else 784.0
            val k = i % n
            val fade = minOf(1.0, k / (RATE * 0.01)) * (1.0 - k.toDouble() / n).coerceAtLeast(0.0)
            (sin(2 * PI * freq * k / RATE) * 0.7 * fade * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun play(pcm: ShortArray) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            Thread.sleep(pcm.size * 1000L / RATE + 100)
        } finally {
            t.release()
        }
    }

    private companion object { const val RATE = 16000; const val PAUSE_MS = 1800L }
}
