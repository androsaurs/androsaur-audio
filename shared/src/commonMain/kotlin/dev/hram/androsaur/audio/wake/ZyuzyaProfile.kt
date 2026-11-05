package dev.hram.androsaur.audio.wake

/** A measured acoustic profile, not a general fuzzy match for ordinary conversation. */
object ZyuzyaProfile {
    fun supports(phrase: String): Boolean = WakePhrase.normalize(phrase) in setOf("зюзя", "сюзя")
}
