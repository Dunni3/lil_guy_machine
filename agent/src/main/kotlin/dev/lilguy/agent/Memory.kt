package dev.lilguy.agent

import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus

/** Short-term memory: the last few intents the agent chose and how each ended. */
class Memory(private val capacity: Int = 8) {
    /** [result] stays null until a later observation reports how the intent ended. */
    data class Entry(val tick: Long, val intent: Intent, val result: IntentStatus? = null)

    private val entries = ArrayDeque<Entry>()

    fun record(tick: Long, intent: Intent) {
        entries.addLast(Entry(tick, intent))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Fills in the result of the latest entry once the observation says how [intent] ended. */
    fun settle(intent: Intent?, result: IntentStatus?) {
        val last = entries.lastOrNull() ?: return
        if (last.result == null && result != null && last.intent == intent && result.isFinal) {
            entries[entries.lastIndex] = last.copy(result = result)
        }
    }

    fun recent(): List<Entry> = entries.toList()

    private val IntentStatus.isFinal: Boolean
        get() = this is IntentStatus.Succeeded || this is IntentStatus.Failed || this is IntentStatus.Interrupted
}
