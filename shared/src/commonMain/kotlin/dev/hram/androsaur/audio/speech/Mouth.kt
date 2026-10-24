package dev.hram.androsaur.audio.speech

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How wide the mouth on the screen should be while a sound plays: the loudness of the speech in short windows,
 * 0 (closed) to 1 (open). Sound arrives in chunks as it is synthesized; the windows are looked up by the number of the sample
 * that is playing now.
 */
class MouthEnvelope(private val sampleRate: Int) {
    private val windowSamples = sampleRate * WINDOW_MS / 1000
    private val values = ArrayList<Float>()
    private var sum = 0.0
    private var count = 0

    @Synchronized fun add(samples: FloatArray) {
        for (s in samples) {
            sum += s.toDouble() * s
            if (++count == windowSamples) { values.add(openness(sqrt(sum / count))); sum = 0.0; count = 0 }
        }
    }

    /** The mouth for the sample that is playing now; 0 past the end of what is known. */
    @Synchronized fun at(sample: Long): Float = values.getOrElse((sample / windowSamples).toInt().coerceAtLeast(0)) { 0f }

    @Synchronized fun windows(): Int = values.size

    companion object {
        const val WINDOW_MS = 40
        /** Speech is roughly -30..-12 dBFS in RMS; below the first the mouth is shut, above the second it is fully open. */
        const val SHUT_DB = -42.0
        const val OPEN_DB = -14.0

        fun openness(rms: Double): Float {
            if (rms <= 0.0) return 0f
            val db = 20 * log10(rms)
            return ((db - SHUT_DB) / (OPEN_DB - SHUT_DB)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
