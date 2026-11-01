package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.ChoiceSet
import dev.lilguy.decision.SchemaHints
import dev.lilguy.sim.BackgroundOp
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.HearOp
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.PracticeOp
import dev.lilguy.sim.ProduceOp
import dev.lilguy.sim.ProgressOp
import dev.lilguy.sim.RateOp
import dev.lilguy.sim.SellOp
import dev.lilguy.sim.SetStateOp
import dev.lilguy.sim.Target
import dev.lilguy.sim.WriteOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How the bedroom is put to a model: the system prompt, the action examples, the per-request
 * reply schema and the closed choice set. The prompt opens with who he is ([Persona]: the fixed
 * core and his self-model) and then describes his situation rather than giving him an objective;
 * what he aims for is his own.
 * Everything else comes from the unlocked content, so the prompt can't drift from the rules or
 * mention things that are still gated.
 */
object BedroomPrompt {
    /** About half an hour of game time at the default scale; the choice set's "wait". */
    const val WAIT_TICKS = 150

    fun examples(content: BedroomContent, ticksPerMinute: Int): List<Pair<Intent, String>> = buildList {
        val activity = content.verbs.firstOrNull { it.work } ?: content.verbs.first()
        add(Intent.Do(activity.id) to "do an activity from `activities` that isn't locked; the game walks you there")
        content.items.firstOrNull { it.price != null }?.let { add(Intent.Buy(it.id) to "buy something from `shop` that you can afford; it arrives at the ${content.item(content.shop.at)?.name ?: content.shop.at}") }
        content.verbs.firstOrNull { it.args.isNotEmpty() }?.let { v ->
            val args = v.args.mapValues { content.withName(it.value).first().id }
            add(Intent.Do(v.id, args["what"], args["on"]) to "do an activity with things you own: `what` and `on` name them, as its entry in `activities` does")
        }
        add(Intent.MoveTo(Target.Entity("bed")) to "walk to something in the room")
        add(Intent.Say("one day this room will have a window") to "say something out loud")
        add(Intent.Wait(WAIT_TICKS) to "do nothing for N ticks, at most ${GridWorld.MAX_WAIT} ($ticksPerMinute ticks = 1 game minute)")
    }

    /**
     * "Each time you finish one, sketch costs 4 mood and read adds 2 mood. Below 25 mood you can't
     * sketch: too low to make art.", or null when no rule applies.
     */
    private fun moodRules(content: BedroomContent): String? {
        val blocks = content.verbs.flatMap { a -> a.block.filter { it.need == "mood" && it.below != null }.map { it to a.id } }
            .groupBy { it.first.below!! to it.first.reason }
            .map { (k, v) -> "Below ${k.first.toInt()} mood you can't ${v.joinToString(" or ") { it.second }}: ${k.second}." }
        val lifts = content.verbs.mapNotNull { a -> a.needEffects["mood"]?.takeIf { it > 0 }?.let { "${a.id} adds ${it.toInt()} mood" } }
        val costs = content.verbs.mapNotNull { a -> a.needEffects["mood"]?.takeIf { it < 0 }?.let { "${a.id} costs ${-it.toInt()} mood" } }
        val effects = if (lifts.isEmpty() && costs.isEmpty()) emptyList() else
            listOf("Each time you finish one, " + (costs + lifts).joinToString(" and ") + ".")
        return (effects + blocks).joinToString(" ").ifEmpty { null }
    }

    /** The art line of the prompt: commissions and hanging, or null when the content has neither. */
    private fun artHint(content: BedroomContent): String? {
        val parts = buildList {
            content.commissions?.let { c ->
                add("A request for a piece comes in every day at ${c.arrive} (`commission`) and stays open until the next. The first piece you finish while it's open that shows what it asks for earns $${c.reward} more when you sell it; it's yours to take or leave.")
            }
            content.verbs.firstOrNull { it.ops<SetStateOp>().isNotEmpty() }?.let { h ->
                add("${h.id} puts your newest piece on your wall instead of selling it: each one hung adds ${content.gallery.comfortEach} comfort, up to ${content.gallery.max}.")
            }
        }
        return if (parts.isEmpty()) null else "- Art: everything you make is a picture you draw yourself, with a title. " + parts.joinToString(" ")
    }

    /**
     * "Stimulation falls 3 an hour while you're awake; ... Below 40 it drags your mood down, up to 16
     * lower when you're fully bored.", or empty when stimulation doesn't drain.
     */
    private fun stimulationRule(content: BedroomContent): String {
        val r = content.needs
        if (r.stimulationPerHour >= 0) return ""
        val drag = if (r.bored > 0 && r.boredMood > 0) " Below ${r.bored.toInt()} you're bored, and it drags the mood you settle to down, up to ${(r.bored * r.boredMood).toInt()} lower at 0." else ""
        return " Stimulation falls ${num(-r.stimulationPerHour)} an hour while you're awake; hobbies, making things and anything new bring it back.$drag"
    }

    /** " Frustration rises 10 when something you try fails ...", or empty without frustration. */
    private fun frustrationRule(content: BedroomContent): String {
        val f = content.frustration ?: return ""
        val sleep = if (content.verbs.any { v -> v.ops<RateOp>().any { (it.perHour["frustration"] ?: 0.0) < 0 } && v.effects.any { !it.awake } }) " and faster while you sleep" else ""
        return " Frustration rises ${f.perFailure.toInt()} when something you try fails, falls ${(-f.perSuccess).toInt()} each time you finish something, and fades ${(-f.perHour).toInt()} an hour$sleep; above ${f.above.toInt()} it drags the mood you settle to down."
    }

    /**
     * The learning line: skills, practice and books, or null when the content has no skills or books.
     * Only what he knows is named: [content] is the known part.
     */
    private fun learningHint(content: BedroomContent): String? {
        // Only skills something he knows raises: a practice op, or a book's study.
        val raised = content.verbs.flatMap { v -> v.ops<PracticeOp>().flatMap { it.gains.keys } } + content.books.flatMap { it.skill.keys }
        val skills = content.vocabulary.skills.filter { it in raised }
        if (skills.isEmpty() && content.books.isEmpty()) return null
        val parts = buildList {
            if (skills.isNotEmpty()) {
                val practice = content.verbs.filter { it.ops<PracticeOp>().isNotEmpty() }.map { it.id }
                val study = content.verbs.filter { v -> v.ops<ProgressOp>().any { it.study } }.map { it.id }
                val by = listOfNotNull(practice.takeIf { it.isNotEmpty() }?.joinToString(", "), study.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" or ")} a book" }).joinToString(" and ")
                val r = content.skills
                val pays = listOfNotNull(
                    if (r.gainPerLevel > 0) "what you do with a skill gives ${(r.gainPerLevel * 100).toInt()}% more mood and stimulation per level" else null,
                    if (r.pricePerLevel > 0 && content.items.any { it.sellSkill != null }) "your art sells for ${(r.pricePerLevel * 100).toInt()}% more per level of art skill you had when you made it" else null,
                    r.deepens.entries.filter { it.key in skills }.takeIf { r.affinityPerLevel > 0 && it.isNotEmpty() }?.let { d -> "knowing more about " + d.joinToString(" and ") { (s, q) -> "$s makes $q things mean more to you" } },
                ).joinToString("; ")
                add("you have skills (${skills.joinToString(", ")}, in `skills`), each from 0 with no limit" + (if (by.isNotEmpty()) ", raised by $by" else "") + (if (pays.isNotEmpty()) ": $pays." else "."))
            }
            if (content.books.isNotEmpty()) add("A book is read a chapter at a time, one a session, and `books` shows how far you are. Finishing one can teach you things you don't know yet, which then show up in `activities` and `shop`; the shop says what a book teaches.")
        }
        return "- Learning: " + parts.joinToString(" ")
    }

    /** The variety line of the prompt: novelty and habituation, or null when the content has neither. */
    private fun varietyHint(content: BedroomContent): String? {
        val parts = buildList {
            content.novelty?.let { n -> add("The first time you do something it gives " + n.effects.entries.joinToString(" and ") { (need, d) -> "${d.toInt()} $need" } + " on top.") }
            content.habituation?.let { h ->
                val stale = if (h.staleStimulation < 0) ", and doing something you've worn thin costs stimulation" else ""
                add("Doing the same thing again gives less: each time you finish it, its mood and stimulation gains drop ${num(h.perRepeat * 100)}%, coming back ${num(h.recoverPerHour * 100)}% an hour$stale.")
                val routine = content.verbs.filter { it.isRoutine }.map { it.phrase ?: it.id }
                if (routine.isNotEmpty()) add("Routine things (${routine.joinToString(", ")}) don't wear thin.")
            }
        }
        return if (parts.isEmpty()) null else "- Variety: " + parts.joinToString(" ").replaceFirstChar { it.lowercase() }
    }

    /**
     * "- Night and day: night runs 20:00 to 04:00. ...", for verbs that change by day, or null when
     * none do.
     */
    private fun nightHint(content: BedroomContent): String? {
        val night = content.night ?: return null
        val parts = content.verbs.mapNotNull { v ->
            val d = v.byDay ?: return@mapNotNull null
            val name = v.phrase ?: v.id
            val atNight = v.until?.let { u -> "$name started then lasts until ${u.time}" + (u.need?.let { " or until $it reaches ${u.reaches!!.toInt()}" } ?: "") }
                ?: "$name started then takes ${GameClock.duration(v.minutes!!)}"
            val nap = if (v.effects.any { !it.awake }) " it's a nap" else " it's different"
            val rules = listOfNotNull(
                d.minutes?.let { "at most ${GameClock.duration(it)}" },
                d.block.takeIf { it.isNotEmpty() }?.joinToString(" and ") { b -> "only when ${b.need} is " + (b.above?.let { "${it.toInt()} or less" } ?: "at least ${b.below!!.toInt()}") },
            ).joinToString(", and ")
            "$atNight. Started at any other hour$nap: $rules."
        }
        if (parts.isEmpty()) return null
        return "- Night and day: night runs ${night.from} to ${night.to}. " + parts.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /**
     * "- Thinking: think stops you where you are ... Every 4 decisions you also stop to think on your
     * own.", for the verb that writes his plan, or null when he has none.
     */
    private fun reflectionHint(content: BedroomContent): String? {
        val v = content.verbs.firstOrNull { it.ops<WriteOp>().any { w -> w.showsAs == "plan" } } ?: return null
        val energy = v.needEffects["energy"]?.takeIf { it < 0 }?.let { " and costs ${(-it).toInt()} energy" }.orEmpty()
        val time = v.minutes?.let { " for ${GameClock.duration(it)}" }.orEmpty()
        val again = if (v.noRepeat) " You can't ${v.id} twice in a row." else ""
        val every = content.reflection?.takeIf { it.verb == v.id }?.let { " Every ${it.every} decisions you also ${v.phrase ?: v.id} on your own." }.orEmpty()
        return "- Thinking: ${v.id} stops you where you are$time$energy. Use it to work out what you want to do next and what you're working toward, and to ask how something in your world works; your latest thought shows below the observation as your plan for now, and what you found out under it.$again$every"
    }

    /**
     * [content] is what he's told about: only the verbs he's offered (a verb whose result nothing
     * writes is left out). [self] is his self-model as the prompt shows it, or null when nothing
     * revises it ([Persona.text]).
     */
    fun system(content: BedroomContent, ticksPerMinute: Int, self: String? = null): String {
        val r = content.needs
        val actions = examples(content, ticksPerMinute).joinToString("\n") { (intent, what) ->
            "- ${PromptBuilder.tag(intent)}: $what\n  ${PromptBuilder.exampleJson.encodeToString(Intent.serializer(), intent)}"
        }
        val makers = content.verbs.filter { it.ops<ProduceOp>().isNotEmpty() }.map { it.id }
        val made = content.verbs.flatMap { v -> v.ops<ProduceOp>().flatMap { it.items.keys } }.toSet()
        val art = content.items.filter { it.sellPrice != null && it.id in made }.joinToString(" and ") { "a ${it.name} sells for $${it.sellPrice}" } +
            if (content.items.any { it.sellSkill != null } && content.skills.pricePerLevel > 0) ", more as your skill grows" else ""
        val work = content.verbs.filter { it.work }.joinToString(", ") { it.id }
        val floor = r.workFloor.joinToString(" or ") { "${it.need} is below ${it.below?.toInt()}" }
        val sellAt = content.verbs.firstOrNull { it.ops<SellOp>().isNotEmpty() }
        val hatch = content.item(content.shop.at)?.name ?: content.shop.at
        val decor = content.items.any { it.slot != null && content.value(it) > 0 }
        val forSale = content.items.filter { it.price != null }
        val wants = if (forSale.any { it.group != "food" }) "for food and for anything you want to own" else "for food"
        val bored = if (r.stimulationPerHour < 0 && r.boredMood > 0) ", less boredom" else ""
        val mood = if (decor) {
            "Mood drifts ${r.moodDriftPerHour.toInt()} an hour toward ${r.moodBaseline.toInt()} plus your room's comfort$bored; nice things raise it for a while, and decor raises comfort by as much as you like it."
        } else {
            "Mood drifts ${r.moodDriftPerHour.toInt()} an hour toward ${r.moodBaseline.toInt()}$bored; some activities raise it for a while."
        }
        val sinking = moodRules(content)?.let { " $it" }.orEmpty()
        val lists = "What you can do: ${Persona.abilities(content)}. `activities` lists all of it, with how long it takes and what it gives, or why it's locked." +
            (if (content.verbs.any { it.args.isNotEmpty() }) " One done with something you own is listed once for each thing you could use, with `what` (and `on`) naming it." else "") +
            " `shop` is what the shop will sell you now: its price, whether you can afford it, and what it's for. Something that's no use without another thing isn't sold to you until you have that thing; the shop says what buying an item opens up." +
            if (decor) " Your room has empty places for furniture and decor, and the catalog is where they come from." else ""
        val options = "activities, shop"
        val hearing = content.verbs.filter { it.ops<HearOp>().isNotEmpty() }.map { it.id }
        val hear = if (hearing.isEmpty()) "" else " ${hearing.joinToString(" and ")} ${if (hearing.size > 1) "are" else "is"} for whatever is playing, so offered only while something plays."
        val music = if (content.verbs.any { it.ops<BackgroundOp>().isNotEmpty() }) " music: what's playing, if anything.$hear" else ""
        val life = listOfNotNull(
            "- Time: the clock shows the day and the time. Activities take game minutes or hours, and you choose again when one finishes.",
            "- Needs, each from 0 to 100. Energy falls ${-r.awakeEnergyPerHour.toInt()} an hour while you're awake and comes back while you sleep. Fullness falls ${-r.fullnessPerHour.toInt()} an hour${r.asleepFullnessPerHour?.let { " (${(-it).toInt()} asleep)" }.orEmpty()}; eating brings it back.${stimulationRule(content)}${frustrationRule(content)} $mood Below ${r.low.toInt()} energy or fullness, mood falls ${-r.lowMoodPerHour.toInt()} an hour instead.$sinking When $floor you can't work ($work).",
            "- Money: ${makers.joinToString(" and ")} make${if (makers.size > 1) "" else "s"} art, and ${sellAt?.id ?: "selling"} sells everything you hold through the $hatch: $art. The shop delivers through the $hatch, and you pay when it arrives.",
            nightHint(content),
            varietyHint(content),
            learningHint(content),
            artHint(content),
            reflectionHint(content),
            "- $lists",
            "- Nothing ends the game. An action that can't be done fails at once, and lastResult says why. Read it and choose something else rather than repeating it.",
        ).joinToString("\n")
        return """
            |${Persona.text(content, self)}
            |
            |You live in this bedroom. You need money $wants. Each turn you choose what to do next; the game carries it out, then shows you how things stand.
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
            |- clock: day and time. money: dollars. needs: ${needNames(content).let { it.dropLast(1).joinToString(", ") + " and " + it.last() }}, 0 to 100.
            |- holding: art you're carrying, to sell. owns: everything else you own, id to count.$music${if (content.vocabulary.skills.isNotEmpty()) "\n            |- skills: your skills, name to level." else ""}${if (content.books.isNotEmpty()) "\n            |- books: the books you own, how many chapters you've read of how many, what studying one raises, and what finishing it would teach you." else ""}
            |- art: the pieces you have made and still have, held to sell or hung on your wall, with their titles. commission: today's request for a piece, if one is open.
            |- $options: your options, as above.
            |- self and nearby: where you are and the furniture around you, with tile x and y. localMap: the room, one string per row, north at the top.
            |- lastIntent and lastResult: your previous action and how it ended (succeeded, failed with a reason, or interrupted).
            |- recentEvents: the last few things that happened, stamped with day and time.
            |
            |Below the observation you may see what you remember from earlier (memories, and thoughts you had), and what you decided the last time you stopped to think. Those thoughts are your own words: let the plans in them shape what you do with your hours.
        """.trimMargin()
    }

    /**
     * The reply schema for one observation: do() takes only activities that aren't locked, and
     * only things listed with them, and buy() only what he can afford, so constrained decoding
     * can't pick anything the sim would refuse for those reasons. There is nothing to pick up in
     * the bedroom and use() and think() are for other rooms, so they're left out (thinking is an activity here).
     */
    fun replySchema(obs: Observation, ticksPerMinute: Int): ActionSchema<AgentReply> {
        val open = obs.activities.orEmpty().filter { it.locked == null }
        val hints = SchemaHints(
            descriptions = VARIANT_DESCRIPTIONS + mapOf(
                "reply.thought" to "Your reasoning in a sentence or two, before you act.",
                "reply.intent" to "The one action to take now.",
                "do.activity" to "An activity from `activities` that isn't locked.",
                "do.what" to "What to do it with, as listed with the activity; null when it lists none.",
                "do.on" to "What to do it on, as listed with the activity; null when it lists none.",
                "buy.item" to "An item from `shop` that you can afford.",
                "entity.id" to "An id from nearby.",
                "wait" to "Do nothing for some ticks ($ticksPerMinute ticks = 1 game minute).",
                "tile.x" to "Tile column; x grows east.",
                "tile.y" to "Tile row; y grows south.",
            ),
            enums = mapOf(
                "do.activity" to open.map { it.id }.distinct(),
                "do.what" to open.mapNotNull { it.what }.distinct(),
                "do.on" to open.mapNotNull { it.on }.distinct(),
                "buy.item" to obs.shop.orEmpty().filter { it.affordable }.map { it.id },
                "entity.id" to obs.nearby.map { it.id },
                "pick_up.itemId" to emptyList(),
            ),
            ranges = mapOf("wait.ticks" to 1..GridWorld.MAX_WAIT),
            omit = setOf("use", "think"),
        )
        return ActionSchema.of("reply", AgentReply.serializer(), hints) { it.thought }
    }

    /**
     * The decision as a closed set: each activity that isn't locked (once per thing he could do it
     * with), each purchase he can afford, and waiting half an hour. Only verbs in [content] are offered: with no model writing
     * thoughts, think is left out, so a picking model doesn't spend time on nothing visible. Walking and talking are left out; the
     * activities walk him where he needs to be. The state is the life part of the observation
     * (no map or coordinates), plus his recent actions.
     *
     * Options are ordered activities (in content order), purchases, wait: Jev leans toward
     * earlier options, so doing comes before spending and waiting is last. Each sits in its node of
     * the content's `menu` ([menuNode]), so a picker can choose the kind of thing first.
     */
    fun choices(obs: Observation, recent: List<Memory.Entry>, ticksPerMinute: Int, content: BedroomContent): ChoiceSet {
        val thinking = content.reflection?.let { content.verb(it.verb) } != null
        val offered = obs.activities.orEmpty().filter { content.verb(it.id) != null }
        val options = buildList {
            offered.filter { it.locked == null }.forEach { a ->
                val with = listOfNotNull(a.what?.let { " with the $it" }, a.on?.let { " on the $it" }).joinToString("")
                add(option(Intent.Do(a.id, a.what, a.on), "Do ${a.id}$with: takes ${a.takes}; gives ${a.gives}.", content))
            }
            obs.shop.orEmpty().filter { it.affordable }.forEach { s ->
                add(option(Intent.Buy(s.id), "Buy ${s.id} for $${s.price}, leaving $${(obs.money ?: 0) - s.price}: ${s.gives}.", content))
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
                    if (content.needs.stimulationPerHour < 0) put("stimulation", n.stimulation)
                    if (content.frustration != null) put("frustration", n.frustration)
                }
            }
            obs.skills?.let { s -> putJsonObject("skills") { s.forEach { (k, v) -> put(k, v) } } }
            obs.books?.let { books ->
                putJsonArray("books") {
                    books.forEach { b ->
                        add("${b.id} \"${b.title}\": read ${b.read} of ${b.chapters} chapters" + (b.raises?.let { "; studying raises ${it.joinToString(" and ")}" } ?: "") + (b.teaches?.let { "; finishing it teaches you $it" } ?: ""))
                    }
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
                offered.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        a.what?.let { put("what", it) }
                        a.on?.let { put("on", it) }
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
        }
        return ChoiceSet(
            instructions = "What should the character in `state` do next? He lives here for the long run and looks after himself: his `needs` fall over time, " +
                "and he needs money for food. Choose what serves him best over the coming days, not just the next hour. " +
                (if (content.habituation != null) "Doing the same thing over and over bores him: its gains shrink and his stimulation falls, which drags his mood down; new things and hobbies lift it. " else "") +
                (if (thinking) "Thinking lets him work out what he wants and plan; he also stops to think on his own every few decisions. " else "") +
                "Read `state.last_action` and `state.recent_actions`: don't repeat an action that just failed.",
            state = state,
            options = options,
            groups = content.menu.map { ChoiceSet.Group(it.id, it.says, it.under) },
        )
    }

    /**
     * The menu node [intent] sits in: an activity's own, a purchase's by the first verb its item is
     * for, else by the item's group; null for the top.
     */
    private fun menuNode(intent: Intent, content: BedroomContent): String? = when (intent) {
        is Intent.Do -> content.menu.firstOrNull { intent.activity in it.verbs }?.id
        is Intent.Buy -> content.item(intent.item)?.let { item ->
            content.verbs.filter { v -> item.id in v.at || (v.requires + v.args.values + v.effects.flatMap { it.asksFor }).any { it in item.names } }
                .firstNotNullOfOrNull { v -> content.menu.firstOrNull { v.id in it.verbs }?.id }
                ?: content.menu.firstOrNull { item.group in it.buys }?.id
        }
        else -> null
    }

    /** "5.5", "3". */
    private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

    /** The needs the observation shows, as the content uses them. */
    private fun needNames(content: BedroomContent): List<String> =
        listOf("energy", "fullness", "mood") + listOfNotNull(if (content.needs.stimulationPerHour < 0) "stimulation" else null, if (content.frustration != null) "frustration" else null)

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun option(intent: Intent, description: String, content: BedroomContent? = null) = ChoiceSet.Option(
        label = label(intent),
        description = description,
        reply = json.encodeToJsonElement(AgentReply.serializer(), AgentReply(description, intent)).jsonObject,
        group = content?.let { menuNode(intent, it) },
    )

    /** "do:sketch", "do:play_record:jazz_record:record_player", "buy:canvas", "wait:150". */
    private fun label(intent: Intent): String = when (intent) {
        is Intent.Do -> (listOf("do", intent.activity) + listOfNotNull(intent.what, intent.on)).joinToString(":")
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
