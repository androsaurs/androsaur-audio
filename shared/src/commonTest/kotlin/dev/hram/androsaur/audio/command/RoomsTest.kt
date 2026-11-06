package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomsTest {
    private val kids = Room("k", "Детская", listOf("детская", "детскую", "детской"))
    private val living = Room("l", "Гостиная", listOf("гостиная", "гостиную", "гостиной"))
    private val polina = Room("p", "Комната Полины", listOf("полина", "комната полины"))
    private val granny = Room("g", "Колонка бабушки", listOf("бабушка"))
    private val parser = RoomParser(listOf(kids, living, polina, granny))

    @Test fun inTheRoomIsASpeaker() {
        assertEquals("k", parser.parse("передай в детскую")?.id)
        assertEquals("l", parser.parse("скажи в гостиную приходите")?.id)
        assertEquals("k", parser.parse("передай в детской")?.id)
    }

    @Test fun thePrepositionIsWhatSeparatesARoomFromAPerson() {
        assertNull(parser.parse("передай полине"))
        assertNull(parser.parse("передай бабушке"))
        assertNull(parser.parse("передай жене что я задержусь"))
        assertEquals("p", parser.parse("передай в полину")?.id)
        assertEquals("g", parser.parse("передай на колонку бабушки")?.id)
    }

    @Test fun fillerWordsBetweenAreSkipped() {
        assertEquals("p", parser.parse("передай в комнату полины")?.id)
        assertEquals("g", parser.parse("скажи в колонку бабушке")?.id)
        assertEquals("p", parser.parse("передай в комнату полины приходите")?.id)
    }

    @Test fun oneWrongLetterStillFindsTheRoom() {
        assertEquals("k", parser.parse("передай в детскую".replace("детскую", "детскуя"))?.id)
        assertNull(parser.parse("передай в кухню"))
    }

    @Test fun aDroppedPrepositionIsForgivenForAListedName() {
        assertEquals("l", parser.parse("передай гостиная")?.id)
        assertEquals("l", parser.parse("секунду передай гостиную")?.id)
        assertEquals("p", parser.parse("передай полина")?.id)
        assertNull(parser.parse("передай полине"))
        assertNull(parser.parse("передай бабушке"))
    }

    @Test fun everySpeaker() {
        assertEquals("all", parser.parse("передай на все колонки")?.id)
        assertEquals("all", parser.parse("передай во все комнаты")?.id)
        assertEquals("all", parser.parse("передай все колонки")?.id)
        assertEquals("all", parser.parse("передай на все")?.id)
        assertNull(parser.parse("передай все"))
        assertNull(parser.parse("передай маме что все дома"))
    }

    @Test fun aVerbIsRequiredUnlessItWasHeardBefore() {
        assertNull(parser.parse("в детскую"))
        assertEquals("k", parser.parse("в детскую", verbSeen = true)?.id)
        assertNull(parser.parse("какая погода в детской"))
    }

    @Test fun anythingBeforeThePrepositionIsNotARoom() {
        assertNull(parser.parse("передай детскуя"))
        assertNull(parser.parse("передай маме что в детской беспорядок"))
        assertEquals("k", parser.parse("передай сообщение в детскую")?.id)
    }

    @Test fun theRecognizerKnowsThePrepositionsAndNames() {
        val words = RoomParser.vocabulary(listOf(kids, polina))
        assertTrue(listOf("в", "во", "на", "комнату", "детскую", "полины", "полину").all { it in words })
    }
}
