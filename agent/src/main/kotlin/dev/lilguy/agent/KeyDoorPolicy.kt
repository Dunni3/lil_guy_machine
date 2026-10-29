package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Target
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Hand-written policy for the key-and-door room: get the key, unlock the door, walk out. It reads
 * the observation out of the prompt, so it sees only what a language model would, and its reply
 * (an [AgentReply] with its rule as the thought) goes through the same schema check and decode as
 * a real backend's.
 *
 * [thinkTime] fakes model latency so a watched run looks like one; tests and the CLI use zero.
 */
class KeyDoorPolicy(
    private val thinkTime: Duration = Duration.ZERO,
    override val id: String = "scripted:key-door",
) : DecisionModel {
    override val capabilities = Capabilities(jsonSchema = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        if (thinkTime.isPositive()) delay(thinkTime)
        val obs = PromptBuilder.observationOf(request) ?: error("no observation in the prompt")
        val (intent, rationale) = choose(obs)
        val raw = json.encodeToString(AgentReply.serializer(), AgentReply(rationale, intent))
        val value = schema.decode(raw)
        return Decision(value, schema.rationaleOf(value), Usage(), started.elapsedNow(), raw)
    }

    private fun choose(obs: Observation): Pair<Intent, String> {
        val seen = obs.nearby.associateBy { it.kind }
        val door = seen["door"]
        val key = seen["key"]
        val exit = seen["exit"]
        val holdingKey = "key" in obs.self.inventory
        return when {
            obs.lastIntent == null ->
                Intent.Say("Locked in? Let's find a way out.") to "Fresh start. Say hello, then look for a key."
            exit != null && exit.distance < 0.5f ->
                Intent.Wait(40) to "I'm outside. Nothing left to do."
            door?.state == "open" && exit != null ->
                Intent.MoveTo(Target.Entity(exit.id)) to "The door is open, so I'll walk out."
            holdingKey && door != null && door.distance <= REACH ->
                Intent.Use("key", on = door.id) to "I'm next to the locked door with the key. Unlock it."
            holdingKey && door != null ->
                Intent.MoveTo(Target.Entity(door.id)) to "I have the key. The door is ${door.distance} tiles ${door.direction}."
            key != null && key.distance <= REACH ->
                Intent.PickUp(key.id) to "The key is within reach. Grab it."
            key != null ->
                Intent.MoveTo(Target.Entity(key.id)) to
                    "The door is locked. There's a key ${key.distance} tiles ${key.direction}; go get it."
            else ->
                Intent.Wait(20) to "I can't see a key or an open door. Wait and look again."
        }
    }

    private companion object {
        /** Euclidean tiles; covers the sim's "on or beside" reach, diagonals included. */
        const val REACH = 1.5f
        val json = Json
    }
}
