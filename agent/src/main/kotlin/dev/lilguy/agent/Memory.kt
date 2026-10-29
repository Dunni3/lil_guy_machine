package dev.lilguy.agent

import dev.lilguy.sim.Intent

/** Short-term memory: the last few intents the agent chose. */
class Memory(private val capacity: Int = 8) {
    data class Entry(val tick: Long, val intent: Intent)

    private val entries = ArrayDeque<Entry>()

    fun record(tick: Long, intent: Intent) {
        entries.addLast(Entry(tick, intent))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun recent(): List<Entry> = entries.toList()
}
