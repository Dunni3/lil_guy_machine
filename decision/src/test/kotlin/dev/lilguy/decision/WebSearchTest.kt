package dev.lilguy.decision

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSearchTest {
    @Test
    fun `a grounded Gemini reply becomes what the results say, each page with what it backs up, at the page its link redirects to`() {
        var key: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val root = "http://127.0.0.1:${server.address.port}"
        // The shape of a real reply (gemini-3.5-flash-lite with google_search, trimmed).
        val reply = """
            {"candidates":[{"content":{"parts":[{"text":"Tune by ear with the **5th fret** method.\nMatch each string to the one below."}]},
              "groundingMetadata":{"webSearchQueries":["tune a guitar by ear"],
                "groundingChunks":[{"web":{"uri":"$root/grounding-api-redirect/a","title":"guitarshed.com"}},{"web":{"uri":"https://example.net/b","title":"reddit.com"}}],
                "groundingSupports":[{"segment":{"text":"Tune by ear with the **5th fret** method."},"groundingChunkIndices":[0]},
                  {"segment":{"text":"Match each string to the one below."},"groundingChunkIndices":[0,1]}]}}],
             "usageMetadata":{"promptTokenCount":31,"candidatesTokenCount":20,"thoughtsTokenCount":5}}
        """.trimIndent()
        server.createContext("/") { ex ->
            if (ex.requestMethod == "HEAD") {
                ex.responseHeaders.add("Location", "https://guitarshed.example/tuning")
                ex.sendResponseHeaders(302, -1)
            } else {
                key = ex.requestHeaders.getFirst("x-goog-api-key")
                val bytes = reply.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
        try {
            val search = GeminiSearch(SearchConfig(model = "m", baseUrl = "$root/v1beta", timeoutSeconds = 5.0), "k")
            val r = runBlocking { search.search("how do I tune a guitar by ear") }
            search.close()
            assertEquals("k", key)
            assertEquals("Tune by ear with the 5th fret method. Match each string to the one below.", r.summary)
            assertEquals(
                listOf(
                    WebSource("guitarshed.com", "https://guitarshed.example/tuning", "Tune by ear with the 5th fret method. … Match each string to the one below."),
                    WebSource("reddit.com", "https://example.net/b", "Match each string to the one below."),
                ),
                r.sources,
            )
            assertEquals(listOf("tune a guitar by ear"), r.queries)
            assertEquals(Usage(31, 25), r.usage)
        } finally {
            server.stop(0)
        }
    }
}
