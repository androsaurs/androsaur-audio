package dev.hram.androsaur.audio.level

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioLevelTest {
    @Test fun silenceIsAtTheFloor() {
        assertEquals(AudioLevel.FLOOR_DBFS, AudioLevel.dbfs(ShortArray(64)))
        assertTrue(AudioLevel.isDigitalSilence(ShortArray(64)))
    }

    @Test fun fullScaleSquareWaveIsNearZeroDbfs() {
        val frame = ShortArray(64) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertTrue(AudioLevel.dbfs(frame) > -0.1)
        assertFalse(AudioLevel.isDigitalSilence(frame))
    }

    @Test fun halvingTheAmplitudeLowersTheLevelBySixDecibels() {
        val loud = ShortArray(64) { 8000 }
        val quiet = ShortArray(64) { 4000 }
        assertEquals(6.02, AudioLevel.dbfs(loud) - AudioLevel.dbfs(quiet), 0.01)
    }

    @Test fun detectorFiresOnceAtTheThresholdAndResetsOnSound() {
        val detector = SilenceDetector(thresholdFrames = 3)
        assertFalse(detector.onFrame(true)); assertFalse(detector.onFrame(true))
        assertTrue(detector.onFrame(true))
        assertTrue(detector.silent)
        assertFalse(detector.onFrame(true))
        assertFalse(detector.onFrame(false))
        assertFalse(detector.silent)
        assertFalse(detector.onFrame(true)); assertFalse(detector.onFrame(true))
        assertTrue(detector.onFrame(true))
    }
}
