package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.SchemaHints
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.Target
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a model sends back each turn: its reasoning, then the intent. "thought" comes first so a
 * model writing left to right reasons before it commits. Milestone 5 adds `remember` and `plan`;
 * new fields need a default so older logs still decode.
 */
@Serializable
@SerialName("reply")
data class AgentReply(
    val thought: String = "",
    val intent: Intent,
)

/** The action space as shown to people and models: one example of each intent with what it does. */
val ActionExamples: List<Pair<Intent, String>> = listOf(
    Intent.MoveTo(Target.Entity("door")) to "walk to an entity, stopping beside it if it blocks the way",
    Intent.MoveTo(Target.Tile(3, 4)) to "walk to a floor tile",
    Intent.PickUp("key") to "pick up an item on or beside your tile",
    Intent.Use("key", on = "door") to "use a held item on something beside you",
    Intent.Use("apple", on = null) to "use a held item by itself",
    Intent.Say("found it") to "say something out loud",
    Intent.Wait(20) to "do nothing for N ticks (20 ticks = 1 s of game time)",
)

/** One line per intent variant for schema descriptions and tool definitions. */
internal val VARIANT_DESCRIPTIONS = mapOf(
    "move_to" to "Walk to an entity (stopping beside it if it blocks the way, like a locked door) or to a floor tile.",
    "pick_up" to "Pick up an item on your tile or one of the 8 tiles around it.",
    "use" to "Use an item you are holding: on an entity beside you, or by itself when \"on\" is null.",
    "say" to "Say something out loud.",
    "wait" to "Do nothing for some ticks (20 ticks = 1 s of game time).",
    "do" to "Do an activity; the game walks you to where it happens.",
    "buy" to "Buy an item from the shop.",
    "think" to "Stop and think about what you want to do next and what you want to achieve.",
)

/**
 * The reply schema for one observation. Ids are enums of what the character can see right now
 * (and, for the item in `use`, what it holds), so constrained decoding can't name anything else.
 * A variant with no valid id at all, such as `use` with empty hands, is left out.
 */
fun replySchema(obs: Observation): ActionSchema<AgentReply> {
    val inView = obs.nearby.map { it.id }
    val hints = SchemaHints(
        descriptions = VARIANT_DESCRIPTIONS + mapOf(
            "reply.thought" to "Your reasoning in a sentence or two, before you act.",
            "reply.intent" to "The one action to take now.",
            "entity.id" to "An id from nearby.",
            "pick_up.itemId" to "An id from nearby.",
            "use.itemId" to "An item id from your inventory.",
            "use.on" to "An id from nearby, or null to use the item by itself.",
            "tile.x" to "Tile column; x grows east.",
            "tile.y" to "Tile row; y grows south.",
        ),
        enums = mapOf(
            "entity.id" to inView,
            "pick_up.itemId" to inView,
            "use.itemId" to obs.self.inventory,
            "use.on" to inView,
            // No activities or shop in this room, so the schema leaves do and buy out.
            "do.activity" to emptyList(),
            "buy.item" to emptyList(),
        ),
        ranges = mapOf("wait.ticks" to 1..GridWorld.MAX_WAIT),
        // Key-and-door is the quick backend test; thinking is for the bedroom.
        omit = setOf("think"),
    )
    return ActionSchema.of("reply", AgentReply.serializer(), hints) { it.thought }
}
