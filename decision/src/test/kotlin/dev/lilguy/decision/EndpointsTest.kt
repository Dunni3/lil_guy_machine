package dev.lilguy.decision

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointsTest {
    private val example = File("../config/endpoints.example.yaml")

    @Test
    fun `the example config parses`() {
        val config = Endpoints.load(example)
        assertEquals(setOf("hosted", "local"), config.endpoints.keys)
        val hosted = config.endpoints.getValue("hosted")
        assertEquals("openai_compat", hosted.kind)
        assertEquals("PROVIDER_API_KEY", hosted.apiKeyEnv)
        assertEquals(StructuredOutput.JsonSchema, hosted.structuredOutput)
        assertEquals(null, config.endpoints.getValue("local").apiKeyEnv)
        assertEquals("hosted", config.defaultEndpoint)
    }

    @Test
    fun `keys come only from the named environment variable`() {
        val config = Endpoints.load(example)
        val missing = assertFailsWith<IllegalArgumentException> { Endpoints.create(config, "hosted") { null } }
        assertTrue("PROVIDER_API_KEY" in missing.message!!)
        val model = Endpoints.create(config, "hosted") { if (it == "PROVIDER_API_KEY") "sk-test" else null }
        assertEquals("hosted:provider-model-name", model.id)
        assertTrue(model.capabilities.jsonSchema)
        (model as AutoCloseable).close()
        // No api_key_env: no key needed.
        (Endpoints.create(config, "local") { null } as AutoCloseable).close()
        assertFailsWith<IllegalArgumentException> { Endpoints.create(config, "nope") { null } }
    }

    @Test
    fun `strategy and tuning keys parse`() {
        val config = Endpoints.parse(
            """
            endpoints:
              t:
                base_url: http://x/v1
                model: m
                structured_output: tools
                strict: false
                temperature: 0.2
                send_temperature: false
                timeout_s: 12.5
                max_retries: 1
                reasoning_effort: low
            """.trimIndent(),
        )
        val t = config.endpoints.getValue("t")
        assertEquals(StructuredOutput.Tools, t.structuredOutput)
        assertEquals(false, t.strict)
        assertEquals(0.2, t.temperature)
        assertEquals(false, t.sendTemperature)
        assertEquals(12_500, t.timeout.inWholeMilliseconds)
        assertEquals(1, t.maxRetries)
        assertEquals("low", t.reasoningEffort)
        assertEquals(null, config.defaultEndpoint)
    }
}
