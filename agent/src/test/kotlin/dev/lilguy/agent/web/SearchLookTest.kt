package dev.lilguy.agent.web

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.MockChatServer
import dev.lilguy.agent.PromptBuilder
import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.EndpointConfig
import dev.lilguy.decision.OpenAiCompatBackend
import dev.lilguy.decision.WebResults
import dev.lilguy.decision.WebSearch
import dev.lilguy.decision.WebSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SearchLookTest {
    private class FakeWeb : WebSearch {
        val queries = mutableListOf<String>()
        override val id = "fake-web"
        override suspend fun search(query: String): WebResults {
            queries += query
            return WebResults("Match each string to the fifth fret of the one below.", listOf(WebSource("guitarshed.com", "https://guitarshed.example/tuning", null)), emptyList())
        }
    }

    @Test
    fun `a tool model searches the web before it acts, and the search reaches his memory, his next prompt and the log with his thought, and past the day of searches none is sent`() {
        val web = FakeWeb()
        val store = InMemoryStore()
        val memory = AgentMemory(store, "r", searcher = Searcher(web, perDay = 1))
        // Each turn: search, then wait with a thought.
        MockChatServer { _, n ->
            200 to if (n % 2 == 1) MockChatServer.toolCall("search", """{"query":"how to tune a guitar by ear"}""")
            else MockChatServer.toolCall("wait", """{"thought":"I'll try tuning it by ear tonight.","minutes":5}""")
        }.use { server ->
            val model = OpenAiCompatBackend(EndpointConfig(baseUrl = server.baseUrl, model = "mock", timeoutSeconds = 5.0), null)
            val records = mutableListOf<AgentRuntime.DecisionRecord>()
            val (world, runtime) = bedroomScenario(model, timeout = 30.seconds, longTerm = memory) { records += it }
            runBlocking { runLockstep(world, runtime, maxTicks = 20_000, shouldStop = { records.size >= 2 }) }

            assertEquals(listOf("how to tune a guitar by ear"), web.queries, "the second search is past the day's one")
            val (first, second) = memory.searches()
            assertEquals("I'll try tuning it by ear tonight.", first.thought)
            assertTrue(first.then.orEmpty().startsWith("wait"), first.then)
            assertTrue(second.refused && !second.found)
            // The look's answer went back to the model, and the search into his events and his next turn.
            assertTrue("fifth fret" in server.requests[1].toString())
            assertTrue(store.eventsAfter("r", 0, 100).any { it.kind == EventKind.Search && "I looked up \"how to tune a guitar by ear\" online" in it.text })
            val next = server.requests[2].toString()
            assertTrue(PromptBuilder.FOUND_HEADER in next && "fifth fret" in next)
            assertTrue("already searched 1 times today" in server.requests[3].toString())
            assertEquals(listOf("search", "search"), records.map { it.looks.single().name })
        }
    }
}
