package dev.hram.androsaur.audio.wake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakePhraseTest {
    @Test fun defaultIsValid() = assertEquals(WakePhrase.DEFAULT, WakePhrase.normalize(WakePhrase.DEFAULT))

    @Test fun normalizeCleansInput() = assertEquals("привет петя", WakePhrase.normalize("  Привет,  Петя! "))

    @Test fun rejectsBadPhrases() {
        listOf("", "   ", "hello world", "привет~9 петя", "п петя", "привет петя вот так точно слишком много слов", "привет\"петя", "привет~", "|привет")
            .forEach { assertNull(WakePhrase.normalize(it), it) }
    }

    @Test fun acceptsAlternatives() = assertEquals("привет|здравствуй петя|пети~1", WakePhrase.normalize("привет|здравствуй петя|пети~1"))

    @Test fun grammarExpandsAlternativesAndDropsTolerance() = assertEquals(
        setOf("привет петя", "привет пети", "здравствуй петя", "здравствуй пети"),
        WakePhrase.grammarPhrases("привет|здравствуй петя~1|пети").toSet(),
    )

    @Test fun grammarIsCapped() = assertTrue(WakePhrase.grammarPhrases("аа|бб|вв|гг аа|бб|вв|гг аа|бб|вв|гг").size <= 16)

    @Test fun displayUsesFirstAlternatives() = assertEquals("привет петя", WakePhrase.display(WakePhrase.DEFAULT))
}
