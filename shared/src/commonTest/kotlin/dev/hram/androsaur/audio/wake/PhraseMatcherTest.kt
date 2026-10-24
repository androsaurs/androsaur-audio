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

    @Test fun alternativesInAWordAreAccepted() {
        val named = PhraseMatcher("привет петя|пети|петю|пете")
        assertTrue(named.matches("Привет, Пете"))
        assertTrue(named.matches("привет пети"))
        assertTrue(named.matches("ой привет петю как дела"))
        assertFalse(named.matches("привет мир"))
        assertFalse(named.matches("привет как дела расскажи"))
    }

    @Test fun toleranceAcceptsNearbyWordsWithTheSameFirstLetter() {
        val fuzzy = PhraseMatcher("привет~3 петя~1")
        assertTrue(fuzzy.matches("привет пете"))
        assertTrue(fuzzy.matches("привет петь"))
        assertTrue(fuzzy.matches("привет пётр"))
        assertTrue(fuzzy.matches("приве петя"))
        assertTrue(fuzzy.matches("ну привёл петя"))
        assertTrue(fuzzy.matches("пред петя"))
    }

    @Test fun toleranceDoesNotLetOtherNamesOrPlainSpeechThrough() {
        val fuzzy = PhraseMatcher("привет~3 петя~1")
        assertFalse(fuzzy.matches("привет катя"))
        assertFalse(fuzzy.matches("привет вася"))
        assertFalse(fuzzy.matches("привет как дела"))
        assertFalse(fuzzy.matches("привет"))
        assertFalse(fuzzy.matches("петя привет"))
        assertFalse(fuzzy.matches("привет петров"))
        assertFalse(fuzzy.matches("привет петух"))
        assertFalse(fuzzy.matches("потом петя"))
        assertFalse(fuzzy.matches("перед петей"))
        assertFalse(fuzzy.matches("пока как дела"))
        assertFalse(fuzzy.matches("привет будете какой"))
    }

    @Test fun continuationAfterTheGreetingDoesNotMatchWithoutTheName() {
        assertFalse(matcher.matches("привет как у тебя дела"))
        assertFalse(matcher.matches("привет всем"))
    }

    @Test fun emptyPhraseIsRejected() {
        assertFailsWith<IllegalArgumentException> { PhraseMatcher("  ") }
    }

    @Test fun engineIdsRoundTrip() {
        assertEquals(EngineSpec.None, EngineSpec.parse("none"))
        assertEquals(EngineSpec.Vosk, EngineSpec.parse("vosk"))
        assertEquals(EngineSpec.VoskGrammar, EngineSpec.parse("vosk-grammar"))
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
