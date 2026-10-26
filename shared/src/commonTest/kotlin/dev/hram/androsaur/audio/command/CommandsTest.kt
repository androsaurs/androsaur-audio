package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandsTest {
    private val wife = Recipient("жена", person = "Маша")
    private val mama = Recipient("мама")
    private val parser = CommandParser(listOf(wife, mama, Recipient("Сергей"), Recipient("Женя")))

    @Test fun passToWife() {
        val c = parser.parse("передай жене")!!
        assertEquals("жена", c.recipient?.id)
        assertEquals("Маша", c.recipient?.addressee)
        assertEquals("Записка жене", c.echo())
    }

    @Test fun anyCaseOfTheNameReachesTheRecipient() {
        assertEquals("жена", parser.parse("скажи жену")?.recipient?.id)
        assertEquals("мама", parser.parse("передай маме что я задержусь")?.recipient?.id)
        assertEquals("Сергей", parser.parse("оставь записку сергею")?.recipient?.id)
    }

    @Test fun sendAndWriteAreVerbsToo() {
        assertEquals("жена", parser.parse("отправь жене")?.recipient?.id)
        assertEquals("мама", parser.parse("напиши маме")?.recipient?.id)
    }

    @Test fun forIsRepeatedBack() {
        val c = parser.parse("оставь записку для мамы")!!
        assertEquals("мама", c.recipient?.id)
        assertEquals("Записка для мамы", c.echo())
    }

    @Test fun verbWithoutRecipientAsksForWhom() {
        val c = parser.parse("передай")!!
        assertNull(c.recipient)
        assertEquals("жена", parser.parse("жене", verbSeen = true)?.recipient?.id)
    }

    @Test fun nothingIsGuessedFromOtherSpeech() {
        assertNull(parser.parse("какая сегодня погода"))
        assertNull(parser.parse("включи свет в комнате"))
    }

    @Test fun aNameWithoutAVerbIsNotACommand() {
        assertNull(parser.parse("да и жене что я задержусь"))
        assertNull(parser.parse("жене привет"))
    }

    @Test fun declensionWritesOutTheCases() {
        assertEquals(listOf("жена", "жене", "жену", "жены"), Declension.forms("Жена"))
        assertEquals(listOf("маша", "маше", "машу", "маши"), Declension.forms("Маша"))
        assertEquals(listOf("женя", "жене", "женю", "жени"), Declension.forms("Женя"))
        assertEquals(listOf("сергей", "сергею", "сергея", "сергее"), Declension.forms("Сергей"))
        assertEquals(listOf("иван", "ивану", "ивана", "иване"), Declension.forms("Иван"))
        assertEquals(listOf("леша", "леше", "лешу", "леши"), Declension.forms("Лёша"))
    }

    @Test fun anUnknownNameIsNotGuessed() {
        assertNull(parser.parse("передай Петру")!!.recipient)
    }

    @Test fun theVerbMayBeHeardRoughly() {
        assertEquals("мама", parser.parse("передан маме")?.recipient?.id)
    }

    @Test fun aBoundRecipientWinsATieWhateverTheOrder() {
        // «жене» is «жена» and «Женя» alike: the one bound to somebody wins.
        assertEquals("жена", parser.parse("передай жене")?.recipient?.id, "the role given to Маша, over the unbound name")
        assertEquals("жена", CommandParser(listOf(Recipient("Женя"), wife)).parse("передай жене")?.recipient?.id)
    }

    @Test fun anUnboundRoleNoLongerTakesAPersonsName() {
        // The bug: the role «жена» was in the list with nobody behind it and took the notes meant for Женя.
        val book = listOf(Recipient("жена"), Recipient("мама"), Recipient("Женя", personId = "p1"))
        assertEquals("Женя", CommandParser(book).parse("передай жене")?.recipient?.id)
        assertEquals("мама", CommandParser(book).parse("передай маме")?.recipient?.id, "alone, an unbound role still works")
    }

    @Test fun twoUnboundKeepTheListOrder() =
        assertEquals("жена", CommandParser(listOf(Recipient("жена"), Recipient("Женя"))).parse("передай жене")?.recipient?.id)
}
