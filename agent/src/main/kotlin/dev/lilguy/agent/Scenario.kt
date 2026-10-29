package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.Intent
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.Target
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject

/** Schema generation lands with milestone 3; until then the model is sent an empty schema. */
val IntentSchema = ActionSchema("intent", Intent.serializer(), JsonObject(emptyMap()))

const val KEY_DOOR_GOAL = "get out of the room. The door is locked."

/** The action space as shown to people: one example of each intent with what it does. */
val ActionExamples: List<Pair<Intent, String>> = listOf(
    Intent.MoveTo(Target.Entity("door")) to "walk to an entity (or a tile)",
    Intent.PickUp("key") to "pick up an item on or beside you",
    Intent.Use("key", on = "door") to "use a held item on something beside you",
    Intent.Say("found it") to "speak",
    Intent.Wait(20) to "do nothing for N ticks (20 = 1 s)",
)

/** A fresh key-and-door room with one agent wired to [model] through the real loop. */
fun keyDoorScenario(
    model: DecisionModel,
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<RoomWorld, AgentRuntime> {
    val world = RoomWorld.create()
    val runtime = AgentRuntime(world, world.agentId, model, IntentSchema, PromptBuilder(KEY_DOOR_GOAL), onDecision = onDecision)
    return world to runtime
}

/**
 * Runs the sim and the agent on the caller's thread, ticking only once the agent has submitted
 * whatever it was deciding. Deterministic, and as fast as the model allows: for the CLI and
 * tests. Run it on a single-threaded dispatcher (runBlocking is one) with a model that doesn't
 * sleep. [afterStep] runs after every tick. Returns the tick it stopped on.
 */
suspend fun runLockstep(
    world: RoomWorld,
    runtime: AgentRuntime,
    maxTicks: Long,
    afterStep: () -> Unit = {},
): Long = coroutineScope {
    val agent = launch { runtime.run() }
    while (!world.snapshot().completed && world.tick < maxTicks) {
        while (world.awaitingDecision(world.agentId) && agent.isActive) yield()
        world.step()
        afterStep()
    }
    agent.cancelAndJoin()
    world.tick
}
