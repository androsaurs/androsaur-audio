package dev.hram.androsaur.audio.level

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object AudioLevel {
    const val FLOOR_DBFS = -120.0

    fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }

    /** Level relative to full scale: 0 is the loudest possible, [FLOOR_DBFS] is silence. */
    fun dbfs(frame: ShortArray): Double {
        val rms = rms(frame)
        return if (rms == 0.0) FLOOR_DBFS else max(FLOOR_DBFS, 20 * log10(rms / 32768.0))
    }

    fun isDigitalSilence(frame: ShortArray) = frame.all { it.toInt() == 0 }
}

/**
 * Android gives a second recorder not an error but exact zeros. A long run of all-zero frames is the
 * sign that another app holds the microphone. Returns true once when the run reaches the threshold.
 */
class SilenceDetector(private val thresholdFrames: Int) {
    private var run = 0
    var silent = false
        private set

    fun onFrame(digitalSilence: Boolean): Boolean {
        if (!digitalSilence) { run = 0; silent = false; return false }
        run++
        if (run == thresholdFrames) { silent = true; return true }
        return false
    }
}
