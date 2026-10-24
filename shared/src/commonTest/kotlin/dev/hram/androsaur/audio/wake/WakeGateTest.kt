package dev.hram.androsaur.audio.wake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeGateTest {
    @Test fun firstDetectionPassesAndRepeatsInsideCooldownAreDropped() {
        val gate = WakeGate(cooldownMs = 2000)
        assertTrue(gate.accept(0))
        assertFalse(gate.accept(1999))
        assertTrue(gate.accept(2000))
    }

    @Test fun droppedDetectionDoesNotExtendTheCooldown() {
        val gate = WakeGate(cooldownMs = 1000)
        assertTrue(gate.accept(5000))
        assertFalse(gate.accept(5900))
        assertTrue(gate.accept(6000))
    }

    @Test fun placeholderEngineNeverDetects() {
        val engine = NoWakeWordEngine()
        assertEquals("none", engine.name)
        assertFalse(engine.process(ShortArray(engine.frameLength) { 30000 }))
    }
}
