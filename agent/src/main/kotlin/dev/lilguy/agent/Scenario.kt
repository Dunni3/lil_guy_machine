package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.Describe
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomState
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
    /** A checkpoint to carry on from, in place of a fresh room; what the content no longer has goes to [dropped]. */
    from: BedroomState? = null,
    dropped: (List<String>) -> Unit = {},
    onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
): Pair<BedroomWorld, AgentRuntime> {
    // A model that calls tools thinks in its own words as it decides and can look things up first, so it isn't offered
    // the reflection verb (think) and isn't made to think every few decisions. It ends a turn with think when it has
    // only thought and looked for as long as a turn allows: he sat and thought (BedroomTools.of's ponder).
    val tooling = model.capabilities.let { it.toolCalls && !it.closedChoice }
    val reflecting = content.reflection?.verb?.takeIf { !tooling }
    val pondering = content.reflection?.verb?.takeIf { tooling && content.verb(it) != null }
    // A verb whose result nothing writes (no thinker) isn't offered to models. The sim still has it, and the reply schema
    // still takes it.
    fun offered(v: VerbSpec): Boolean = (reflecting != null || v.id != content.reflection?.verb) && (longTerm?.offers(v) ?: v.authored.none { it.essential })
    val world = from?.let { BedroomWorld.restore(it, content, ticksPerMinute, granted = granted, seed = seed).also { r -> dropped(r.dropped) }.world }
        ?: BedroomWorld.create(content, ticksPerMinute, granted = granted, seed = seed)
    longTerm?.connect(world)
    // What he's told about is what he knows: a book can teach him a verb mid-run, so it's rebuilt when that changes.
    var lastKnown: Pair<BedroomContent, BedroomContent>? = null
    fun shown(): BedroomContent {
        val known = world.knownContent()
        lastKnown?.let { if (it.first === known) return it.second }
        return known.copy(verbs = known.verbs.filter { offered(it) }).also { lastKnown = known to it }
    }
    val start = shown()
    // His map changes only with what he knows (and later the house), so it's kept per known content.
    var lastMap: Pair<BedroomContent, String?>? = null
    var lastExplored: Set<String> = emptySet()
    fun map(): String? {
        val known = shown()
        // What he knows covers the patches he's found; exploring a place with none changes only what he's explored.
        lastMap?.let { if (it.first === known && lastExplored == world.exploredPlaces()) return it.second }
        lastExplored = world.exploredPlaces()
        return WorldMap.text(world, known).also { lastMap = known to it }
    }
    // Who he is opens the system prompt and changes with his self-model, once a night.
    val self = { longTerm?.selfText() }
    val prompts = PromptBuilder(
        systemFor = { BedroomPrompt.system(shown(), ticksPerMinute, self(), map = map()) },
        timeout = timeout,
        choices = { obs, recent -> BedroomPrompt.choices(obs, recent, ticksPerMinute, shown()) },
        stamp = BedroomPrompt.stamp(world.clock),
        persona = { Persona.text(shown(), self()) },
        self = self,
        view = { BedroomPrompt.deciding(it, shown()) },
        // A model that calls tools calls his verbs.
        toolTurn = PromptBuilder.ToolTurn(
            system = { BedroomPrompt.system(shown(), ticksPerMinute, self(), tools = true, map = map(), looks = setOfNotNull("recall".takeIf { longTerm != null }, "ask".takeIf { longTerm?.canAsk == true }, "search".takeIf { longTerm?.canSearch == true })) },
            tools = { obs ->
                val looks = longTerm?.let { m ->
                    BedroomTools.looks(
                        recall = { q -> m.search(q, obs).ifEmpty { listOf("Nothing comes to mind.") }.joinToString("\n") },
                        ask = if (!m.canAsk) null else { q -> m.ask(q, obs, Describe.state(obs))?.reply?.let { it.text ?: "No answer came (${it.error})." } ?: "No one can answer that now." },
                        search = if (!m.canSearch) null else { q -> m.searchWeb(q, obs)?.answer() ?: "You can't search right now." },
                    )
                }.orEmpty()
                BedroomTools.of(obs, ticksPerMinute, looks, ponder = pondering?.let { Intent.Do(it) })
            },
            status = BedroomTools::view,
            call = { BedroomTools.call(it, ticksPerMinute) },
            // Left to itself a model keeps to the room's business and never looks outward, so until he has searched today, he's reminded he can.
            note = { obs ->
                longTerm?.takeIf { m -> m.canSearch && m.searches().none { it.day == obs.clock?.day && !it.refused } }
                    ?.let { BedroomTools.SEARCH_NOTE }
            },
        ),
    )
    val runtime = AgentRuntime(
        world, world.agentId, model, prompts,
        // The reply schema takes what he's offered, and think for a turn that ends in thought.
        schemaFor = { obs ->
            val known = shown()
            val takes = pondering?.takeIf { known.verb(it) == null }?.let { v -> known.copy(verbs = known.verbs + listOfNotNull(world.content.verb(v))) } ?: known
            BedroomPrompt.replySchema(BedroomPrompt.deciding(obs, takes), ticksPerMinute)
        },
        longTerm = longTerm,
        fallback = { Intent.Wait(BedroomPrompt.WAIT_TICKS) },
        reflection = start.reflection?.takeIf { reflecting != null && start.verb(it.verb) != null }?.let { it.verb to it.every },
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
        /** Bedroom: items he starts with on top of the content's, id to count (a dev aid for screenshots); he knows them and what's taught with them, so no book teaches those. */
        startItems: Map<String, Int> = emptyMap(),
        /** Bedroom: the money he starts with, in place of the content's (a dev aid for trying a late-game state). */
        startMoney: Int? = null,
        /** Bedroom: a checkpoint to carry on from ([bedroomScenario]). */
        from: BedroomState? = null,
        dropped: (List<String>) -> Unit = {},
        onDecision: (AgentRuntime.DecisionRecord) -> Unit = {},
    ): Pair<GridWorld, AgentRuntime> = when (this) {
        KeyDoor -> keyDoorScenario(model, timeout, longTerm, onDecision)
        Bedroom -> {
            val content = BedroomContent.default().let { c -> personality?.let(c::withPersonality) ?: c }
                .let { c ->
                    // Starting with a taught item, he knows what the book teaches with it: the guitar and playing it.
                    if (startItems.isEmpty()) c
                    else c.copy(start = c.start.copy(items = c.start.items + startItems), books = c.books.map { b -> if (b.teaches.any { it in startItems }) b.copy(teaches = emptyList()) else b })
                }.let { c -> if (startMoney == null) c else c.copy(start = c.start.copy(money = startMoney)) }
            bedroomScenario(model, timeout, ticksPerMinute, content, longTerm = longTerm, granted = granted, from = from, dropped = dropped, onDecision = onDecision)
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
 * [maxTicks], or when [shouldStop] says so (checked before each tick). [beforeStep] and [afterStep]
 * run around every tick. Returns the tick it stopped on.
 */
suspend fun runLockstep(
    world: GridWorld,
    runtime: AgentRuntime,
    maxTicks: Long,
    shouldStop: () -> Boolean = { false },
    beforeStep: () -> Unit = {},
    afterStep: () -> Unit = {},
): Long = coroutineScope {
    val agent = launch { runtime.run() }
    while (!world.snapshot().completed && world.tick < maxTicks) {
        while (world.awaitingDecision(world.agentId) && agent.isActive) {
            yield() // an in-process model decides and submits within this
            if (world.awaitingDecision(world.agentId)) delay(2) // a network model: wait without spinning
        }
        if (shouldStop()) break
        beforeStep()
        world.step()
        afterStep()
    }
    agent.cancelAndJoin()
    world.tick
}
