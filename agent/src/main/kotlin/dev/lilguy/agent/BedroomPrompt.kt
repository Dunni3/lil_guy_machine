package dev.lilguy.agent

import dev.lilguy.agent.memory.JOURNAL_ACTIVITY
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
 * reply schema and the closed choice set. The prompt opens with who he is ([Persona]) and then
 * describes his situation rather than giving him an objective; needs and wants do the rest.
 * Everything else comes from the unlocked content, so the prompt can't drift from the rules or
 * mention things that are still gated.
 */
object BedroomPrompt {
    /** About half an hour of game time at the default scale; the choice set's "wait". */
    const val WAIT_TICKS = 150

    fun examples(content: BedroomContent, ticksPerMinute: Int, thinking: Boolean = false): List<Pair<Intent, String>> = buildList {
        val activity = content.activities.firstOrNull { it.work } ?: content.activities.first()
        add(Intent.Do(activity.id) to "do an activity from `activities` that isn't locked; the game walks you there")
        content.items.firstOrNull { it.price != null }?.let { add(Intent.Buy(it.id) to "buy something from `shop` that you can afford; it arrives at the ${content.item(content.shop.at)?.name ?: content.shop.at}") }
        content.uses.firstOrNull()?.let { u ->
            val item = content.withTag(u.item!!).first().id
            add(Intent.Use(item, on = u.on) to "use an item you own on another, as listed in `uses`")
        }
        add(Intent.MoveTo(Target.Entity("bed")) to "walk to something in the room")
        add(Intent.Say("one day this room will have a window") to "say something out loud")
        if (thinking) add(Intent.Think to "stop and think about what you want to do next and what you want to achieve")
        add(Intent.Wait(WAIT_TICKS) to "do nothing for N ticks, at most ${GridWorld.MAX_WAIT} ($ticksPerMinute ticks = 1 game minute)")
    }

    /** The journal line of the prompt, or null when the content has no journal activity. */
    private fun journalHint(content: BedroomContent): String? {
        val j = content.activity(JOURNAL_ACTIVITY) ?: return null
        val energy = j.effects["energy"]?.let { if (it < 0) " and costs ${-it.toInt()} energy" else "" }.orEmpty()
        val work = if (j.work) "; it's work, so you can only do it when you are fed and rested enough to work" else ""
        val minutes = j.minutes?.let { GameClock.duration(it) } ?: "a while"
        return "- Journal: writing in your journal takes $minutes$energy$work. It's where you say what you want and why."
    }

    /**
     * "Each time you finish one, sketch costs 4 mood and journal adds 12 mood. After a day without writing in
     * your journal (24 hours, counted from the start), mood falls 5 an hour instead, until you do it
     * again. Below 25 mood you can't sketch: too low to make art.", or null when no rule applies.
     */
    private fun moodRules(content: BedroomContent): String? {
        val neglect = content.needs.neglect.filter { content.activity(it.activity) != null }.map { n ->
            "After ${n.reason} (${n.afterHours} hours, counted from the start), mood falls ${-n.moodPerHour.toInt()} an hour instead, until you do it again."
        }
        val blocks = content.activities.flatMap { a -> a.block.filter { it.need == "mood" && it.below != null }.map { it to a.id } }
            .groupBy { it.first.below!! to it.first.reason }
            .map { (k, v) -> "Below ${k.first.toInt()} mood you can't ${v.joinToString(" or ") { it.second }}: ${k.second}." }
        val lifts = content.activities.mapNotNull { a -> a.effects["mood"]?.takeIf { it > 0 }?.let { "${a.id} adds ${it.toInt()} mood" } }
        val costs = content.activities.mapNotNull { a -> a.effects["mood"]?.takeIf { it < 0 }?.let { "${a.id} costs ${-it.toInt()} mood" } }
        val effects = if (lifts.isEmpty() && costs.isEmpty()) emptyList() else
            listOf("Each time you finish one, " + (costs + lifts).joinToString(" and ") + ".")
        return (effects + neglect + blocks).joinToString(" ").ifEmpty { null }
    }

    /** The art line of the prompt: commissions and hanging, or null when the content has neither. */
    private fun artHint(content: BedroomContent): String? {
        val parts = buildList {
            content.commissions?.let { c ->
                add("A request for a piece comes in every day at ${c.arrive} (`commission`) and stays open until the next. The first piece you finish while it's open that is what it asks for earns $${c.reward} more when you sell it; it's yours to take or leave.")
            }
            content.activities.firstOrNull { it.hangs != null }?.let { h ->
                add("${h.id} puts your newest piece on your wall instead of selling it: each one hung adds ${content.gallery.comfortEach} comfort, up to ${content.gallery.max}.")
            }
        }
        return if (parts.isEmpty()) null else "- Art: everything you make is a picture you draw yourself, with a title. " + parts.joinToString(" ")
    }

    /** The think line of the prompt. */
    private fun thinkHint(content: BedroomContent): String {
        val t = content.think
        val energy = if (t.energy < 0) " and costs ${(-t.energy).toInt()} energy" else ""
        val every = t.every?.let { " Every $it decisions you also stop to think on your own." }.orEmpty()
        return "- Thinking: think stops you where you are for ${GameClock.duration(t.minutes)}$energy. Use it to work out what you want to do next and what you're working toward; your latest thought shows below the observation as your plan for now. You can't think twice in a row.$every"
    }

    /** True when he may think now: something writes thoughts, and his last action wasn't a think. */
    fun canThink(obs: Observation, thinking: Boolean): Boolean = thinking && obs.lastIntent != Intent.Think

    /**
     * [content] is what he's told about, so leave the journal activity out of it when nothing
     * writes entries. [thinking]: something writes his thoughts, so think is offered.
     */
    fun system(content: BedroomContent, ticksPerMinute: Int, thinking: Boolean = false): String {
        val r = content.needs
        val actions = examples(content, ticksPerMinute, thinking).joinToString("\n") { (intent, what) ->
            "- ${PromptBuilder.tag(intent)}: $what\n  ${PromptBuilder.exampleJson.encodeToString(Intent.serializer(), intent)}"
        }
        val art = content.items.filter { it.sellPrice != null }.joinToString(" and ") { "a ${it.name} sells for $${it.sellPrice}" }
        val makers = content.activities.filter { it.produces.isNotEmpty() }.map { it.id }
        val work = content.activities.filter { it.work }.joinToString(", ") { it.id }
        val floor = r.workFloor.joinToString(" or ") { "${it.need} is below ${it.below?.toInt()}" }
        val sellAt = content.activities.firstOrNull { it.sells != null }
        val hatch = content.item(content.shop.at)?.name ?: content.shop.at
        val decor = content.items.any { it.comfort > 0 }
        val forSale = content.items.filter { it.price != null }
        val wants = if (forSale.any { it.kind != "food" }) "for food and for anything you want to own" else "for food"
        val means = if (content.activity(JOURNAL_ACTIVITY) != null) ", as a means, not the goal" else ""
        val mood = if (decor) {
            "Mood drifts ${r.moodDriftPerHour.toInt()} an hour toward ${r.moodBaseline.toInt()} plus your room's comfort; nice things raise it for a while, and decor raises comfort."
        } else {
            "Mood drifts ${r.moodDriftPerHour.toInt()} an hour toward ${r.moodBaseline.toInt()}; some activities raise it for a while."
        }
        val sinking = moodRules(content)?.let { " $it" }.orEmpty()
        val lists = "`activities` lists everything you can do, with how long it takes and what it gives, or why it's locked. `shop` lists what's for sale, its price, whether you can afford it, and what it's for." +
            if (content.uses.isNotEmpty()) " `uses` lists things you can use on other things." else ""
        val options = if (content.uses.isNotEmpty()) "activities, uses, shop" else "activities, shop"
        val music = if (content.uses.any { it.background != null }) " music: what's playing, if anything." else ""
        val life = listOfNotNull(
            "- Time: the clock shows the day and the time. Activities take game minutes or hours, and you choose again when one finishes.",
            "- Needs, each from 0 to 100. Energy falls ${-r.awakeEnergyPerHour.toInt()} an hour while you're awake and comes back while you sleep. Fullness falls ${-r.fullnessPerHour.toInt()} an hour; eating brings it back. $mood Below ${r.low.toInt()} energy or fullness, mood falls ${-r.lowMoodPerHour.toInt()} an hour instead.$sinking When $floor you can't work ($work).",
            "- Money: ${makers.joinToString(" and ")} make${if (makers.size > 1) "" else "s"} art, and ${sellAt?.id ?: "selling"} sells everything you hold through the $hatch: $art. The shop delivers through the $hatch, and you pay when it arrives.",
            artHint(content),
            journalHint(content),
            if (thinking) thinkHint(content) else null,
            "- $lists",
            "- Nothing ends the game. An action that can't be done fails at once, and lastResult says why. Read it and choose something else rather than repeating it.",
        ).joinToString("\n")
        return """
            |${Persona.text(content)}
            |
            |You live in this bedroom. You need money $wants$means. Each turn you choose what to do next; the game carries it out, then shows you how things stand.
            |
            |How your life works:
            |$life
            |
            |Actions (the "intent" field), each with an example:
            |$actions
            |
            |Reply with a JSON object {"thought": ..., "intent": ...}. "thought" is your reasoning in a sentence or two; "intent" is the one action to take now. Use only ids from the observation.
            |
            |The observation is JSON:
            |- clock: day and time. money: dollars. needs: energy, fullness and mood, 0 to 100.
            |- holding: art you're carrying, to sell. owns: everything else you own, id to count.$music
            |- art: the pieces you have made and still have, held to sell or hung on your wall, with their titles. commission: today's request for a piece, if one is open.
            |- $options: your options, as above.
            |- self and nearby: where you are and the furniture around you, with tile x and y. localMap: the room, one string per row, north at the top.
            |- lastIntent and lastResult: your previous action and how it ended (succeeded, failed with a reason, or interrupted).
            |- recentEvents: the last few things that happened, stamped with day and time.${if (content.needs.neglect.any { content.activity(it.activity) != null }) "\n            |- neglect: how long since your last journal, and whether your mood is falling for it." else ""}
            |
            |Below the observation you may see what you remember from earlier, what you wrote in your journal, and what you decided the last time you stopped to think. The journal and that thought are your own words: let the plans in them shape what you do with your hours.
        """.trimMargin()
    }

    /**
     * The reply schema for one observation: do() takes only activities that aren't locked, buy()
     * only what he can afford, use() only the uses on offer, so constrained decoding can't pick
     * anything the sim would refuse for those reasons. There is nothing to pick up in the
     * bedroom, so pick_up is left out, and think is only there when [canThink].
     */
    fun replySchema(obs: Observation, ticksPerMinute: Int, thinking: Boolean = false): ActionSchema<AgentReply> {
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
            omit = if (canThink(obs, thinking)) emptySet() else setOf("think"),
        )
        return ActionSchema.of("reply", AgentReply.serializer(), hints) { it.thought }
    }

    /**
     * The decision as a closed set: each activity that isn't locked, each use on offer, each
     * purchase he can afford, and waiting half an hour. Without [journaling] (no model writes the
     * entries) the journal is left out, so a picking model doesn't spend hours on nothing visible. Walking and talking are left out; the
     * activities walk him where he needs to be. The state is the life part of the observation
     * (no map or coordinates), plus his recent actions.
     *
     * Options are ordered activities (in content order), uses, purchases, wait: Jev leans toward
     * earlier options, so doing comes before spending and waiting is last.
     */
    fun choices(obs: Observation, recent: List<Memory.Entry>, ticksPerMinute: Int, content: BedroomContent, journaling: Boolean = true, thinking: Boolean = false): ChoiceSet {
        val options = buildList {
            obs.activities.orEmpty().filter { it.locked == null && (journaling || it.id != JOURNAL_ACTIVITY) }.forEach { a ->
                add(option(Intent.Do(a.id), "Do ${a.id}: takes ${a.takes}; gives ${a.gives}."))
            }
            obs.uses.orEmpty().forEach { u ->
                add(option(Intent.Use(u.itemId, on = u.on), "Put the ${u.itemId} on the ${u.on}: takes ${u.takes}; gives ${u.gives}."))
            }
            if (canThink(obs, thinking)) {
                val t = content.think
                add(option(Intent.Think, "Stop and think about what he wants to do next and what he wants to achieve: takes ${GameClock.duration(t.minutes)}; costs ${(-t.energy).toInt()} energy."))
            }
            obs.shop.orEmpty().filter { it.affordable }.forEach { s ->
                add(option(Intent.Buy(s.id), "Buy ${s.id} for $${s.price}, leaving $${(obs.money ?: 0) - s.price}: ${s.gives}."))
            }
            add(option(Intent.Wait(WAIT_TICKS), "Wait about ${WAIT_TICKS / ticksPerMinute} minutes and do nothing."))
        }
        val state = buildJsonObject {
            put("situation", Persona.short(content) + " He lives in this bedroom and needs money for food. His needs fall over time; nothing ends the game.")
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
            obs.commission?.let { c -> put("commission", "${c.request ?: "wants ${c.wants}"} ($${c.reward} more for the first piece that matches, until ${c.until})") }
            obs.art?.takeIf { it.isNotEmpty() }?.let { art ->
                putJsonArray("art") {
                    art.forEach { p ->
                        add("${p.item} ${p.id}" + (p.title?.let { " \"$it\"" } ?: "") + if (p.hung) ", on the wall" else ", held (sells for $${p.sellsFor})" + (p.commission?.let { "; $it" } ?: ""))
                    }
                }
            }
            putJsonArray("activities") {
                obs.activities.orEmpty().filter { journaling || it.id != JOURNAL_ACTIVITY }.forEach { a ->
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
                putJsonArray("recent_actions") { recent.forEach { add(describe(it.intent) + ": " + result(it.result) + (it.thought?.let { t -> "; he thought: \"$t\"" } ?: "")) } }
            }
            if (obs.recentEvents.isNotEmpty()) putJsonArray("recent_events") { obs.recentEvents.forEach { add(it) } }
            obs.neglect?.let { put("neglect", it) }
        }
        return ChoiceSet(
            instructions = "What should the character in `state` do next? He lives here for the long run and looks after himself: his `needs` fall over time, " +
                "and he needs money for food. Choose what serves him best over the coming days, not just the next hour. " +
                (if (journaling) "His goal is to express himself in his journal, where he plans how to make his life better; earning is only how he keeps himself fed. " +
                    "Keep him fed and rested enough, and put the hours that leaves toward his journal: it takes an hour and energy that earning his food also needs. " +
                    (if (content.needs.neglect.isNotEmpty()) "He needs it, too: a day without journaling and his mood sinks (`state.neglect` says how long it's been), and when his mood is low he can't make art, so he can't earn. " else "")
                else "") +
                (if (thinking) "Thinking lets him work out what he wants and plan; he also stops to think on his own every few decisions. " else "") +
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
        Intent.Think -> "think"
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
