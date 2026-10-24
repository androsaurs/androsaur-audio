package dev.hram.androsaur.audio.notes

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class NotesTest {
    private fun note(id: String, to: String = "жена", at: Long = 0, heard: Long? = null) = Note(id, to, "", at, 1000, heard)

    @Test fun recipientIsNormalised() {
        assertEquals("жена", Recipient.normalize("  Жена "))
        assertEquals("елена петрова", Recipient.normalize("Ёлена   Петрова"))
        assertNull(Recipient.normalize("   "))
    }

    @Test fun pendingForIsOldestFirstAndSkipsHeardAndOthers() {
        val notes = listOf(note("b", at = 20), note("a", at = 10), note("c", at = 5, heard = 6), note("d", to = "мама", at = 1))
        assertEquals(listOf("a", "b"), NoteBook.pendingFor(notes, "жена").map { it.id })
    }

    @Test fun purgeDropsOldWaitingAndOldHeard() {
        val now = 100L * 24 * 3600 * 1000
        val fresh = note("fresh", at = now - 1000)
        val stale = note("stale", at = now - NoteBook.PENDING_TTL_MS - 1)
        val heardRecently = note("heard", at = 0, heard = now - 1000)
        val heardLongAgo = note("old", at = 0, heard = now - NoteBook.HEARD_TTL_MS - 1)
        assertEquals(listOf("fresh", "heard"), NoteBook.purge(listOf(fresh, stale, heardRecently, heardLongAgo), now).map { it.id })
    }

    @Test fun purgeKeepsWaitingOverHeardWhenFull() {
        val now = 1_000_000L
        val waiting = (1..NoteBook.MAX_NOTES).map { note("w$it", at = now - it) }
        val heard = note("h", at = 0, heard = now - 1)
        val kept = NoteBook.purge(waiting + heard, now)
        assertEquals(NoteBook.MAX_NOTES, kept.size)
        assertEquals(false, kept.any { it.id == "h" })
    }

    private fun feed(gate: RecordingGate, levels: List<Double>): RecordingGate.Verdict {
        var v = RecordingGate.Verdict.CONTINUE
        for (l in levels) { v = gate.onFrame(l); if (v != RecordingGate.Verdict.CONTINUE) break }
        return v
    }

    private fun quiet(ms: Int) = List(ms / 100) { -75.0 }
    private fun loud(ms: Int) = List(ms / 100) { -25.0 }

    @Test fun endsAfterAPauseFollowingSpeech() {
        assertEquals(RecordingGate.Verdict.DONE, feed(RecordingGate(100.0), quiet(1000) + loud(2000) + quiet(3000)))
    }

    @Test fun noSpeechTimesOut() {
        assertEquals(RecordingGate.Verdict.NO_SPEECH, feed(RecordingGate(100.0), quiet(10_000)))
    }

    @Test fun aClickIsTooShort() {
        assertEquals(RecordingGate.Verdict.TOO_SHORT, feed(RecordingGate(100.0), quiet(1000) + loud(100) + quiet(3000)))
    }

    @Test fun limitStopsALongMessage() {
        assertEquals(RecordingGate.Verdict.LIMIT, feed(RecordingGate(100.0, maxMs = 5000), quiet(1000) + loud(10_000)))
    }

    @Test fun noisyRoomRaisesTheThreshold() {
        val gate = RecordingGate(100.0)
        feed(gate, List(6) { -30.0 })
        assertEquals(-18.0, gate.thresholdDbfs)
        // steady noise at -30 is not speech, so nothing starts
        assertEquals(RecordingGate.Verdict.NO_SPEECH, feed(gate, List(80) { -30.0 }))
    }

    @Test fun aQuietVoiceIsStillSpeech() {
        // measured: the room is about -75 dBFS and speech from this phone's microphone about -52
        val gate = RecordingGate(100.0)
        assertEquals(RecordingGate.Verdict.DONE, feed(gate, quiet(1000) + List(20) { -52.0 } + quiet(3000)))
        assertEquals(-52.0, gate.peakDbfs)
    }

    @Test fun quietSpeechIsRaisedAndTheEdgesAreCut() {
        val rate = 16000
        val samples = ShortArray(rate * 5) { i -> if (i in rate * 2 until rate * 3) (if (i % 2 == 0) 600 else -600).toShort() else 5 }
        val out = Loudness.process(samples, rate, 2000, 3000)
        assertEquals((1000 + Loudness.LEAD_MS + Loudness.TAIL_MS) * 16, out.size)
        assertTrue(kotlin.math.abs(out.maxOf { kotlin.math.abs(it.toInt()) } - (Loudness.TARGET_PEAK * Short.MAX_VALUE).toInt()) < 40)
    }

    @Test fun gainIsLimited() {
        val out = Loudness.process(ShortArray(16000) { 2 }, 16000, 0, 1000)
        assertEquals(2 * Loudness.MAX_GAIN.toInt(), out[100].toInt())
    }

    @Test fun gateReportsWhereSpeechWas() {
        val gate = RecordingGate(100.0)
        feed(gate, quiet(1000) + loud(1000) + quiet(3000))
        assertEquals(1000L, gate.speechStartMs)
        assertEquals(2000L, gate.speechEndMs)
    }

    @Test fun autoGainRaisesQuietSpeechAndLeavesLoudAlone() {
        val gain = AutoGain()
        val quiet = gain.apply(ShortArray(512) { if (it % 2 == 0) 300 else -300 })
        assertTrue(quiet.maxOf { it.toInt() } in 5000..7000)
        val loud = AutoGain().apply(ShortArray(512) { if (it % 2 == 0) 20000 else -20000 })
        assertEquals(20000, loud.maxOf { it.toInt() })
    }

    @Test fun wavHeaderDescribesTheSound() {
        val h = Wav.header(32000, 16000)
        assertEquals(44, h.size)
        assertContentEquals("RIFF".encodeToByteArray(), h.copyOfRange(0, 4))
        assertEquals(1000L, Wav.durationMs(32000, 16000))
        assertEquals((36 + 32000 and 0xFF).toByte(), h[4])
    }
}
