package dev.hram.androsaur.audio.events

import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogTest {
    @Test fun idsGrowAndSinceReturnsOnlyNewerEvents() {
        val log = EventLog()
        log.append("a", 1); log.append("b", 2); log.append("c", 3)
        assertEquals(listOf("b", "c"), log.since(1).map { it.type })
        assertEquals(3, log.latestId)
        assertEquals(emptyList(), log.since(3))
    }

    @Test fun oldestEventsFallOutAtCapacityButIdsKeepGrowing() {
        val log = EventLog(capacity = 2)
        repeat(5) { log.append("e$it", it.toLong()) }
        assertEquals(listOf(4L, 5L), log.since(0).map { it.id })
    }

    @Test fun limitCapsTheBatch() {
        val log = EventLog()
        repeat(10) { log.append("e", it.toLong()) }
        assertEquals(listOf(1L, 2L, 3L), log.since(0, limit = 3).map { it.id })
    }
}
