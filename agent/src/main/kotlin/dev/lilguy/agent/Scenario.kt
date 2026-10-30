package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.JOURNAL_ACTIVITY
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
    longTerm: AgentMemory? = null,
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<RoomWorld, AgentRuntime> {
    val world = RoomWorld.create()
    val runtime = AgentRuntime(world, world.agentId, model, PromptBuilder(KEY_DOOR_GOAL, timeout), longTerm = longTerm, onDecision = onDecision)
    return world to runtime
}

/**
 * The bedroom with one agent wired to [model]. [ticksPerMinute] is the clock scale: the knob
 * that sets how much game time passes per tick, and so how long the guy stands still while a
 * real-time model thinks. [granted] unlocks gated content (tiers above 0) by id.
 */
fun bedroomScenario(
    model: DecisionModel,
    timeout: Duration = 20.seconds,
    ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
    content: BedroomContent = BedroomContent.default(),
    longTerm: AgentMemory? = null,
    granted: Set<String> = emptySet(),
    /** Picks the commissions. */
    seed: Long = 0,
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<BedroomWorld, AgentRuntime> {
    // Without a journal writer, journaling writes nothing, so going without it doesn't sink his mood.
    val rules = if (longTerm?.journaling == true) content else content.copy(needs = content.needs.copy(neglect = emptyList()))
    val world = BedroomWorld.create(rules, ticksPerMinute, granted = granted, seed = seed)
    longTerm?.studio?.connect(world)
    val journaling = longTerm?.journaling == true
    val thinking = longTerm?.thinking == true
    // What he's told about: without a journal writer the journal would cost an hour and write nothing, so the prompt doesn't
    // mention it and a picking model isn't offered it. The reply schema still takes it, as the sim does (the scripted baseline journals).
    val shown = world.content.let { c -> if (journaling) c else c.copy(activities = c.activities.filter { it.id != JOURNAL_ACTIVITY }) }
    val prompts = PromptBuilder(
        system = BedroomPrompt.system(shown, ticksPerMinute, thinking),
        timeout = timeout,
        choices = { obs, recent -> BedroomPrompt.choices(obs, recent, ticksPerMinute, shown, journaling, thinking) },
        stamp = BedroomPrompt.stamp(world.clock),
        persona = Persona.text(shown),
    )
    val runtime = AgentRuntime(
        world, world.agentId, model, prompts,
        schemaFor = { BedroomPrompt.replySchema(it, ticksPerMinute, thinking) },
        longTerm = longTerm,
        fallback = { Intent.Wait(BedroomPrompt.WAIT_TICKS) },
        thinkEvery = if (thinking) shown.think.every else null,
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
        longTerm: AgentMemory? = null,
        /** Bedroom: gated content to unlock, by id. */
        granted: Set<String> = emptySet(),
        onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
    ): Pair<GridWorld, AgentRuntime> = when (this) {
        KeyDoor -> keyDoorScenario(model, timeout, longTerm, onDecision)
        Bedroom -> bedroomScenario(model, timeout, ticksPerMinute, longTerm = longTerm, granted = granted, onDecision = onDecision)
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
