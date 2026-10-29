package dev.lilguy.memory

import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.Span
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteMemoryStoreTest {
    @Test
    fun `events and memories round-trip and persist across reopening`() {
        val file = File(createTempDirectory("mem").toFile(), "memory.db")
        SqliteMemoryStore(file).use { s ->
            val a = s.appendEvent("r1", 5, 1, "07:00", EventKind.Observation, "money $40; it's a \"day\"")
            val b = s.appendEvent("r1", 5, 1, "07:00", EventKind.Action, "do activity=sketch")
            s.appendEvent("r1", 9, null, null, EventKind.Observation, "no clock here")
            s.appendEvent("r2", 1, 1, "07:00", EventKind.Observation, "another life")
            assertEquals(listOf(a, b), listOf(a, b).also { assertEquals(it, s.eventsAfter("r1", 0, 2).map { e -> e.id }) })
            assertEquals(1, s.eventsAfter("r1", b, 10).size)
            assertEquals(3, s.countEventsAfter("r1", 0))
            assertEquals(1, s.countEventsAfter("r2", 0))
            val e = s.eventsAfter("r1", 0, 10)
            assertEquals("money $40; it's a \"day\"", e[0].text)
            assertEquals(EventKind.Action, e[1].kind)
            assertNull(e[2].day)
            assertNull(e[2].time)
            assertEquals(0L, s.coveredThrough("r1", 1))
            s.addMemory("r1", 1, a, b, Span(1, "07:00", 1, "07:00"), "I started sketching.", "m")
        }
        SqliteMemoryStore(file).use { s ->
            val m = s.memories("r1", 1).single()
            assertEquals("I started sketching.", m.text)
            assertEquals(Span(1, "07:00", 1, "07:00"), m.span)
            assertEquals(2L, s.coveredThrough("r1", 1))
            assertEquals(emptyList(), s.memories("r2", 1))
            assertEquals(emptyList(), s.memories("r1", 2))
        }
    }
}
