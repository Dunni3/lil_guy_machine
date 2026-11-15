package dev.lilguy.decision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    private fun usage(json: String) = OpenAiCompatBackend.usageOf(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `thinking that is only in total_tokens counts as output`() {
        // Gemini's OpenAI-compatible reply: 349 thinking tokens are billed but not in completion_tokens.
        assertEquals(Usage(13, 374), usage("""{"usage":{"prompt_tokens":13,"completion_tokens":25,"total_tokens":387}}"""))
        assertEquals(Usage(13, 25), usage("""{"usage":{"prompt_tokens":13,"completion_tokens":25}}"""))
    }
}
