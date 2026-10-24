package dev.hram.androsaur.audio.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechTest {
    @Test fun speakerSettingsRoundTrip() {
        for (id in listOf("system", "system:com.github.olga_yakovleva.rhvoice.android", "piper:denis", "piper:ruslan")) {
            assertEquals(id, SpeakerSpec.parse(id)?.id)
        }
        assertEquals(SpeakerSpec.Piper("irina"), SpeakerSpec.parse("piper:irina"))
    }

    @Test fun unknownSpeakersAreRejected() {
        for (id in listOf("", "piper", "piper:nobody", "piper:../denis", "system:", "system:a b", "sapi")) assertNull(SpeakerSpec.parse(id), id)
    }

    @Test fun silenceShutsTheMouthAndLoudSpeechOpensIt() {
        assertEquals(0f, MouthEnvelope.openness(0.0))
        assertEquals(0f, MouthEnvelope.openness(0.001))
        assertEquals(1f, MouthEnvelope.openness(0.5))
        val mid = MouthEnvelope.openness(0.05)
        assertTrue(mid in 0.3f..0.9f, "$mid")
    }

    @Test fun theMouthFollowsTheSoundByPlayedSample() {
        val rate = 22050
        val envelope = MouthEnvelope(rate)
        val loud = FloatArray(rate / 2) { 0.4f * sin(2 * PI * 200 * it / rate).toFloat() }
        envelope.add(loud); envelope.add(FloatArray(rate / 2))
        assertTrue(envelope.at(rate / 4L) > 0.8f, "open while the first half plays")
        assertEquals(0f, envelope.at(rate * 3L / 4))
        assertEquals(0f, envelope.at(rate * 10L), "past the end is closed")
        assertEquals(25, envelope.windows())
    }

    @Test fun chunksOfAnySizeGiveTheSameWindows() {
        val rate = 16000
        val whole = FloatArray(rate) { 0.2f * sin(2 * PI * 150 * it / rate).toFloat() }
        val a = MouthEnvelope(rate).apply { add(whole) }
        val b = MouthEnvelope(rate).apply { whole.toList().chunked(333).forEach { add(it.toFloatArray()) } }
        assertEquals(a.windows(), b.windows())
        for (i in 0 until rate step 640) assertEquals(a.at(i.toLong()), b.at(i.toLong()), 1e-6f)
    }

    @Test fun realTimeFactorAndSummary() {
        val fast = SpeechTiming(synthMs = 500, audioMs = 2000, firstAudioMs = 300)
        val slow = SpeechTiming(synthMs = 3000, audioMs = 2000, firstAudioMs = 900)
        assertEquals(0.25, fast.rtf, 1e-9)
        val summary = BenchSummary(listOf(BenchRow("a", fast), BenchRow("b", slow)))
        assertEquals(3500, summary.totalSynthMs)
        assertEquals(0.875, summary.rtf, 1e-9)
        assertEquals(1.5, summary.worstRtf, 1e-9)
        assertEquals(900, summary.worstFirstAudioMs)
        assertEquals(0.0, SpeechTiming(10, 0).rtf)
    }

    @Test fun tenDistinctBenchmarkPhrases() {
        assertEquals(10, BenchPhrases.ALL.size)
        assertEquals(10, BenchPhrases.ALL.toSet().size)
    }

    @Test fun wavLengthFromTheHeader() {
        val header = ByteArray(44)
        fun put(at: Int, text: String) = text.forEachIndexed { i, c -> header[at + i] = c.code.toByte() }
        fun int(at: Int, v: Int) { for (i in 0 until 4) header[at + i] = (v shr (8 * i)).toByte() }
        put(0, "RIFF"); put(8, "WAVE"); put(12, "fmt ")
        header[22] = 1; int(24, 22050); header[34] = 16
        assertEquals(1000, WavInfo.durationMs(header, 44L + 44100))
        assertNotNull(WavInfo.durationMs(header, 44))
        assertNull(WavInfo.durationMs(ByteArray(10), 10))
        header[0] = 'X'.code.toByte()
        assertNull(WavInfo.durationMs(header, 100))
    }
}
