package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArithmeticTest {
    private fun calc(text: String) = (QuickCommands.parse(text) as? QuickCommand.Calc)?.expression
    private fun n(text: String) = RussianNumbers.value(text.split(" "))

    @Test fun numbersInWords() {
        assertEquals(7, n("семь"))
        assertEquals(25, n("двадцать пять"))
        assertEquals(123, n("сто двадцать три"))
        assertEquals(110, n("сто десять"))
        assertEquals(2005, n("две тысячи пять"))
        assertEquals(1000, n("тысяча"))
        assertEquals(21_000, n("двадцать одна тысяча"))
        assertEquals(1_500_000, n("один миллион пятьсот тысяч"))
        assertEquals(0, n("ноль"))
        assertEquals(42, n("42"))
    }

    @Test fun wordsThatAreNotOneNumber() {
        assertNull(n("пять семь"))
        assertNull(n("двадцать одиннадцать"))
        assertNull(n("десять пять"))
        assertNull(n("тысяча тысяча"))
        assertNull(n("пять двадцать"))
    }

    @Test fun theFourOperations() {
        assertEquals("2 + 2", calc("сколько будет два плюс два"))
        assertEquals("10 - 3", calc("сколько будет десять минус три"))
        assertEquals("7 * 8", calc("сколько будет семь умножить на восемь"))
        assertEquals("7 * 8", calc("семь на восемь"))
        assertEquals("100 / 4", calc("сколько будет сто разделить на четыре"))
        assertEquals("15 / 3", calc("пятнадцать поделить на три"))
        assertEquals("5 + 7", calc("пять прибавить семь"))
        assertEquals("9 - 4", calc("девять отнять четыре"))
    }

    @Test fun severalOperationsAndBigNumbers() {
        assertEquals("2 + 2 * 2", calc("сколько будет два плюс два умножить на два"))
        assertEquals("1250 - 999", calc("тысяча двести пятьдесят минус девятьсот девяносто девять"))
    }

    @Test fun wordsAroundTheExample() {
        assertEquals("3 + 4", calc("а сколько будет три плюс четыре"))
        assertEquals("3 + 4", calc("скажи сколько будет три плюс четыре"))
        assertEquals("3 + 4", calc("сколько будет три плюс четыре пожалуйста"))
    }

    @Test fun notArithmetic() {
        assertEquals(QuickCommand.Time, QuickCommands.parse("сколько времени"))
        assertEquals(QuickCommand.Timer(600), QuickCommands.parse("поставь таймер на десять минут"))
        assertNull(calc("сколько будет"))
        assertNull(calc("сколько будет два плюс"))
        assertNull(calc("пять"))
        assertNull(calc("передай маме что два плюс два"))
    }

    @Test fun theRecognizerKnowsTheWords() {
        val v = QuickCommands.vocabulary
        for (w in listOf("умножить", "разделить", "плюс", "минус", "семьдесят", "тысяча", "будет")) assertEquals(true, w in v, w)
    }
}
