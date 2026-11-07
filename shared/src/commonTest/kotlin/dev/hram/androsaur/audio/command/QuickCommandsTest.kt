package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCommandsTest {
    private fun p(text: String) = QuickCommands.parse(text)

    @Test fun personalHoroscopeDoesNotConsumeNotesNegationsOrRequestsForOtherPeople() {
        for (text in listOf("гороскоп", "расскажи мой гороскоп", "скажи мне гороскоп на сегодня", "прочитай гороскоп пожалуйста", "какой мой гороскоп"))
            assertEquals(QuickCommand.Horoscope(), p(text), text)
        assertEquals(QuickCommand.Horoscope(tomorrow = true), p("гороскоп на завтра"))
        for (text in listOf("не рассказывай гороскоп", "передай маме гороскоп", "скажи маме мой гороскоп", "гороскоп для овна", "гороскоп на вчера"))
            assertNull(p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("гороскоп", "расскажи", "прочитай", "мой")))
    }

    @Test fun otherAccessoriesHaveExplicitTargetsAndOnlyOneCanBeRequested() {
        for ((text, target) in listOf(
            "надень бантик" to "bow", "одень бант" to "bow", "надень шарфик" to "scarf",
            "надень шарф" to "scarf", "надень цветочный венок" to "crown",
            "возьми семечку" to "seed", "дай семечко" to "seed",
        )) assertEquals(QuickCommand.Accessory(target, true), p(text), text)
        for ((text, target) in listOf(
            "сними бантик" to "bow", "убери бант" to "bow", "сними шарф" to "scarf",
            "сними венок" to "crown", "убери семечку" to "seed", "отложи семечку" to "seed",
            "сними все аксессуары" to "none", "убери украшения" to "none", "сними всё" to "none",
        )) assertEquals(QuickCommand.Accessory(target, false), p(text), text)
        for (text in listOf("не надевай бант", "не бери семечку", "передай маме надень венок",
            "надень очки и бантик", "надень все аксессуары", "сними венок и надень шарфик")) assertNull(p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("бантик", "бант", "шарф", "шарфик", "венок", "возьми", "семечку", "все")))
    }

    @Test fun penguinOutfitsAreCommands() {
        for ((text, target) in listOf(
            "надень шапку" to "bobble_hat", "надень шапочку" to "bobble_hat", "надень наушники" to "headphones",
            "надень кепку" to "cap", "надень толстовку" to "hoodie", "надень худи" to "hoodie",
        )) assertEquals(QuickCommand.Accessory(target, true), p(text), text)
        for ((text, target) in listOf(
            "сними шапку" to "bobble_hat", "сними наушники" to "headphones", "убери кепку" to "cap", "сними толстовку" to "hoodie",
        )) assertEquals(QuickCommand.Accessory(target, false), p(text), text)
        for (text in listOf("передай папе надень кепку", "не надевай наушники", "надень шапку и наушники")) assertNull(p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("шапку", "наушники", "кепку", "толстовку", "худи")))
    }

    @Test fun rememberingAndLookingForThings() {
        for (text in listOf("запомни", "запомните пожалуйста", "пожалуйста запомни")) assertEquals(QuickCommand.Remember, p(text), text)
        for (text in listOf("где лежит", "где лежат", "где находится", "где хранятся", "вспомни", "вспомните пожалуйста")) assertEquals(QuickCommand.Recall, p(text), text)
        for (text in listOf("передай маме запомни", "передай папе где лежит", "где", "лежит", "какая погода")) assertNotEquals(QuickCommand.Remember, p(text), text)
        for (text in listOf("передай папе где лежит", "передай маме вспомни", "где", "лежит")) assertNotEquals(QuickCommand.Recall, p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("запомни", "вспомни", "где", "лежит", "лежат", "находится", "хранятся")))
    }

    @Test fun forgettingIsACommandAndNeverPartOfANote() {
        for (text in listOf("забудь", "забудьте пожалуйста", "пожалуйста забудь")) assertEquals(QuickCommand.Forget, p(text), text)
        for (text in listOf("передай папе забудь", "передай маме забудь про это")) assertNotEquals(QuickCommand.Forget, p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("забудь", "забудьте")))
    }

    @Test fun rememberingASumIsNotCalculatingIt() {
        // The whole phrase in one breath: it is a request to remember (what follows is lost; the fact is said after «Говорите.»).
        assertEquals(QuickCommand.Remember, p("запомни два плюс два равно пять"))
        // The same words without «запомни» are still arithmetic, and «вспомни» is a recall, not a calculation.
        assertEquals(QuickCommand.Calc("2 + 2"), p("сколько будет два плюс два"))
        assertEquals(QuickCommand.Recall, p("вспомни два плюс два"))
    }

    @Test fun rememberingAndRecallingAShoppingNote() {
        assertEquals(QuickCommand.Remember, p("запомни купить батарейки"))
        assertEquals(QuickCommand.Recall, p("вспомни что купить"))
    }

    @Test fun glassesAreExplicitCommandsAndNotContentOfANote() {
        for (text in listOf("надень очки", "одень очки пожалуйста", "надевай очки"))
            assertEquals(QuickCommand.Glasses(true), p(text), text)
        for (text in listOf("сними очки", "убери очки пожалуйста", "снимай очки"))
            assertEquals(QuickCommand.Glasses(false), p(text), text)
        for (text in listOf("не надевай очки", "не снимай очки", "передай маме надень очки", "скажи Кате сними очки", "очки", "надень ботинки", "надень и сними очки"))
            assertNull(p(text), text)
        assertTrue(QuickCommands.vocabulary.containsAll(listOf("очки", "надень", "одень", "сними", "снимай", "убери")))
    }

    @Test fun theTime() {
        assertEquals(QuickCommand.Time, p("который час"))
        assertEquals(QuickCommand.Time, p("сколько сейчас времени"))
        assertEquals(QuickCommand.Time, p("какое сейчас время"))
    }

    @Test fun theWeather() {
        assertEquals(QuickCommand.Weather(tomorrow = false), p("какая погода"))
        assertEquals(QuickCommand.Weather(tomorrow = true), p("какая погода завтра"))
        assertEquals(QuickCommand.Weather(tomorrow = true), p("что с погодой на завтра"))
        assertEquals(QuickCommand.Weather(tomorrow = false), p("прогноз"))
    }

    @Test fun timers() {
        assertEquals(QuickCommand.Timer(600), p("таймер на десять минут"))
        assertEquals(QuickCommand.Timer(60), p("поставь таймер на одну минуту"))
        assertEquals(QuickCommand.Timer(25 * 60), p("таймер на двадцать пять минут"))
        assertEquals(QuickCommand.Timer(1800), p("таймер на полчаса"))
        assertEquals(QuickCommand.Timer(90), p("таймер на полторы минуты"))
        assertEquals(QuickCommand.Timer(3600), p("таймер на час"))
        assertEquals(QuickCommand.Timer(7200), p("таймер на два часа"))
        assertEquals(QuickCommand.Timer(30), p("таймер на тридцать секунд"))
        assertEquals(QuickCommand.Timer(300), p("таймер пять"), "minutes when no unit")
        assertEquals(QuickCommand.Timer(600), p("таймер на 10 минут"), "digits from text input")
        assertNull(p("таймер"), "how long is not said")
        assertNull(p("таймер на пять часов"), "longer than three hours")
    }

    @Test fun timeLeftAndStop() {
        assertEquals(QuickCommand.TimerLeft, p("сколько осталось"))
        assertEquals(QuickCommand.TimerLeft, p("сколько осталось на таймере"))
        assertEquals(QuickCommand.Stop, p("стоп"))
        assertEquals(QuickCommand.Stop, p("выключи таймер"))
        assertEquals(QuickCommand.Stop, p("останови таймер"))
        assertEquals(QuickCommand.Stop, p("отключи таймер"))
        assertEquals(QuickCommand.Stop, p("сбрось таймер"))
        assertEquals(QuickCommand.Stop, p("отмени таймер"))
        assertEquals(QuickCommand.Stop, p("хватит"))
    }

    @Test fun repeatTheNoteButNotANoteForSomebody() {
        assertEquals(QuickCommand.RepeatNote, p("повтори записку"))
        assertEquals(QuickCommand.RepeatNote, p("повтори последнее сообщение"))
        assertEquals(QuickCommand.RepeatNote, p("проиграй еще раз записку"))
        assertNull(p("передай жене записку"))
        assertNull(p("запиши сообщение маше"))
        assertNull(p("повтори"), "repeat what — not a note")
    }

    @Test fun otherSpeechIsNoCommand() {
        assertNull(p("привет как дела"))
        assertNull(p(""))
    }

    @Test fun theRecognizerKnowsTheWords() {
        val words = QuickCommands.vocabulary
        for (w in listOf("таймер", "который", "погода", "завтра", "стоп", "полчаса", "двадцать", "записку", "повтори")) assertTrue(w in words, w)
    }


    @Test fun switchingVerbsAloneAreNotAStop() {
        for (text in listOf("выключи", "отключи", "сбрось", "выключи свет", "включи свет", "отключи свет", "выключи семьдесят"))
            assertNull(p(text), text)
        assertEquals(QuickCommand.Stop, p("выключи таймер"))
        assertEquals(QuickCommand.Stop, p("отключи таймер"))
        assertEquals(QuickCommand.Stop, p("сбрось таймер"))
        assertEquals(QuickCommand.Stop, p("стоп"))
    }
}
