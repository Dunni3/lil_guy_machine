package dev.lilguy.decision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class BeamSearchTest {
    private fun option(label: String, group: String?) = ChoiceSet.Option(label, "do $label", JsonObject(emptyMap()), group)

    @Test
    fun `each depth is one batch over the beam, the beam keeps a path greedy would drop, and a last question picks among the finalists`() {
        val set = ChoiceSet(
            instructions = "What next?",
            state = JsonObject(emptyMap()),
            options = listOf(
                option("eat", "upkeep"), option("sleep", "upkeep"),
                option("record", "music"), option("guitar", "music"),
                option("read", "books"),
                option("lonely", "empty_parent"),
                option("wait", null),
            ),
            groups = listOf(
                ChoiceSet.Group("upkeep", "look after himself"),
                ChoiceSet.Group("leisure", "for fun"),
                ChoiceSet.Group("music", "music", parent = "leisure"),
                ChoiceSet.Group("books", "books", parent = "leisure"),
                ChoiceSet.Group("empty_parent", "holds one thing"),
                ChoiceSet.Group("nothing", "holds nothing"),
            ),
        )
        val batches = mutableListOf<Map<String, BeamSearch.Question>>()
        val answers = mapOf(
            // Upkeep leads at the top, but each of its options is weak; music's guitar is strong.
            "top" to mapOf("group:upkeep" to 0.5, "group:leisure" to 0.4, "lonely" to 0.05, "wait" to 0.05),
            "in_upkeep" to mapOf("eat" to 0.5, "sleep" to 0.5),
            "in_leisure" to mapOf("group:music" to 0.9, "read" to 0.1),
            "in_music" to mapOf("record" to 0.1, "guitar" to 0.9),
            BeamSearch.LAST to mapOf("guitar" to 0.7, "eat" to 0.3),
        )
        val result = runBlocking {
            BeamSearch(width = 2).pick(set) { qs -> batches += qs; qs.keys.associateWith { answers.getValue(it) } }
        }

        // A group of one is its option ("lonely"), an empty group is gone, and books (one option) is that option.
        assertEquals(setOf("group:upkeep", "group:leisure", "lonely", "wait"), batches[0].getValue("top").options.keys)
        assertEquals(setOf("group:music", "read"), batches[1].getValue("in_leisure").options.keys)
        // Depth 2 asks both paths in one batch; depth 3 only music, since upkeep's options are already leaves.
        assertEquals(listOf(setOf("top"), setOf("in_upkeep", "in_leisure"), setOf("in_music"), setOf(BeamSearch.LAST)), batches.map { it.keys })
        // leisure > music > guitar is 0.4 * 0.9 * 0.9 = 0.32, ahead of eat's 0.25; greedy would have stayed in upkeep.
        assertEquals(listOf(listOf("group:leisure", "group:music", "guitar"), listOf("group:upkeep", "eat")), result.finalists.map { it.path })
        assertEquals("guitar", result.option.label)
    }
}
