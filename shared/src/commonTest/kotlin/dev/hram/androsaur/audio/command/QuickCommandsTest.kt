package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickCommandsTest {
    private fun p(text: String) = QuickCommands.parse(text)

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

}
