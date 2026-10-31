package dev.lilguy.agent

import dev.lilguy.sim.BedroomContent
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentDocsTest {
    @Test
    fun `the content schemas on the classpath are what the content classes generate`() {
        ContentDocs.schemaFiles(BedroomContent.default()).forEach { (name, text) ->
            val shipped = javaClass.getResourceAsStream("/content/bedroom/schema/$name")?.bufferedReader()?.use { it.readText() }
            assertEquals(text, shipped, "$name is out of date: run make content-docs")
        }
    }
}
