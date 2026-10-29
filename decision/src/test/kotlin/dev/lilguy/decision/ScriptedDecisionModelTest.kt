package dev.lilguy.decision

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedDecisionModelTest {
    @Serializable
    sealed interface Choice {
        @Serializable @SerialName("left") data object Left : Choice
        @Serializable @SerialName("right") data class Right(val steps: Int) : Choice
    }

    private val schema = ActionSchema("choice", Choice.serializer(), JsonObject(emptyMap()))
    private val request = DecisionRequest(system = "pick one", messages = emptyList())

    @Test
    fun `replies decode into the requested type in order`() = runTest {
        val model = ScriptedDecisionModel(listOf("""{"type":"right","steps":2}""", """{"type":"left"}"""))
        assertEquals(Choice.Right(2), model.decide(request, schema).value)
        assertEquals(Choice.Left, model.decide(request, schema).value)
    }

    @Test
    fun `running out of replies is an error`() = runTest {
        val model = ScriptedDecisionModel(emptyList())
        assertFailsWith<IllegalStateException> { model.decide(request, schema) }
    }
}
