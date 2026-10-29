package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.ChoiceSet
import dev.lilguy.decision.SchemaHints
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How the bedroom is put to a model: the system prompt, the action examples, the per-request
 * reply schema and the closed choice set. The prompt describes his situation ("you live here;
 * you need money for food and anything you want") rather than giving him an objective; needs
 * and wants do the rest. Numbers come from the content file, so the prompt can't drift from
 * the rules.
 */
object BedroomPrompt {
    /** About half an hour of game time at the default scale; the choice set's "wait". */
    const val WAIT_TICKS = 150

    fun examples(ticksPerMinute: Int): List<Pair<Intent, String>> = listOf(
        Intent.Do("sketch") to "do an activity from `activities` that isn't locked; the game walks you there",
        Intent.Buy("canvas") to "buy something from `shop` that you can afford; it arrives at the door",
        Intent.Use("jazz_record", on = "record_player") to "use an item you own on another, as listed in `uses`",
        Intent.MoveTo(Target.Entity("bed")) to "walk to something in the room",
        Intent.Say("finally, an easel") to "say something out loud",
        Intent.Wait(WAIT_TICKS) to "do nothing for N ticks, at most ${GridWorld.MAX_WAIT} ($ticksPerMinute ticks = 1 game minute)",
    )

    fun system(content: BedroomContent, ticksPerMinute: Int): String {
        val r = content.needs
        val actions = examples(ticksPerMinute).joinToString("\n") { (intent, what) ->
            "- ${PromptBuilder.tag(intent)}: $what\n  ${PromptBuilder.exampleJson.encodeToString(Intent.serializer(), intent)}"
        }
        val art = content.items.filter { it.sellPrice != null }.joinToString(" and ") { "a ${it.name} sells for $${it.sellPrice}" }
        val makers = content.activities.filter { it.produces.isNotEmpty() }.joinToString(" and ") { it.id }
        val work = content.activities.filter { it.work }.joinToString(", ") { it.id }
        val floor = r.workFloor.joinToString(" or ") { "${it.need} is below ${it.below?.toInt()}" }
        val sellAt = content.activities.firstOrNull { it.sells != null }
        return """
            |You are a little guy. You live in this bedroom. You need money for food and for anything you want to own. You'll be living here for a long time. Each turn you choose what to do next; the game carries it out, then shows you how things stand.
            |
            |How your life works:
            |- Time: the clock shows the day and the time. Activities take game minutes or hours, and you choose again when one finishes.
            |- Needs, each from 0 to 100. Energy falls ${-r.awakeEnergyPerHour.toInt()} an hour while you're awake and comes back while you sleep. Fullness falls ${-r.fullnessPerHour.toInt()} an hour; eating brings it back. Mood drifts ${r.moodDriftPerHour.toInt()} an hour toward ${r.moodBaseline.toInt()} plus your room's comfort; nice things raise it for a while, and decor raises comfort. Below ${r.low.toInt()} energy or fullness, mood falls ${-r.lowMoodPerHour.toInt()} an hour instead. When $floor you can't work ($work).
            |- Money: $makers make art, and ${sellAt?.id ?: "selling"} sells everything you hold at the door: $art. The shop delivers to the door, and you pay when it arrives.
            |- `activities` lists everything you can do, with how long it takes and what it gives, or why it's locked. `shop` lists what's for sale, its price, whether you can afford it, and what it's for. `uses` lists things you can use on other things.
            |- Nothing ends the game. An action that can't be done fails at once, and lastResult says why. Read it and choose something else rather than repeating it.
            |
            |Actions (the "intent" field), each with an example:
            |$actions
            |
            |Reply with a JSON object {"thought": ..., "intent": ...}. "thought" is your reasoning in a sentence or two; "intent" is the one action to take now. Use only ids from the observation.
            |
            |The observation is JSON:
            |- clock: day and time. money: dollars. needs: energy, fullness and mood, 0 to 100.
            |- holding: art you're carrying, to sell. owns: everything else you own, id to count. music: what's playing, if anything.
            |- activities, uses, shop: your options, as above.
            |- self and nearby: where you are and the furniture around you, with tile x and y. localMap: the room, one string per row, north at the top.
            |- lastIntent and lastResult: your previous action and how it ended (succeeded, failed with a reason, or interrupted).
            |- recentEvents: the last few things that happened, stamped with day and time.
        """.trimMargin()
    }

    /**
     * The reply schema for one observation: do() takes only activities that aren't locked, buy()
     * only what he can afford, use() only the uses on offer, so constrained decoding can't pick
     * anything the sim would refuse for those reasons. There is nothing to pick up in the
     * bedroom, so pick_up is left out.
     */
    fun replySchema(obs: Observation, ticksPerMinute: Int): ActionSchema<AgentReply> {
        val uses = obs.uses.orEmpty()
        val hints = SchemaHints(
            descriptions = VARIANT_DESCRIPTIONS + mapOf(
                "reply.thought" to "Your reasoning in a sentence or two, before you act.",
                "reply.intent" to "The one action to take now.",
                "do.activity" to "An activity from `activities` that isn't locked.",
                "buy.item" to "An item from `shop` that you can afford.",
                "use.itemId" to "An item from `uses`.",
                "use.on" to "What to use it on, from `uses`.",
                "entity.id" to "An id from nearby.",
                "wait" to "Do nothing for some ticks ($ticksPerMinute ticks = 1 game minute).",
                "tile.x" to "Tile column; x grows east.",
                "tile.y" to "Tile row; y grows south.",
            ),
            enums = mapOf(
                "do.activity" to obs.activities.orEmpty().filter { it.locked == null }.map { it.id },
                "buy.item" to obs.shop.orEmpty().filter { it.affordable }.map { it.id },
                "use.itemId" to uses.map { it.itemId }.distinct(),
                "use.on" to uses.map { it.on }.distinct(),
                "entity.id" to obs.nearby.map { it.id },
                "pick_up.itemId" to emptyList(),
            ),
            ranges = mapOf("wait.ticks" to 1..GridWorld.MAX_WAIT),
        )
        return ActionSchema.of("reply", AgentReply.serializer(), hints) { it.thought }
    }

    /**
     * The decision as a closed set: each activity that isn't locked, each use on offer, each
     * purchase he can afford, and waiting half an hour. Walking and talking are left out; the
     * activities walk him where he needs to be. The state is the life part of the observation
     * (no map or coordinates), plus his recent actions.
     *
     * Options are ordered activities (in content order), uses, purchases, wait: Jev leans toward
     * earlier options, so doing comes before spending and waiting is last.
     */
    fun choices(obs: Observation, recent: List<Memory.Entry>, ticksPerMinute: Int): ChoiceSet {
        val options = buildList {
            obs.activities.orEmpty().filter { it.locked == null }.forEach { a ->
                add(option(Intent.Do(a.id), "Do ${a.id}: takes ${a.takes}; gives ${a.gives}."))
            }
            obs.uses.orEmpty().forEach { u ->
                add(option(Intent.Use(u.itemId, on = u.on), "Put the ${u.itemId} on the ${u.on}: takes ${u.takes}; gives ${u.gives}."))
            }
            obs.shop.orEmpty().filter { it.affordable }.forEach { s ->
                add(option(Intent.Buy(s.id), "Buy ${s.id} for $${s.price}, leaving $${(obs.money ?: 0) - s.price}: ${s.gives}."))
            }
            add(option(Intent.Wait(WAIT_TICKS), "Wait about ${WAIT_TICKS / ticksPerMinute} minutes and do nothing."))
        }
        val state = buildJsonObject {
            put("situation", "The character lives in this bedroom. He needs money for food and for anything he wants to own. His needs fall over time; nothing ends the game.")
            obs.clock?.let { put("time", "day ${it.day}, ${it.time}") }
            put("money", obs.money ?: 0)
            obs.needs?.let { n ->
                putJsonObject("needs") {
                    put("energy", n.energy)
                    put("fullness", n.fullness)
                    put("mood", n.mood)
                }
            }
            putJsonArray("holding") { obs.holding.orEmpty().forEach { add(it) } }
            putJsonObject("owns") { obs.owns.orEmpty().forEach { (id, n) -> put(id, n) } }
            obs.music?.let { put("music", it) }
            putJsonArray("activities") {
                obs.activities.orEmpty().forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        a.locked?.let { put("locked", it) }
                        a.takes?.let { put("takes", it) }
                        a.gives?.let { put("gives", it) }
                    })
                }
            }
            putJsonArray("shop") {
                obs.shop.orEmpty().forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("price", s.price)
                        put("affordable", s.affordable)
                        put("gives", s.gives)
                    })
                }
            }
            put("last_action", obs.lastIntent?.let { describe(it) + ": " + result(obs.lastResult) } ?: "none yet")
            if (recent.isNotEmpty()) {
                putJsonArray("recent_actions") { recent.forEach { add(describe(it.intent) + ": " + result(it.result)) } }
            }
            if (obs.recentEvents.isNotEmpty()) putJsonArray("recent_events") { obs.recentEvents.forEach { add(it) } }
        }
        return ChoiceSet(
            instructions = "What should the character in `state` do next? He lives here for the long run and looks after himself: his `needs` fall over time, " +
                "and he needs money for food and for anything he wants. Choose what serves him best over the coming days, not just the next hour. " +
                "Read `state.last_action` and `state.recent_actions`: don't repeat an action that just failed.",
            state = state,
            options = options,
        )
    }

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun option(intent: Intent, description: String) = ChoiceSet.Option(
        label = label(intent),
        description = description,
        reply = json.encodeToJsonElement(AgentReply.serializer(), AgentReply(description, intent)).jsonObject,
    )

    /** "do:sketch", "buy:canvas", "use:jazz_record:record_player", "wait:150". */
    private fun label(intent: Intent): String = when (intent) {
        is Intent.Do -> "do:${intent.activity}"
        is Intent.Buy -> "buy:${intent.item}"
        is Intent.Use -> "use:${intent.itemId}" + (intent.on?.let { ":$it" } ?: "")
        is Intent.Wait -> "wait:${intent.ticks}"
        is Intent.MoveTo -> "move_to:" + when (val t = intent.target) {
            is Target.Entity -> t.id
            is Target.Tile -> "${t.x},${t.y}"
        }
        is Intent.PickUp -> "pick_up:${intent.itemId}"
        is Intent.Say -> "say"
    }

    private fun describe(intent: Intent): String = label(intent).replace(':', ' ')

    private fun result(status: IntentStatus?): String = when (status) {
        is IntentStatus.Failed -> "failed: ${status.reason}"
        IntentStatus.Succeeded -> "succeeded"
        IntentStatus.Interrupted -> "interrupted"
        else -> "still going"
    }

    /** "d2 14:30", for the recent-actions list. */
    fun stamp(clock: GameClock): (Long) -> String = { tick -> clock.reading(tick).let { "d${it.day} ${it.time}" } }
}
