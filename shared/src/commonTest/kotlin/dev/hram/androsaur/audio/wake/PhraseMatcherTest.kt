package dev.hram.androsaur.audio.wake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseMatcherTest {
    private val matcher = PhraseMatcher("привет петя")

    @Test fun exactAndSurroundedPhrasesMatch() {
        assertTrue(matcher.matches("привет петя"))
        assertTrue(matcher.matches("ну Привет, Петя как дела"))
    }

    @Test fun otherNamesAndPartialPhrasesDoNot() {
        assertFalse(matcher.matches("привет вася"))
        assertFalse(matcher.matches("петька привет"))
        assertFalse(matcher.matches("привет"))
        assertFalse(matcher.matches(""))
    }

    @Test fun wordsMustBeWholeAndInOrder() {
        assertFalse(matcher.matches("приветпетя"))
        assertFalse(matcher.matches("петя привет"))
        assertFalse(matcher.matches("привет петя" .replace("петя", "петяя")))
    }

    @Test fun yoIsTreatedAsYe() {
        assertTrue(PhraseMatcher("привет фёдор").matches("привет федор"))
    }

    @Test fun emptyPhraseIsRejected() {
        assertFailsWith<IllegalArgumentException> { PhraseMatcher("  ") }
    }

    @Test fun engineIdsRoundTrip() {
        assertEquals(EngineSpec.None, EngineSpec.parse("none"))
        assertEquals(EngineSpec.Vosk, EngineSpec.parse("vosk"))
        val mww = EngineSpec.parse("mww:okay_nabu") as EngineSpec.MicroWakeWord
        assertEquals("okay_nabu", mww.model)
        assertEquals("mww:okay_nabu", mww.id)
    }

    @Test fun unknownOrUnsafeEngineIdsAreRejected() {
        assertNull(EngineSpec.parse("porcupine"))
        assertNull(EngineSpec.parse("mww:"))
        assertNull(EngineSpec.parse("mww:../secret"))
        assertNull(EngineSpec.parse("mww:Okay"))
    }
}
