package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Personality
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.VerbSpec
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
    // A verb whose result nothing writes (no thinker) isn't offered to models. The sim still has it, and the reply schema
    // still takes it.
    fun offered(v: VerbSpec): Boolean = longTerm?.offers(v) ?: v.authored.none { it.essential }
    val world = BedroomWorld.create(content, ticksPerMinute, granted = granted, seed = seed)
    longTerm?.connect(world)
    // What he's told about is what he knows: a book can teach him a verb mid-run, so it's rebuilt when that changes.
    var lastKnown: Pair<BedroomContent, BedroomContent>? = null
    fun shown(): BedroomContent {
        val known = world.knownContent()
        lastKnown?.let { if (it.first === known) return it.second }
        return known.copy(verbs = known.verbs.filter { offered(it) }).also { lastKnown = known to it }
    }
    val start = shown()
    // Who he is opens the system prompt and changes with his self-model, once a night.
    val self = { longTerm?.selfText() }
    val prompts = PromptBuilder(
        systemFor = { BedroomPrompt.system(shown(), ticksPerMinute, self()) },
        timeout = timeout,
        choices = { obs, recent -> BedroomPrompt.choices(obs, recent, ticksPerMinute, shown()) },
        stamp = BedroomPrompt.stamp(world.clock),
        persona = { Persona.text(shown(), self()) },
        self = self,
    )
    val runtime = AgentRuntime(
        world, world.agentId, model, prompts,
        schemaFor = { BedroomPrompt.replySchema(it, ticksPerMinute) },
        longTerm = longTerm,
        fallback = { Intent.Wait(BedroomPrompt.WAIT_TICKS) },
        reflection = start.reflection?.takeIf { start.verb(it.verb) != null }?.let { it.verb to it.every },
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
        /** Bedroom: his personality, in place of the content's default. */
        personality: Personality? = null,
        onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
    ): Pair<GridWorld, AgentRuntime> = when (this) {
        KeyDoor -> keyDoorScenario(model, timeout, longTerm, onDecision)
        Bedroom -> {
            val content = BedroomContent.default().let { c -> personality?.let(c::withPersonality) ?: c }
            bedroomScenario(model, timeout, ticksPerMinute, content, longTerm = longTerm, granted = granted, onDecision = onDecision)
        }
    }

    /**
     * Maps an intent from a decision log written before verbs took arguments onto today's action
     * space: in the bedroom, use(item, on) is the verb whose `what` and `on` those items fill, and
     * think is the reflection verb (do(think)).
     */
    fun upgrade(content: BedroomContent = BedroomContent.default()): (Intent) -> Intent = { intent ->
        when {
            this == Bedroom && intent == Intent.Think -> content.reflection?.let { Intent.Do(it.verb) } ?: intent
            this == Bedroom && intent is Intent.Use -> content.verbs.firstOrNull { v ->
                val what = v.args["what"] ?: return@firstOrNull false
                val on = v.args["on"] ?: return@firstOrNull false
                content.item(intent.itemId)?.names?.contains(what) == true && intent.on != null && content.item(intent.on!!)?.names?.contains(on) == true
            }?.let { Intent.Do(it.id, intent.itemId, intent.on) } ?: intent
            else -> intent
        }
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
