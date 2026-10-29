package dev.lilguy.sim

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class IntentSerializationTest {
    private val json = Json

    @Test
    fun `every intent round-trips through JSON`() {
        val intents: List<Intent> = listOf(
            Intent.MoveTo(Target.Tile(3, 4)),
            Intent.MoveTo(Target.Entity("door")),
            Intent.PickUp("key"),
            Intent.Use("key", on = "door"),
            Intent.Say("hello"),
            Intent.Wait(20),
        )
        for (intent in intents) {
            val encoded = json.encodeToString(Intent.serializer(), intent)
            assertEquals(intent, json.decodeFromString(Intent.serializer(), encoded), encoded)
        }
    }

    @Test
    fun `intents use short type tags`() {
        val encoded = json.encodeToString(Intent.serializer(), Intent.PickUp("key"))
        assertEquals("""{"type":"pick_up","itemId":"key"}""", encoded)
    }
}
