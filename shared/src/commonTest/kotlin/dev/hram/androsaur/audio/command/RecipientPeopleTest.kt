package dev.hram.androsaur.audio.command

import kotlin.test.Test
import kotlin.test.assertEquals

class RecipientPeopleTest {
    private val book = listOf(Recipient("жена", person = "Катя"), Recipient("мама"), Recipient("Женя", listOf("Жене")), Recipient("Катя"))

    @Test fun namesWithoutAnIdGetTheirIdInPlace() {
        val (list, changed) = Recipient.withPeople(book, listOf("p1" to "Женя", "p2" to "Катя"), 40)
        assertEquals(2, changed)
        assertEquals(listOf(null, null, "p1", "p2"), list.map { it.personId })
        assertEquals(listOf("Жене"), list[2].forms)
    }

    @Test fun aRenameKeepsThePlaceAndTheIdAndRolesFollow() {
        val withIds = Recipient.withPeople(book, listOf("p1" to "Женя", "p2" to "Катя"), 40).first
        val (list, _) = Recipient.withPeople(withIds, listOf("p1" to "Папа", "p2" to "Екатерина"), 40)
        assertEquals(listOf("жена", "мама", "Папа", "Екатерина"), list.map { it.id })
        assertEquals(Recipient("Папа", personId = "p1"), list[2])
        assertEquals("Екатерина", list[0].person)
    }

    @Test fun nothingChangesWhenNothingChanged() {
        val withIds = Recipient.withPeople(book, listOf("p1" to "Женя"), 40).first
        assertEquals(0, Recipient.withPeople(withIds, listOf("p1" to "Женя"), 40).second)
    }

    @Test fun aNewPersonGoesToTheEndWhileThereIsRoom() {
        assertEquals(Recipient("Лёша", personId = "p3"), Recipient.withPeople(book, listOf("p3" to "Лёша"), 40).first.last())
        assertEquals(book, Recipient.withPeople(book, listOf("p3" to "Лёша"), 4).first)
    }

    @Test fun aMergedPersonTakesTheHouseIdOrGoes() {
        val mine = listOf(Recipient("мама"), Recipient("Катя", personId = "local"))
        assertEquals(listOf(Recipient("мама"), Recipient("Катя", personId = "house")), Recipient.merged(mine, "local", "house"))
        val both = mine + Recipient("Екатерина", personId = "house")
        assertEquals(listOf(Recipient("мама"), Recipient("Екатерина", personId = "house")), Recipient.merged(both, "local", "house"))
    }
}
