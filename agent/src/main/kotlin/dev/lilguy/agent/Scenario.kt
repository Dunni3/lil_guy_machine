package dev.lilguy.agent

import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.RoomWorld
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import dev.lilguy.sim.Intent
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
 * The bedroom with one agent wired to [model]. [ticksPerMinute] is the clock scale: the knob
 * that sets how much game time passes per tick, and so how long the guy stands still while a
 * real-time model thinks.
 */
fun bedroomScenario(
    model: DecisionModel,
    timeout: Duration = 20.seconds,
    ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
    content: BedroomContent = BedroomContent.default(),
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<BedroomWorld, AgentRuntime> {
    val world = BedroomWorld.create(content, ticksPerMinute)
    val prompts = PromptBuilder(
        system = BedroomPrompt.system(content, ticksPerMinute),
        timeout = timeout,
        choices = { obs, recent -> BedroomPrompt.choices(obs, recent, ticksPerMinute) },
        stamp = BedroomPrompt.stamp(world.clock),
    )
    val runtime = AgentRuntime(
        world, world.agentId, model, prompts,
        schemaFor = { BedroomPrompt.replySchema(it, ticksPerMinute) },
        fallback = { Intent.Wait(BedroomPrompt.WAIT_TICKS) },
        onDecision = onDecision,
    )
    return world to runtime
}

/** The rooms a run can use. Key-and-door is the quick test every backend must pass; the bedroom is the life sim. */
enum class Scenario(val id: String) {
    KeyDoor("key-door"),
    Bedroom("bedroom");

    /** The scripted policy for this room, with [thinkTime] of fake latency. */
    fun scriptedPolicy(thinkTime: Duration = Duration.ZERO): DecisionModel = when (this) {
        KeyDoor -> KeyDoorPolicy(thinkTime)
        Bedroom -> BedroomPolicy(thinkTime)
    }

    fun create(
        model: DecisionModel,
        timeout: Duration = 20.seconds,
        ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
        onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
    ): Pair<GridWorld, AgentRuntime> = when (this) {
        KeyDoor -> keyDoorScenario(model, timeout, onDecision)
        Bedroom -> bedroomScenario(model, timeout, ticksPerMinute, onDecision = onDecision)
    }

    companion object {
        fun parse(id: String): Scenario = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown scenario '$id'; use ${entries.joinToString(" or ") { it.id }}")
    }
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
    world: GridWorld,
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
