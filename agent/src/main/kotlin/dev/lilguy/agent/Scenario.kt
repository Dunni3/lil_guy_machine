package dev.lilguy.agent

import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.RoomWorld
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val KEY_DOOR_GOAL = "get out of the room. The door is locked."

/**
 * A fresh key-and-door room with one agent wired to [model] through the real loop. [timeout]
 * bounds one whole decision, retries included.
 */
fun keyDoorScenario(
    model: DecisionModel,
    timeout: Duration = 20.seconds,
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<RoomWorld, AgentRuntime> {
    val world = RoomWorld.create()
    val runtime = AgentRuntime(world, world.agentId, model, PromptBuilder(KEY_DOOR_GOAL, timeout), onDecision = onDecision)
    return world to runtime
}

/**
 * Runs the sim and the agent on the caller's thread, ticking only once the agent has submitted
 * whatever it was deciding. Deterministic, and as fast as the model allows: for the CLI and
 * tests. Run it on a single-threaded dispatcher (runBlocking is one). A network model suspends
 * rather than blocks, so it works here too; ticks still only advance between decisions. Stops when the room is solved, at
 * [maxTicks], or when [shouldStop] says so (checked before each tick). [afterStep] runs after
 * every tick. Returns the tick it stopped on.
 */
suspend fun runLockstep(
    world: RoomWorld,
    runtime: AgentRuntime,
    maxTicks: Long,
    shouldStop: () -> Boolean = { false },
    afterStep: () -> Unit = {},
): Long = coroutineScope {
    val agent = launch { runtime.run() }
    while (!world.snapshot().completed && world.tick < maxTicks) {
        while (world.awaitingDecision(world.agentId) && agent.isActive) {
            yield() // an in-process model decides and submits within this
            if (world.awaitingDecision(world.agentId)) delay(2) // a network model: wait without spinning
        }
        if (shouldStop()) break
        world.step()
        afterStep()
    }
    agent.cancelAndJoin()
    world.tick
}
