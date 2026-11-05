package dev.hram.androsaur.audio.wake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZyuzyaProfileTest {
    @Test fun acceptsOnlyTheMeasuredProfile() {
        for (phrase in listOf("зюзя", "Сюзя!", " ЗЮЗЯ ")) assertTrue(ZyuzyaProfile.supports(phrase), phrase)
        for (phrase in listOf("петя", "привет зюзя", "зюзя|петя", "зюзя~1", "../зюзя", ""))
            assertFalse(ZyuzyaProfile.supports(phrase), phrase)
    }

    @Test fun engineIdIsStableAndDoesNotAcceptArbitraryProfiles() {
        assertEquals(EngineSpec.KwsZyuzya, EngineSpec.parse("kws:zyuzya"))
        assertEquals(null, EngineSpec.parse("kws:petya"))
    }
}
