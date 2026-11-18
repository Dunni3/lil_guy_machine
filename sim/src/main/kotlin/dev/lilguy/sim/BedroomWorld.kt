package dev.lilguy.sim

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Milestone 4 world: a bedroom where the guy makes art, sells it, and spends the money on food,
 * tools and things he likes. Everything he can do is a row in [content]; this class is the
 * engine that reads those rows. State changes only through five parts: the [clock], the money
 * [ledger], the [needs], what he owns, and the running activity (plus background music). What an
 * activity does is its list of [Op]s; this class runs them and gives them the world ([OpWorld]).
 *
 * do(verb, what, on) and buy(item) walk him to the right spot on their own. A verb's arguments
 * are bound to items he owns (a record, and the player it goes on); one he leaves out is filled
 * with the first owned match. Decisions happen when a verb ends.
 *
 * Art is a count like any item, and each carried one is also a numbered piece, so it can be drawn,
 * hung or sold on its own. A drawing model's title and tags arrive later through [deliver]; a
 * daily commission ([CommissionRules]) pays extra for the first sold piece whose drawing matches it:
 * the adjective in its title or tags, and a shape drawn for the noun.
 *
 * Doing the same thing again pays less ([HabituationRules]) and the first time pays more
 * ([NoveltyRules]); both feed stimulation, and low stimulation lowers the mood he settles to.
 *
 * What an item is worth to him comes from its qualities and his [Personality]: in its slot it adds
 * comfort, and an `enjoy` op adds it as mood ([QualityValue]).
 *
 * He has skills ([SkillRules]), raised by practice and by reading books a chapter at a time
 * ([BookSpec]). What a book teaches isn't in his world until he finishes it: the shop doesn't list
 * it and he can't do it ([knownContent] is the content as he knows it). Failures frustrate him and
 * successes ease it ([FrustrationRules]).
 */
class BedroomWorld private constructor(
    startLayout: Layout,
    guy: Character,
    startContent: BedroomContent,
    val clock: GameClock,
    /** Picks the commissions. */
    private val seed: Long,
) : GridWorld(startLayout.map, guy) {
    /** The house on its land: the map, the places on it, where each slot is. Replaced when a room is built or moved. */
    @Volatile var layout: Layout = startLayout
        private set

    /** Rooms built onto the house: room id to the site it stands on. */
    private val house = linkedMapOf<String, String>()

    /** Daylight (dimmed by the weather's [daylight]), windows and lamps ([Lighting]). */
    val lighting = Lighting()

    /** The sun alone, daylight and windows, for what grows ([Surroundings.growHour]); its own cache, so it never pushes out [lighting]'s. */
    private val sunlight = Lighting()

    /** Warmth, sound, water and lures, what reads them, and what his code has things give off ([Surroundings]). */
    private val surroundings = Surroundings()

    /** Rooms built onto the house, room id to site id. */
    fun houseRooms(): Map<String, String> = LinkedHashMap(house)

    /** Everything he can own and do. It grows when he invents something ([inventions]); swapped only on the sim thread. */
    @Volatile var content: BedroomContent = startContent
        private set

    /** What he has invented, oldest first; replaced rather than changed, so any thread can read it. */
    @Volatile var inventions: List<Invention> = emptyList()
        private set(value) {
            field = value
            invented = value.map { InventedView(it, it.item.slot in content.room.wallSlots) }
        }
    private var invented: List<InventedView> = emptyList()

    val ledger = Ledger(content.start.money, clock)
    val needs = Needs(content.start.needs, clock)

    /** Item id to count; only counts above zero. */
    private val owned = LinkedHashMap<String, Int>()
    private var doing: Doing? = null

    /** Wear per habit ([habit]): its level and the tick it was set; it fades from there. */
    private val wear = mutableMapOf<String, Pair<Double, Long>>()

    /** Habits he has finished at least once, for the novelty bonus; a subject he has drawn is "subject:whale" ([subjectNovelty]). */
    private val tried = mutableSetOf<String>()

    /** How familiar what he does is, by what it was like ([features]); null without `familiarity`, and verbs wear by id. */
    private val familiar: Familiarity? = startContent.familiarity?.let { Familiarity(it, clock.ticks(60).toLong()) }

    /** Item id to the tick he first had it, for comfort's `adapt`. */
    private val ownedSince = LinkedHashMap<String, Long>()

    /** The commission nouns he has drawn a piece of, so a drawing model can tell him which are new. Readable from any thread. */
    @Volatile var subjectsDrawn: Set<String> = emptySet()
        private set

    /** Things he bought for the room, to the tick he bought them, while they still feel new ([NewThings]). */
    private val newThings = LinkedHashMap<String, Long>()

    /** Skill name to level, from 0 up. */
    private val skills = LinkedHashMap<String, Double>()

    /** Book id to how many chapters he has read. */
    private val chapters = mutableMapOf<String, Int>()

    /** What books have taught him ([BedroomContent.taught]); read from the agent's thread through [knownContent]. */
    @Volatile private var known: Set<String> = emptySet()
    @Volatile private var knownCache: Pair<Set<String>, BedroomContent>? = null

    private var music: Music? = null

    /** Outdoor places he has explored (looked round from their lookout). */
    private val explored = linkedSetOf<String>()

    /** Patch to the tick it has grown back by, while it's bare. */
    private val bareUntil = linkedMapOf<String, Long>()

    /** Lamps he has switched on or off (or back to their timer, which drops them from here); the rest are on their timers. */
    private val lampModes = linkedMapOf<String, LampMode>()

    /** The run's weather, from the seed (Weather.kt); null without weather.yaml. */
    val weather: Weather? = startContent.weather?.let { Weather(it, seed) }

    /** What his recorders have logged, by thing and key. */
    private val records = Records(startContent.weather?.station?.keeps ?: WeatherRules.Station().keeps)

    /** Bumped whenever what he owns or plays changes; with his last intent it keys [options], and the minute and need levels too between activities. */
    private var version = 0
    private var options: Options? = null

    private class Options(val key: List<Any>, val moment: List<Any>, val activities: List<ActivityOption>, val shop: List<ShopOption>, val catalog: List<CatalogEntry>, val verbs: List<VerbView>)

    /**
     * [usesOf] and [heldBack] by item id while [current] is rebuilt: nothing changes during a rebuild, and each
     * shop option asks them for every other item, which without this made a rebuild quadratic in the shop.
     */
    private class Memo(val uses: HashMap<String, List<VerbSpec>> = HashMap(), val held: HashMap<String, String?> = HashMap())
    private var memo: Memo? = null

    /** What he finished lately that isn't routine, oldest first, for [lately]; kept for [LATELY_DAYS]. */
    private val recent = ArrayDeque<Pair<String, Long>>()

    /** Verb id to how often he has finished it; replaced rather than changed, so snapshots can share it. */
    private var done: Map<String, VerbTally> = emptyMap()

    /** [bound]: the verb, its bound arguments, and how fresh it was when it started, which scales its rates. */
    private class Doing(val bound: Bound, val startTick: Long, val endTick: Long) {
        val activity: VerbSpec get() = bound.verb
    }

    private enum class PieceState { Held, Hung, Sold }

    /** One piece of art. [commission]: the request open when it was finished, if any. [skill]: his level in the skill its price grows with, when he made it. */
    private class Piece(val id: Int, val item: String, val commission: Int?, val skill: Double) {
        var state = PieceState.Held
        var title: String? = null
        var tags: List<String> = emptyList()
        var shows: String? = null
    }

    private class Commission(val id: Int, val adjective: String, val noun: String, val wants: String, val until: Long) {
        var request: String? = null
        var fulfilled = false
    }

    /** Every piece he has made, oldest first; the held ones match the counts of carried items. */
    private val pieces = mutableListOf<Piece>()
    private var commission: Commission? = null
    private var commissions = 0
    private val notes = ConcurrentLinkedQueue<ArtNote>()
    /** Letters on their way in ([post]), the ones waiting at the mail hatch, oldest first, and the ids of those he has opened. */
    private val posted = ConcurrentLinkedQueue<Letter>()
    private val mailbox = ArrayDeque<Letter>()
    private val opened = linkedSetOf<Long>()

    /** The ids of the letters he has opened, for whoever sent them. Readable from any thread. */
    @Volatile var lettersOpened: Set<Long> = emptySet()
        private set

    /** Authored ops whose verb succeeded, newest last ([AuthoredView]); the oldest drop off. */
    private val authored = ArrayDeque<AuthoredView>()
    private var authoredCount = 0
    private val deliveries = ConcurrentLinkedQueue<Pair<Int, AuthoredResult>>()
    /** Shopping trips' picks as they arrive ([stage]), and as staged for each trip verb until its checkout. */
    private val staging = ConcurrentLinkedQueue<Pair<String, AuthoredResult>>()
    private val baskets = mutableMapOf<String, AuthoredResult.Basket>()
    /** What he designed, staged for each building verb until it finishes. */
    private val designs = mutableMapOf<String, AuthoredResult.Designed>()
    /** Letters he wrote, as staged for each letter verb until he puts it through the hatch. */
    private val drafts = mutableMapOf<String, AuthoredResult.Composed>()
    /** The letters he has opened and the ones he has written, oldest first. */
    private val read = mutableListOf<Letter>()
    private val sent = mutableListOf<OutLetter>()

    /** The letters he has written, for the host to send on. Readable from any thread. */
    @Volatile var lettersSent: List<OutLetter> = emptyList()
        private set

    /** Who he can write to and what has passed between them, for the letter writer. Readable from any thread. */
    @Volatile var correspondence: List<Correspondent> = emptyList()
    /** The verbs he wrote, staged for each writing verb until it finishes. */
    private val codes = mutableMapOf<String, AuthoredResult.Coded>()

    /** Runs the code of the verbs he wrote ([ScriptOp]). */
    private val sandbox = ScriptSandbox()

    /** The verbs he wrote, oldest first; replaced rather than changed, so any thread can read it. */
    @Volatile var coded: List<CodedVerb> = emptyList()
        private set

    /** What his code stored on things, item id (or `self`) to key to value. */
    private val thingState = linkedMapOf<String, LinkedHashMap<String, kotlinx.serialization.json.JsonPrimitive>>()

    /** What his code changed about his things (where they stand, their names and colours), to put back on a restore. */
    private val custom = linkedMapOf<String, BedroomState.Custom>()

    /** What a verb's code did as it last finished, by verb id, until [ScriptOp.finish] reports it. */
    private val scriptRuns = mutableMapOf<String, List<String>>()

    /** His verbs whose code broke the last time he did them, until they run clean or he rewrites them; while any is, writing code doesn't wear thin. */
    private val brokenVerbs = mutableSetOf<String>()

    /** What his things do by themselves, item id to its code; replaced rather than changed. */
    @Volatile var behaviors: Map<String, ThingCode> = emptyMap()
        private set

    /** His things' code over time: triggers, looks, timers, what happened where he wasn't ([Machines]). */
    private val machines = Machines(sandbox)
    private val effectHost: Surroundings.Host = object : Surroundings.Host {
        override val content get() = this@BedroomWorld.content
        override val layout get() = this@BedroomWorld.layout
        override val weather get() = this@BedroomWorld.weather
        override fun placed() = this@BedroomWorld.placed().map { (spec, cell) -> spec to if (spec.id == carrying) guy.cell else cell }
        override fun unplaced() = owned.keys.map(::item).filter { it.slot == null }
        override fun hisCell() = guy.cell
        override fun minutes() = clock.minutes(ticks)
        override fun light() = lightField(lantern = false)
        override fun sun() = sunlight.field(content, layout, house, clock.minuteOfDay(ticks), emptyList(), this@BedroomWorld.daylight())
        override fun daylight() = content.light?.let { lighting.daylight(it, clock.minuteOfDay(ticks), this@BedroomWorld.daylight()) } ?: Lighting.FULL
        override fun playingOn() = music?.on
    }
    private val machineHost: MachineHost = MachineWorld()
    private val reach = Reach(machineHost)
    /** His machines' stores, batches and wear ([Works]). */
    private val works = Works()
    private val worksHost: WorksHost = WorksWorld()
    /** The going price of each ware ([Market]). */
    private val market = Market()
    /** What he said on his latest trip that he's saving for, until he owns it. */
    private var savingFor: String? = null
    /** [on]: what it plays on, for the renderer's "playing" state. */
    private class Music(val itemId: String, val on: String?, val spec: BackgroundOp, val endTick: Long, val fresh: Double)

    /**
     * The world as [Op]s see it. The art and music parts are here rather than in the ops because
     * pieces, commissions and what's playing are this world's state.
     */
    private val ops = object : OpWorld {
        override val content get() = this@BedroomWorld.content
        override fun item(id: String) = this@BedroomWorld.item(id)
        override fun value(item: ItemSpec) = this@BedroomWorld.value(item)
        override fun priceOf(id: String) = price(item(id), item(id).sellSkill?.let(::skill) ?: 0.0)?.let { going(id, it) }
        override fun knows(id: String) = isKnown(id)
        override fun chaptersRead(book: String) = chapters[book] ?: 0
        override fun readChapter(book: String) = this@BedroomWorld.readChapter(book)
        override fun teaching(book: String) = content.book(book)?.teaches?.filter { it !in known }?.takeIf { it.isNotEmpty() }?.let(::taughtText)
        override fun practise(skill: String, d: Double) = this@BedroomWorld.practise(skill, d)
        override fun count(tag: String, extra: String?) = countTag(tag, extra)
        override fun take(tag: String, n: Int) = this@BedroomWorld.take(tag, n)
        override fun add(id: String, n: Int) = this@BedroomWorld.add(id, n)
        override fun change(need: Need, d: Double, reason: String) = needs.change(ticks, need, d, reason)
        override fun rate(need: Need, perHour: Double, reason: String) {
            if (perHour != 0.0) needs.change(ticks, need, perHour / 60, reason, rate = true)
        }
        override fun level(need: Need) = needs.level(need)
        override fun noun(tag: String) = this@BedroomWorld.noun(tag)
        override fun wanted(tag: String) = this@BedroomWorld.wanted(tag)
        override fun quantity(id: String, n: Int) = this@BedroomWorld.quantity(id, n)
        override fun sell(tag: String) = this@BedroomWorld.sell(tag)

        override fun saleText(tag: String): String {
            val counts = owned.filter { (id, _) -> tag in item(id).names }
            val sale = saleOf(held(tag))
            val bonus = if (winner(sale.map { it.first }) != null) ", $${content.commissions!!.reward} of it for the request" else ""
            return "$${sale.sumOf { it.second } + counts.entries.sumOf { (id, n) -> quote(id, n) }} for the ${counts.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }} you hold$bonus" +
                counts.keys.mapNotNull(::lowNote).joinToString("") { "; $it" }
        }

        override fun checkout(verb: String, most: Int): List<String> {
            val basket = baskets.remove(verb) ?: AuthoredResult.Basket(emptyList(), choseSaving = false)
            if (basket.choseSaving) savingFor = basket.savingFor?.takeIf { content.item(it)?.price != null }
            version++
            val parts = basket.items.take(most).map { id ->
                val spec = content.item(id)?.takeIf { isKnown(it.id) }
                (spec?.let { refusal(it) } ?: if (spec == null) "'$id' isn't for sale" else null)?.let { "didn't buy $id: $it" }
                    ?: bought(spec!!, spec.price!!)
            }.ifEmpty { listOf("bought nothing") }
            if (savingFor?.let { count(it) > 0 } == true) savingFor = null
            return parts + listOfNotNull(savingFor?.let { "saving for ${article(item(it).name)} ($${item(it).price})" })
        }

        override fun tripText(): String {
            val sold = tripItems()
            val within = sold.count { heldBack(it) == null && ledger.canAfford(it.price!!) }
            val saving = savingFor?.takeIf { count(it) == 0 }?.let(::item)?.let { w ->
                if (ledger.canAfford(w.price!!)) "; you can afford the ${w.name} you're saving for now"
                else "; you're saving for ${article(w.name)} ($${w.price}, $${w.price - ledger.balance} to go)"
            }.orEmpty()
            val food = if (foodTags.all { countTag(it) == 0 }) "; you have no food" else ""
            return "look through the whole catalog and buy food, supplies or things to own: $within of the ${sold.size} things in it you could buy now$food$saving"
        }

        override fun tripLock() = if (tripItems().isEmpty()) "there's nothing in the shop you don't have" else null

        override fun tripFailed(verb: String) = baskets[verb]?.failed?.let {
            baskets.remove(verb)
            "you couldn't make up your mind at the catalog, so you bought nothing"
        }

        override fun buildLock(): String? {
            val r = content.invent ?: return "there's nothing to build with"
            val today = clock.day(ticks)
            if (inventions.count { clock.day(it.tick) == today } >= r.perDay) return "you've built what you can today; a new idea needs a night's sleep"
            val cheapest = r.cheapest
            if (r.materials.keys.none { count(it) > 0 } && (cheapest == null || !ledger.canAfford(cheapest))) {
                return "you have no materials to build with; " + materialsSold(r.materials.keys) +
                    (cheapest?.let { ", and food or a book costs at least $$it and you have $${ledger.balance}" } ?: "")
            }
            return null
        }

        override fun buildFailed(verb: VerbSpec): String? {
            val why = unbuildable(verb) ?: return null
            designs.remove(verb.id)
            return why
        }

        private fun unbuildable(verb: VerbSpec): String? {
            val staged = designs[verb.id] ?: return "you couldn't settle on a design, so nothing got built"
            val design = staged.design ?: return "you couldn't settle on a design, so nothing got built"
            return when (val checked = content.invent!!.check(design, content, verb.minutes!!, ::inventSpot)) {
                is InventRules.Checked.Refused -> "the ${design.name.ifBlank { "design" }} didn't work: " + checked.problems.joinToString("; ")
                is InventRules.Checked.Ok -> checked.invention.let { made ->
                    if (made.bill.isNotEmpty()) shortOf(made.bill, "the ${made.item.name}")
                    else made.cost.takeIf { !ledger.canAfford(it) }?.let { "the ${made.item.name} costs $$it and you have $${ledger.balance}" }
                }
            }
        }

        override fun survey(lookout: String): String {
            val place = layout.placeOf(cellOf(lookout)) ?: return "nothing to find here"
            val first = explored.add(place.id)
            val found = patchesIn(place).filter { it !in known }
            if (found.isEmpty()) return if (first) "explored ${place.name}: nothing grows here to collect" else "looked round ${place.name} again; nothing new"
            known = known + found
            version++
            return "explored ${place.name} and found " + found.joinToString(" and ") { item(it).name }
        }

        override fun surveyText(lookout: String): String {
            val place = layout.placeOf(cellOf(lookout)) ?: return "a look round"
            return if (place.id in explored) "you've explored ${place.name} before" else "you haven't explored ${place.name} yet: there may be things to collect"
        }

        override fun bare(patch: String): String? {
            val until = bareUntil[patch]?.takeIf { it > ticks } ?: return null
            return "the ${item(patch).name} haven't grown back yet (until ${stamp(until)})"
        }

        override fun gather(patch: String): List<String> {
            val find = content.world?.find(patch) ?: return emptyList()
            add(find.yields, 1)
            bareUntil[patch] = ticks + clock.ticks(find.regrowHours * 60)
            version++
            return listOf("+1 ${item(find.yields).name}", "the ${item(patch).name} will grow back by ${stamp(bareUntil.getValue(patch))}" + if (weather != null) ", sooner if it rains" else "")
        }

        // Unbound, the argument is still its affordance ("growable"): nothing to say of a thing yet.
        override fun waterLock(planter: String) = content.item(planter)?.let { surroundings.waterLock(effectHost, it) }
        override fun water(planter: String): List<String> = surroundings.water(effectHost, item(planter)).also { version++ }
        override fun harvestLock(planter: String) = content.item(planter)?.let { surroundings.harvestLock(it) }
        override fun harvest(planter: String): List<String> = surroundings.harvest(item(planter)) { id, n -> add(id, n); item(id).name }.also { version++ }
        override fun soilText(planter: String) = content.item(planter)?.let { p -> surroundings.soilText(p, p.grows?.let { item(it.yields).name } ?: "harvest") } ?: "a planter's soil"
        override fun watchLock(lure: String) = content.item(lure)?.let { surroundings.watchLock(effectHost, it) }
        override fun watch(lure: String): List<String> = surroundings.watch(effectHost, item(lure)) { id, n -> add(id, n); item(id).name }.also { version++ }
        override fun watchText(lure: String) = content.item(lure)?.let { surroundings.watchText(effectHost, it) } ?: "visitors that come to it"

        override fun logLock(thing: String) = content.item(thing)?.takeIf { records.of(thing).isEmpty() }?.let { "the ${it.name} hasn't logged anything yet; it logs each hour" }

        override fun readLog(thing: String) = StationLog.summary(records.of(thing), ::stamp, clock.ticks(60).toLong()).let { listOf("the ${item(thing).name}'s log") + it }

        override fun roomLock(plan: String, site: String) = this@BedroomWorld.roomLock(plan, site)

        override fun placeRoom(plan: String, site: String) = this@BedroomWorld.placeRoom(plan, site)

        override fun mailLock(): String? = if (mailbox.isEmpty()) "no letters are waiting at the mail hatch" else null

        override fun openMail(): List<String> {
            val l = mailbox.removeFirst()
            opened += l.id
            lettersOpened = opened.toSet()
            read += l.copy(tick = ticks)
            correspond()
            version++
            val left = if (mailbox.isEmpty()) "" else "${mailbox.size} more waiting"
            return listOfNotNull("a letter from ${l.from}, someone outside your world, reads: \"${l.body}\"", left.ifEmpty { null })
        }

        override fun letterLock(): String? {
            if (content.letters == null) return "you can't write letters"
            if (correspondence.isEmpty()) return "nobody has written to you yet, so there's nobody to write to"
            return if (correspondence.all { it.blocked != null }) correspondence.joinToString("; ") { it.blocked!! } else null
        }

        override fun letterFailed(verb: String): String? {
            val d = drafts[verb]
            val why = when {
                d == null || d.failed != null || d.body.isNullOrBlank() -> "you couldn't find the words, so you didn't send a letter"
                else -> correspondence.firstOrNull { it.sender == d.to }?.let { it.blocked } ?: if (correspondence.none { it.sender == d.to }) "that's nobody who has written to you" else null
            } ?: return null
            drafts.remove(verb)
            return why
        }

        override fun postLetter(verb: String): List<String> {
            val d = drafts.remove(verb)!!
            val c = correspondence.first { it.sender == d.to }
            val body = d.body!!.trim().take(content.letters!!.maxLength)
            val out = OutLetter(sent.size + 1, c.sender, c.name, body, ticks, clock.day(ticks), GameClock.hhmm(clock.minuteOfDay(ticks)), read.lastOrNull { it.sender == c.sender }?.id)
            sent += out
            lettersSent = sent.toList()
            correspond()
            version++
            return listOf("wrote to ${c.name} and put the letter through the mail hatch" + if (c.unanswered.isEmpty()) "" else ", answering ${if (c.unanswered.size == 1) "their letter" else "their ${c.unanswered.size} letters"}")
        }

        override fun letterText(): String? {
            val open = correspondence.filter { it.blocked == null }
            if (open.isEmpty()) return null
            val waiting = open.filter { it.unanswered.isNotEmpty() }
            if (waiting.isNotEmpty()) return waiting.joinToString(" and ") { it.name } + (if (waiting.size == 1 && waiting[0].unanswered.size == 1) "'s letter is" else " are") + " waiting for an answer"
            return "write to " + open.joinToString(" or ") { it.name } + ", who wrote to you before"
        }

        override fun mailText(): String? = mailbox.firstOrNull()?.let { l -> "a letter from ${l.from}" + if (mailbox.size > 1) " (${mailbox.size} waiting)" else "" }

        override fun scriptLock(b: Bound): String? {
            val code = b.verb.code ?: return "it has no code"
            val run = sandbox.call(code.source, ScriptSandbox.CAN_DO, ScriptView("your verb ${b.verb.id}", b::paid), scriptArgs(b), b.verb.id, readOnly = true)
            if (run.error != null) return "your code's canDo broke: ${run.error}"
            return run.result?.takeIf { it.isNotBlank() && it != "true" && it != "null" }
        }

        override fun runScript(b: Bound): String? {
            val code = b.verb.code ?: return "it has no code"
            val view = ScriptView("your verb ${b.verb.id}", b::paid, b.verb.minutes ?: 60)
            val run = sandbox.call(code.source, ScriptSandbox.FINISH, view, scriptArgs(b), b.verb.id)
            version++
            val did = view.did + run.says.map { "you said \"$it\"" }
            if (run.error != null) {
                scriptRuns.remove(b.verb.id)
                brokenVerbs += b.verb.id
                val undone = view.undo()
                return "your code broke: ${run.error}" + if (undone.isEmpty()) "" else "; what it did before that is undone (${undone.joinToString(", ")})"
            }
            brokenVerbs -= b.verb.id
            scriptRuns[b.verb.id] = did + listOfNotNull(run.result?.takeIf { it.isNotBlank() })
            return null
        }

        override fun scriptParts(verb: String): List<String> = scriptRuns.remove(verb).orEmpty().ifEmpty { listOf("your code changed nothing") }

        override fun defineFailed(verb: VerbSpec): String? {
            val staged = codes[verb.id]
            val why = when {
                staged == null -> "you couldn't settle on the code, so nothing changed"
                staged.design == null -> "you couldn't get the code written" + (staged.failed?.let { ": $it" } ?: "")
                else -> CodeRules.problems(staged.design, content, sandbox).takeIf { it.isNotEmpty() }?.let { "the code didn't work: " + it.joinToString("; ") }
            } ?: return null
            codes.remove(verb.id)
            return why
        }

        override fun define(verb: VerbSpec): List<String> = putCode(codes.remove(verb.id)!!.design!!)

        override fun defineText(): String {
            val n = coded.size
            return "write the code for a new thing to do, or rewrite one of yours" + if (n == 0) "" else ": you've written $n (${coded.joinToString { it.verb.id }})"
        }

        override fun tendLock(id: String) = works.tendLock(worksHost, id)
        override fun tendText(id: String) = works.tendText(worksHost, id)
        override fun tend(id: String) = works.tend(worksHost, id)
        override fun repairLock(id: String) = works.repairLock(worksHost, id)
        override fun repairText(id: String) = works.repairText(worksHost, id)
        override fun repair(id: String) = works.repair(worksHost, id)

        // Unbound, the argument is still its tag ("invention"): nothing to say of a thing yet.
        override fun dismantleLock(id: String): String? {
            val spec = content.item(id) ?: return null
            if (inventions.none { it.item.id == id }) return "the ${spec.name} isn't something you invented; only your own inventions can be taken apart"
            if (count(id) == 0) return "you don't have the ${spec.name}"
            return null
        }

        override fun dismantleText(id: String): String {
            val made = inventions.firstOrNull { it.item.id == id } ?: return "take one of your inventions apart for half its materials, and its spot if it stands somewhere"
            val back = content.invent!!.salvaged(made.bill)
            return "take the ${made.item.name} apart: " + (if (back.isEmpty()) "nothing back" else "back " + back.entries.joinToString { (m, n) -> quantity(m, n) }) +
                (made.item.slot?.let { ", and $it is free" } ?: "")
        }

        override fun dismantle(id: String) = this@BedroomWorld.dismantle(id)

        override fun build(verb: VerbSpec): List<String> {
            val staged = designs.remove(verb.id)!!
            val design = staged.design!!
            val rules = content.invent!!
            val body = (rules.check(design, content, verb.minutes!!, ::inventSpot) as InventRules.Checked.Ok).invention.copy(tick = ticks)
            // Its workings (the wiring its code needs, a mechanism for moving parts) go in only if he has them too.
            val code = staged.code?.takeIf { !it.isEmpty }
            val workings = rules.workings(design, code)
            val wired = workings.isEmpty() || shortOf(plus(body.bill, workings), "it") == null
            val bill = if (wired) plus(body.bill, workings) else body.bill
            val made = if (bill.isEmpty()) body else body.copy(bill = bill, cost = rules.value(bill, content), wired = wired && code != null)
            val spec = made.item
            this@BedroomWorld.content = content.plus(made)
            inventions = inventions + made
            knownCache = null
            val paid = if (bill.isEmpty()) {
                ledger.debit(ticks, made.cost, "materials for the ${spec.name}")
                "materials $${made.cost} (money $${ledger.balance})"
            } else {
                takeBill(bill)
                "used " + bill.entries.joinToString { (m, n) -> quantity(m, n) } + " ($${made.cost} of materials)"
            }
            add(spec.id, 1)
            val like = spec.qualities.entries.joinToString { (q, s) -> "$q ${s.toInt()}" }
            val comfortNote = if (comfortOf(spec) != 0) comfortText() else null
            val newNote = newThingRate(spec).takeIf { it > 0 }?.let { newThings[spec.id] = ticks; "new: ${newThingText(it)}" }
            return listOfNotNull(
                "built the ${spec.name}, ${article(spec.group)} of your own design" + (if (like.isEmpty()) "" else " ($like)") + (spec.slot?.let { " at $it" } ?: ""),
                paid,
                spec.price?.let { "the shop will send more for $$it" },
                spec.sellPrice?.let { "it sells for $$it" },
                comfortNote,
                newNote,
            ) + when {
                // What it does, written with it: its verbs and its behavior, now that it has an id.
                code != null && wired -> putCode(code.about(spec.id))
                code != null -> listOf("you didn't have " + workings.entries.joinToString(" and ") { (m, n) -> quantity(m, n) } + " for its workings, so it's built but doesn't do anything yet")
                else -> listOfNotNull(staged.codeFailed?.let { "you didn't get its code written ($it), so it doesn't do anything yet" })
            }
        }

        override fun buildText(): String {
            val r = content.invent ?: return "design and build a new thing"
            // With none, where they come from: a model deciding doesn't otherwise connect the workbench to the shop.
            val stock = r.materials.keys.filter { count(it) > 0 }.joinToString { quantity(it, count(it)) }.ifEmpty { "none yet: " + materialsSold(r.materials.keys) }
            val dollars = r.cheapest?.let { "; food or a book from $$it" }.orEmpty()
            val sp = spots()
            val free = sp.free()
            val ground = free.count { !sp.isWall(it) }
            val wall = free.count(sp::isWall)
            val room = when {
                ground == 0 && wall == 0 -> "; no free spot on the ground or in a wall left for a new thing to stand: dismantle one of yours or move something first"
                ground == 0 -> "; no free spot on the ground left ($wall in a wall): dismantle one of yours or move something to make room"
                else -> "; $ground free spots on the ground, $wall in a wall"
            }
            return "design a new thing of your own and build it: ${r.uses.keys.joinToString(" or ") { useNoun(it) }}; things are built from your materials (you have $stock)$dollars$room"
        }

        override fun switchLock(lamp: String, to: LampMode): String? {
            val spec = content.item(lamp) ?: return null
            if (lampMode(lamp) != to) return null
            return when (to) {
                LampMode.On -> "the ${spec.name} is already switched on"
                LampMode.Off -> "the ${spec.name} is already switched off"
                LampMode.Timer -> "the ${spec.name} is already on its timer (lit ${timerOf(spec).text})"
            }
        }

        override fun switch(lamp: String, to: LampMode): String {
            if (to == LampMode.Timer) lampModes.remove(lamp) else lampModes[lamp] = to
            version++
            val name = item(lamp).name
            return when (to) {
                LampMode.On -> "switched the $name on: "
                LampMode.Off -> "switched the $name off: "
                LampMode.Timer -> "put the $name "
            } + switchText(lamp, to)
        }

        // Short: the option already names the lamp.
        override fun switchText(lamp: String, to: LampMode): String {
            val spec = item(lamp)
            return when (to) {
                LampMode.On -> "it stays on until you switch it again"
                LampMode.Off -> "it stays off until you switch it again"
                LampMode.Timer -> "back on its timer, lit ${timerOf(spec).text}" + if (lampLit(spec, to)) "" else " (off now)"
            }
        }

        override fun hangLock(tag: String): String? {
            if (countTag(tag) == 0) return "you have no ${noun(tag)} to hang"
            if (hung().size >= content.gallery.max) return "your wall is full (${content.gallery.max} pieces)"
            return null
        }

        override fun hang(tag: String) = this@BedroomWorld.hang(tag)

        override fun hangText(tag: String) = listOfNotNull(held(tag).lastOrNull()?.let { "hangs ${pieceName(it)}" }) +
            "comfort +${content.gallery.comfortEach} while you can see it${if (content.light != null) " lit" else ""} (${hung().size} of ${content.gallery.max} on the wall)"

        override fun await(op: AuthoredOp, b: Bound, piece: Int?) {
            authored.addLast(AuthoredView(++authoredCount, b.verb.id, op.handler, ticks, piece))
            while (authored.size > AUTHORED_KEPT) authored.removeFirst()
        }

        override fun newestPiece(item: String) = pieces.lastOrNull { it.item == item }?.id

        override fun play(item: String, on: String?, bg: BackgroundOp, fresh: Double): String {
            music = Music(item, on, bg, ticks + clock.ticks(bg.minutes), fresh)
            version++
            return "${item(item).name} playing for ${GameClock.duration(bg.minutes)}"
        }

        override fun playing(name: String) = music?.takeIf { it.spec.name == name }?.let { it.itemId to ((it.endTick - ticks) / clock.ticksPerMinute).toInt() }
    }

    init {
        content.start.items.forEach { (id, n) -> add(id, n) }
        publishFirst()
    }

    // --- ownership -----------------------------------------------------------------------------

    fun count(id: String): Int = owned[id] ?: 0

    /** Where [id]'s slot is on the map. */
    private fun cellOf(id: String): Cell = layout.slotCells.getValue(item(id).slot!!)

    /** The patches (world.yaml `finds`) whose slots are in [place]. */
    private fun patchesIn(place: Place): List<String> =
        content.world?.finds.orEmpty().map { it.patch }.filter { content.item(it) != null && layout.placeOf(cellOf(it)) == place }

    /** Outdoor places he has explored, by id. */
    fun exploredPlaces(): Set<String> = explored.toSet()

    private fun item(id: String): ItemSpec = content.item(id) ?: error("no item '$id'")

    /** How many owned items answer to [tag] (an id or an affordance), plus one more [extra] if given (for "what if he bought it"). */
    private fun countTag(tag: String, extra: String? = null): Int =
        owned.entries.sumOf { (id, n) -> if (answers(id, tag)) n else 0 } + if (extra != null && answers(extra, tag)) 1 else 0

    /** Whether [id] answers to [tag]: its id or an affordance, or `machine` for a thing with code, which wears and is mended like one. */
    private fun answers(id: String, tag: String): Boolean = tag in item(id).names || (tag == Works.MACHINE && id in behaviors) ||
        (tag == INVENTION && inventions.any { it.item.id == id })

    private fun add(id: String, n: Int) {
        version++
        if (item(id).carried) repeat(n) { pieces += Piece(pieces.size + 1, id, openCommission()?.id, item(id).sellSkill?.let(::skill) ?: 0.0) }
        owned[id] = count(id) + n
        syncHolding()
    }

    /** Removes [n] items with [tag], first-listed first; returns what was taken. */
    private fun take(tag: String, n: Int): Map<String, Int> {
        version++
        val taken = LinkedHashMap<String, Int>()
        var left = n
        for (id in owned.keys.toList()) {
            if (left == 0) break
            if (tag !in item(id).names) continue
            val k = min(left, count(id))
            owned[id] = count(id) - k
            if (owned[id] == 0) owned.remove(id)
            taken[id] = k
            left -= k
        }
        syncHolding()
        return taken
    }

    private fun syncHolding() {
        guy.inventory.clear()
        guy.inventory += holding()
    }

    private fun holding(): List<String> = owned.flatMap { (id, n) -> if (item(id).carried) List(n) { id } else emptyList() }

    /** What he owns apart from carried art and fixtures like the door. */
    private fun ownsMap(): Map<String, Int> = owned.filter { (id, _) -> item(id).let { !it.carried && it.group != "fixture" } }

    /** Owned items that sit in a slot, with their tiles. */
    private fun placed(): List<Pair<ItemSpec, Cell>> = owned.keys.mapNotNull { id ->
        val spec = item(id)
        spec.slot?.let { layout.slotCells[it] }?.let { spec to it }
    }

    /** What [spec] adds to comfort while it sits in its slot: its value to him, in view. Items without a slot are out of sight. */
    private fun comfortOf(spec: ItemSpec): Int = if (spec.slot == null) 0 else (value(spec) * content.qualityValue.inView).roundToInt()

    /** [BedroomContent.value] with what his skills add: each skill that deepens a quality raises his affinity for it. */
    private fun value(spec: ItemSpec): Double {
        val r = content.skills
        if (r.affinityPerLevel == 0.0 || r.deepens.isEmpty()) return content.value(spec)
        return content.value(spec) + spec.qualities.entries.sumOf { (q, strength) ->
            strength * r.affinityPerLevel * r.deepens.filterValues { it == q }.keys.sumOf(::skill)
        }
    }

    /**
     * The comfort of what he can see from where he stands ([ComfortRules]): each thing's value by how
     * near and how lit it is, hung art where it hangs, his collection at its shelf, and the room he's in.
     * Without comfort rules, everything in its slot counts in full.
     */
    private fun comfort(): Int {
        val seen = { cell: Cell? -> if (content.comfort == null) 1.0 else cell?.let(::seenWeight) ?: 0.0 }
        val here = layout.placeOf(guy.cell)?.id
        return (
            placed().sumOf { (spec, cell) -> comfortOf(spec) * maxOf(seen(cell), if (spec.sound != null) surroundings.heard(effectHost, spec, guy.cell) else 0.0) * usedTo(spec.id) } +
                hung().size * content.gallery.comfortEach * seen(layout.slotCells["rail"]) +
                collectionComfort() * seen(layout.slotCells["shelf"]) +
                house.keys.sumOf { id -> roomComfort(id) * if (content.comfort == null) 1.0 else if (id == here) seen(guy.cell) else 0.0 }
            ).roundToInt()
    }

    /** How much of a thing's comfort is left now he's used to it ([ComfortRules.Adapt]): 1 when new, or without `adapt`. */
    private fun usedTo(id: String): Double {
        val adapt = content.comfort?.adapt ?: return 1.0
        val since = ownedSince[id] ?: return 1.0
        return adapt.share((ticks - since).toDouble() / clock.ticks(60) / 24)
    }

    /** How much a thing at [cell] counts toward what he sees now, 0 to 1: near and lit ([Lighting.viewWeight]). */
    private fun seenWeight(cell: Cell): Double {
        val rules = content.comfort ?: return 1.0
        val asleep = doing?.activity?.effects?.any { !it.awake } == true
        return Lighting.viewWeight(rules, content.light, layout, lightField(), guy.cell, cell, asleep)
    }

    /** "comfort 30 from what you can see here, so mood settles toward 80". */
    private fun comfortText(): String = "comfort ${comfort()}" + (if (content.comfort != null) " from what you can see here" else "") + ", so mood settles toward ${moodTarget().toInt()}"

    // --- light -----------------------------------------------------------------------------------

    /** How [lamp] is set: on its timer unless he has switched it. */
    private fun lampMode(lamp: String): LampMode = lampModes[lamp] ?: LampMode.Timer

    /** When [spec], a lamp, is lit on its timer: its own, or rules.yaml's. */
    private fun timerOf(spec: ItemSpec): LampTimer = spec.light?.timer ?: content.light?.timer ?: LampTimer("18:00", "00:00")

    /**
     * Whether [spec]'s light is on now, set to [mode]: a lamp by its switch or its timer, a patch
     * unless it's been picked bare, and anything else with a light always.
     */
    private fun lampLit(spec: ItemSpec, mode: LampMode = lampMode(spec.id)): Boolean {
        if (spec.light == null || (bareUntil[spec.id] ?: 0) > ticks) return false
        if (LAMP !in spec.affords) return true
        return when (mode) {
            LampMode.On -> true
            LampMode.Off -> false
            LampMode.Timer -> timerOf(spec).covers(clock.minuteOfDay(ticks))
        }
    }

    /** What gives off light now: each placed thing whose light is on ([lampLit]; one he's carrying, where he is), and his [lantern]. */
    private fun lamps(lantern: Boolean = true): List<Emitter> = placed().mapNotNull { (spec, cell) ->
        spec.light?.takeIf { lampLit(spec) }?.let { Emitter(spec.id, "the ${spec.name}", EffectKind.Light, if (spec.id == carrying) guy.cell else cell, it.strength, it.radius) }
    } + listOfNotNull(content.light?.lantern?.takeIf { lantern }?.let { Emitter(LANTERN, "your lantern", EffectKind.Light, guy.cell, it.strength, it.radius) })

    /**
     * The light on every tile now, worked out again only when the daylight, the layout or the lamps change.
     * Without his [lantern] it's the light to work by: a verb's `needs` don't count the lantern, which is
     * for seeing by, and goes wherever he does.
     */
    fun lightField(lantern: Boolean = true): Field = lighting.field(content, layout, house, clock.minuteOfDay(ticks), lamps(lantern) + surroundings.codeLights(effectHost), daylight())

    /** Degrees on every tile now: the weather, the house, heaters, fires and what he wears ([Surroundings.warmth]). */
    fun warmthField(): Field = surroundings.warmth(effectHost)

    /** How loud it is on every tile now ([Surroundings.sound]). */
    fun soundField(): Field = surroundings.sound(effectHost)

    /** The visitors at his things now ([Surroundings.visits]). */
    fun visits(): List<Visit> = surroundings.visits(effectHost)

    /** The things [a] is done at with [args] that are lit enough for it, of [ids]; all of them when it needs no light. */
    private fun litEnough(a: VerbSpec, ids: List<String>): List<String> {
        val need = a.needs[EffectKind.Light.id] ?: return ids
        val f = lightField(lantern = false)
        return placed().filter { (spec, cell) -> spec.id in ids && f[cell] >= need }.map { it.first.id }.ifEmpty { ids }
    }

    /** "too dark to read at the bed or the chair (light 0.4; it needs 3)": where [a] would be done is too dark; null when one is lit enough, or arguments still to bind decide. */
    private fun darkLock(a: VerbSpec, args: Map<String, String>?): String? {
        val need = a.needs[EffectKind.Light.id] ?: return null
        val f = lightField(lantern = false)
        val at: List<Pair<String?, Cell>> = if (VerbSpec.HERE in a.at) {
            listOf(null to guy.cell)
        } else {
            if (args == null && a.at.any { it in a.args }) return null
            val ids = a.at.map { args?.get(it) ?: it }
            placed().filter { it.first.id in ids }.map { (spec, cell) -> spec.name to cell }
        }
        if (at.isEmpty()) return null
        val best = at.maxOf { f[it.second] }
        if (best >= need) return null
        val where = if (at.first().first == null) "here" else "at " + at.joinToString(" or ") { "the ${it.first}" }
        return "$DARK${a.id} $where (light ${Lighting.level(best, down = true)}; it needs ${Lighting.level(need)})"
    }

    /** "too cold to explore at the garden bench (3°; it needs 4)": the warmth where [a] would be done is short of what it `needs`; null otherwise. */
    private fun coldLock(a: VerbSpec, args: Map<String, String>?): String? {
        val need = a.needs[EffectKind.Warmth.id] ?: return null
        val f = warmthField()
        val at: List<Pair<String?, Cell>> = if (VerbSpec.HERE in a.at) {
            listOf(null to guy.cell)
        } else {
            if (args == null && a.at.any { it in a.args }) return null
            val ids = a.at.map { args?.get(it) ?: it }
            placed().filter { it.first.id in ids }.map { (spec, cell) -> spec.name to cell }
        }
        if (at.isEmpty()) return null
        val best = at.maxOf { f[it.second] }
        if (best >= need) return null
        val where = if (at.first().first == null) "here" else "at " + at.joinToString(" or ") { "the ${it.first}" }
        return "too cold to ${a.id} $where (${Surroundings.deg(best)}; it needs ${Surroundings.deg(need)})"
    }

    /** Whether he knows [e] is there without seeing it: his house's things, and what's in a place he has explored. */
    private fun knownThere(e: EntitySnapshot): Boolean = layout.place(e.place ?: "")?.kind == PlaceKind.Room || e.place in explored

    /** What room [id] is like to him while he's in it: its qualities, by his affinities, as decor's are. */
    private fun roomComfort(id: String): Double {
        val r = content.world?.room(id) ?: return 0.0
        return r.qualities.entries.sumOf { (q, s) -> s * content.personality.affinity(q) } * content.qualityValue.inView
    }

    /** Why [plan]'s room can't go on [site] now, or null. */
    private fun roomLock(plan: String, site: String): String? {
        val world = content.world ?: return "there's no land to build on"
        // Unbound (asked before he has a plan), the arguments' own locks say what's missing.
        val spec = content.item(plan) ?: return null
        val room = world.roomFor(plan) ?: return "the ${spec.name} isn't for a room"
        val s = world.site(site) ?: return null
        if (house[room.id] == site) return "${room.name} stands there already"
        house.entries.firstOrNull { it.value == site }?.let { (other, _) -> return "${world.room(other)?.name ?: other} stands there" }
        if (s.nextTo != null && s.nextTo !in house.values) return "it needs a room ${world.site(s.nextTo)?.name ?: s.nextTo} first"
        val leaning = world.sites.filter { it.nextTo == house[room.id] && it.id in house.values }
        if (leaning.isNotEmpty() && house[room.id] != null) return "another room is built onto ${room.name}"
        return null
    }

    /** Builds [plan]'s room on [site], or moves it there if it stands elsewhere: the map, places and doors change with it. */
    private fun placeRoom(plan: String, site: String): List<String> {
        val world = content.world ?: return emptyList()
        val room = world.roomFor(plan) ?: return emptyList()
        val moved = house.containsKey(room.id)
        val old = layout.place(room.id)
        house[room.id] = site
        layout = Layout.of(content, house)
        map = layout.map
        room.items.forEach { if (count(it) == 0) add(it, 1) }
        version++
        mapCache = null
        placesCache = null
        // Standing where the room was, he steps out to his bedroom.
        if (old != null && guy.cell in old.bounds && layout.placeOf(guy.cell)?.id != room.id) {
            layout.spot(layout.places.first())?.let { guy.x = it.x + 0.5f; guy.y = it.y + 0.5f }
        }
        val where = world.site(site)!!.name
        return listOf(if (moved) "moved ${room.name} to $where" else "built ${room.name} $where")
    }

    /** Each kind of sample he keeps adds comfort, up to the rule's most kinds. */
    private fun collectionComfort(): Int = content.collection?.let { c ->
        minOf(c.max, owned.keys.count { "specimen" in item(it).names }) * c.comfortEach
    } ?: 0

    /** How to say [name] in a sentence: an item's name, or for an affordance, the kind its items share ("book"). */
    private fun noun(name: String): String {
        val options = content.withName(name)
        return options.singleOrNull()?.name ?: options.map { it.group }.distinct().singleOrNull() ?: name.replace('_', ' ')
    }

    /** How far boredom lowers the mood he settles to. */
    private fun boredom(): Double = (content.needs.bored - needs[Need.Stimulation]).coerceAtLeast(0.0) * content.needs.boredMood

    /** How far frustration lowers the mood he settles to. */
    private fun frustrated(): Double = content.frustration?.let { f -> (needs[Need.Frustration] - f.above).coerceAtLeast(0.0) * f.mood } ?: 0.0

    private fun moodTarget(): Double = (content.needs.moodBaseline + comfort() - boredom() - frustrated()).coerceIn(0.0, Needs.MAX)

    // --- the verbs he writes --------------------------------------------------------------------

    /** Puts [made] into his world: a new verb, or in place of his verb of the same id. */
    private fun putVerb(made: CodedVerb) {
        content = content.plusVerb(made.verb)
        coded = if (coded.any { it.verb.id == made.verb.id }) coded.map { if (it.verb.id == made.verb.id) made else it } else coded + made
        knownCache = null
        options = null
        version++
    }

    /** Puts in what [d] writes (its verbs, then its behaviors) as far as it checks out; the event's parts, a line for each and for what didn't go in. */
    private fun putCode(d: CodeDesign): List<String> {
        val parts = mutableListOf<String>()
        d.verbs.forEach { v ->
            when (val r = CodeRules.check(v, content, sandbox)) {
                is CodeRules.Checked.Refused -> parts += "verb '${v.called}' didn't go in: " + r.problems.joinToString("; ")
                is CodeRules.Checked.Ok -> runCatching { putVerb(CodedVerb(r.verb, d.why.trim(), ticks)) }
                    .onSuccess {
                        brokenVerbs -= r.verb.id
                        val code = r.verb.code!!
                        parts += (if (r.rewrote) "rewrote your verb ${r.verb.id} (version ${code.version})" else "a new verb of your own, ${r.verb.id}") + ": ${code.says}" +
                            " (${GameClock.duration(r.verb.minutes!!)}" + (r.verb.args["what"]?.let { ", with ${article(noun(it))}" } ?: "") + ")"
                    }
                    .onFailure { e -> parts += "verb '${v.called}' didn't go in: ${e.message}" }
            }
        }
        d.behaviors.forEach { b ->
            CodeRules.problem(b, content, sandbox)?.let { parts += "$it, so it didn't go in"; return@forEach }
            // A thing he built from materials does something only once it's wired: code written for it later takes the wiring then.
            val made = inventions.firstOrNull { it.item.id == b.thing }?.takeIf { it.bill.isNotEmpty() && !it.wired }
            val wiring = content.invent?.workings?.code.orEmpty()
            if (made != null && wiring.isNotEmpty()) {
                shortOf(wiring, "wiring the ${made.item.name}")?.let { parts += "the ${made.item.name}'s code didn't go in: $it"; return@forEach }
                takeBill(wiring)
                inventions = inventions.map { if (it === made) it.copy(bill = plus(it.bill, wiring), wired = true) else it }
                parts += "wired the ${made.item.name} with " + wiring.entries.joinToString(" and ") { (m, n) -> quantity(m, n) }
            }
            val was = behaviors[b.thing]
            behaviors = behaviors + (b.thing to ThingCode(b.code, b.says.trim(), (was?.version ?: 0) + 1, ticks, every = b.everyMinutes))
            parts += "the ${item(b.thing).name} " + (if (was != null) "does something new by itself: " else "now does something by itself: ") + b.says.trim()
        }
        version++
        return parts
    }

    /** The world as [Machines] and [Reach] see it. */
    private inner class MachineWorld : MachineHost {
        override val content get() = this@BedroomWorld.content
        override val layout get() = this@BedroomWorld.layout
        override val ticks get() = this@BedroomWorld.ticks
        override val behaviors get() = this@BedroomWorld.behaviors
        override fun setBehavior(id: String, code: ThingCode?) {
            this@BedroomWorld.behaviors = if (code == null) behaviors - id else behaviors + (id to code)
        }
        override fun count(id: String) = this@BedroomWorld.count(id)
        override fun knows(id: String) = isKnown(id)
        override fun cellOf(id: String) = content.item(id)?.slot?.let { layout.slotCells[it] }
        override fun hisCell() = guy.cell
        override fun bareUntil(patch: String) = this@BedroomWorld.bareUntil[patch]?.takeIf { it > ticks }
        override fun setBareUntil(patch: String, until: Long?) {
            if (until == null) this@BedroomWorld.bareUntil.remove(patch) else this@BedroomWorld.bareUntil[patch] = until
            version++
        }
        override fun ticksIn(minutes: Double) = (minutes * clock.ticksPerMinute).roundToLong()
        override fun minutes() = clock.minutes(ticks)
        override fun minuteStart() = clock.isMinuteStart(ticks)
        override fun stamp(tick: Long) = this@BedroomWorld.stamp(tick)
        override fun invention(id: String) = inventions.firstOrNull { it.item.id == id }
        override fun event(text: String, place: String?) = this@BedroomWorld.event(text, place)
        override fun changed() {
            version++
        }
        override fun broken(id: String) = works.broken(id)
        override fun wore(id: String) = works.wore(worksHost, id)

        /** A thing's code runs as "your <thing>"; its gains in mood and stimulation wear thin as a verb's do, by how often it runs. */
        override fun run(id: String, code: ThingCode, fn: String, extra: List<Any?>, limit: Long): MachineRun {
            val key = "$THING_WEAR$id"
            val fresh = freshness(key)
            val view = ScriptView("your ${item(id).name}", { name, d -> paid(needOf(name), d, fresh) }, self = id)
            val run = sandbox.call(code.source, fn, view, emptyMap(), id, extra = listOf(view.thing(id)) + extra, limit = limit)
            content.habituation?.takeIf { view.gained }?.let { h ->
                val share = if (fn == Triggers.EVERY) (code.every ?: 60) / 60.0 else 1.0
                wear[key] = (1.0 - fresh + h.perRepeat * share.coerceAtMost(1.0)).coerceAtMost(1.0) to ticks
            }
            // What broken code changed before it broke is undone, as for his verbs.
            return MachineRun(run, view.did, if (run.error != null) view.undo() else emptyList())
        }
    }

    /** The world as [Works] sees it. */
    private inner class WorksWorld : WorksHost {
        override val content get() = this@BedroomWorld.content
        override val layout get() = this@BedroomWorld.layout
        override val coded: Set<String> get() = behaviors.keys
        override fun count(id: String) = this@BedroomWorld.count(id)
        override fun standing(id: String) = content.item(id)?.slot?.takeIf { count(id) > 0 && id != carrying }?.let { layout.slotCells[it] }
        override fun hisCell() = guy.cell
        override fun effect(kind: EffectKind, cell: Cell) = when (kind) {
            EffectKind.Light -> if (content.light == null) EffectKind.Light.most else lightField(lantern = false)[cell]
            else -> surroundings.field(effectHost, kind.id)?.get(cell) ?: 0.0
        }
        override fun add(id: String, n: Int) = this@BedroomWorld.add(id, n)
        override fun take(id: String, n: Int) = this@BedroomWorld.take(id, n).values.sum()
        override fun skill(name: String) = this@BedroomWorld.skill(name)
        override fun quantity(id: String, n: Int) = this@BedroomWorld.quantity(id, n)
        override fun tell(text: String, place: String?) = machines.placed(machineHost, text, place)
        override fun changed() {
            version++
        }
    }

    /** How fresh what wears by [key] is: 1 when fresh, down to 0 when worn out (`thing:<id>` for a thing's code). */
    private fun freshness(key: String): Double {
        val h = content.habituation ?: return 1.0
        val (level, since) = wear[key] ?: return 1.0
        val hours = (ticks - since).toDouble() / clock.ticks(60)
        return 1.0 - (level - h.recoverPerHour * hours).coerceIn(0.0, 1.0)
    }

    // --- weather ---------------------------------------------------------------------------------

    /**
     * Each game hour: if it rained the hour just gone, bare patches grow back [WeatherRules.Rain.regrow] hours
     * instead of one, and every recorder he owns logs the weather at its spot.
     */
    private fun weatherHour() {
        val w = weather ?: return
        val hour = clock.minutes(ticks) / 60
        if (w.sky(hour - 1) == Sky.Rain && w.rules.rain.regrow > 1 && bareUntil.values.any { it > ticks }) {
            val sooner = clock.ticks(((w.rules.rain.regrow - 1) * 60).roundToInt()).toLong()
            bareUntil.replaceAll { _, until -> if (until > ticks) until - sooner else until }
            version++
        }
        // A recorder he's carrying somewhere else logs nothing until it's set down.
        owned.keys.filter { RECORDER in item(it).affords && item(it).slot != null && it != carrying }.forEach { id ->
            val c = w.at(clock.minutes(ticks), outdoors(cellOf(id)))
            records.record(id, StationLog.TEMPERATURE, ticks, Weather.round1(warmthField()[cellOf(id)]))
            records.record(id, StationLog.RAIN, ticks, Weather.round1(c.rain))
            records.record(id, StationLog.PRESSURE, ticks, Weather.round1(c.pressure))
            records.record(id, StationLog.LIGHT, ticks, Weather.round1(lightField()[cellOf(id)]))
            surroundings.visitorsAt(effectHost, cellOf(id)).forEach { (visitor, n) -> runCatching { records.record(id, visitor, ticks, n) } }
        }
    }

    /** Visitors he had last hour, by lure, so their coming is said once. */
    private var visiting: Set<String> = emptySet()

    /**
     * Each game hour: planters grow in sun on damp soil, and visitors that have just come to one of his
     * things in his place are an event; ones elsewhere are an event filed under their own place.
     */
    private fun effectsHour(hour: Int) {
        if (content.growing != null) {
            val ripe = placed().filter { it.first.grows != null && surroundings.harvestLock(it.first) != null }.map { it.first.id }.toSet()
            surroundings.growHour(effectHost, hour)
            placed().filter { it.first.id in ripe && surroundings.harvestLock(it.first) == null }.forEach { (spec, cell) ->
                event("the ${spec.name}'s ${item(spec.grows!!.yields).name} are ready to pick", layout.placeOf(cell)?.id)
            }
            version++
        }
        val visits = visits()
        val now = visits.map { it.lure.id }.toSet()
        visits.filter { it.lure.id !in visiting }.forEach { v -> event("${v.count} ${v.visitor.name} came to the ${v.lure.name}", layout.placeOf(v.cell)?.id) }
        if (now != visiting) version++
        visiting = now
    }

    private fun outdoors(cell: Cell): Boolean = layout.placeOf(cell)?.kind == PlaceKind.Outdoor

    /** The weather at [cell] now; null without weather. */
    fun conditionsAt(cell: Cell): Conditions? = weather?.at(clock.minutes(ticks), outdoors(cell))?.let { c -> c.copy(temperature = warmthField()[cell]) }?.let { c -> if (content.light != null) c.copy(light = lightField()[cell]) else c }

    /** How much of the sun gets through the sky now, 0 to 1: what a light engine multiplies daylight by. 1 without weather. */
    fun daylight(): Double = weather?.daylight(clock.minutes(ticks) / 60) ?: 1.0

    /** The cold where he is ([WeatherRules.Cold]) when the warmth on his tile is below it, or null. */
    private fun coldNow(): WeatherRules.Cold? {
        val cold = weather?.rules?.cold ?: return null
        return cold.takeIf { warmthField()[guy.cell] < it.below }
    }

    /** The weather as he sees it from where he stands; null without weather or land. */
    private fun weatherText(): String? = weather?.takeIf { layout.outdoors }?.seen(clock.minutes(ticks), outdoors(guy.cell))

    private fun weatherView(): WeatherView? = weather?.let { w ->
        val minute = clock.minutes(ticks)
        val c = w.at(minute)
        WeatherView(c.sky.id, Weather.round1(c.temperature), Weather.round1(w.rain(minute / 60)), w.daylight(minute / 60))
    }

    /** `world.args`: each of the verb's argument slots as bound, or before binding what it asks for. */
    private fun scriptArgs(b: Bound): Map<String, String> = b.verb.args.keys.associateWith { b.resolve(it) }

    /** Content changed under the agent: what he knows and his options are rebuilt. */
    private fun contentChanged() {
        knownCache = null
        options = null
        version++
    }

    /** Where his things stand and could go, by the current content and map. */
    private fun spots() = Spots(content, layout) { count(it) > 0 }

    /**
     * Where a new thing of [place] (`floor` or `wall`) he invents goes: the room's first free spare
     * spot of that kind (room.yaml `spots`), else the free spot of that kind nearest the workbench,
     * in a room or on the land. Null when every one is taken.
     */
    fun inventSpot(place: String): String? = inventSpots(place).firstOrNull()

    /** Every free spot a new thing of [place] could go, in the order [inventSpot] takes them. */
    fun inventSpots(place: String): List<String> {
        val s = spots()
        val wall = place == InventRules.WALL
        val free = s.free().filter { s.isWall(it) == wall }
        val spare = (if (wall) content.room.spots.wall else content.room.spots.floor).filter { it in free }
        val bench = content.verbs.firstOrNull { v -> v.ops<BuildOp>().isNotEmpty() }?.at?.firstNotNullOfOrNull { content.item(it)?.slot?.let(layout.slotCells::get) } ?: guy.cell
        return spare + (free - spare.toSet()).mapNotNull { spot -> layout.distance(bench, layout.slotCells.getValue(spot))?.let { spot to it } }.sortedBy { it.second }.map { it.first }
    }

    /** [a] and [b] added up, material by material. */
    private fun plus(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> = LinkedHashMap(a).also { m -> b.forEach { (k, n) -> m.merge(k, n, Int::plus) } }

    /** Why [what] can't be built from his stock: what [bill] takes, what he has of it, and where the rest is sold. Null when he has it all. */
    private fun shortOf(bill: Map<String, Int>, what: String): String? {
        val short = bill.filter { (m, n) -> count(m) < n }
        if (short.isEmpty()) return null
        val has = short.keys.joinToString(" and ") { m -> if (count(m) == 0) "no ${quantity(m, 2).substringAfter(' ')}" else quantity(m, count(m)) }
        return "$what takes " + bill.entries.joinToString(" and ") { (m, n) -> quantity(m, n) } + "; you have $has. " + materialsSold(short.keys).replaceFirstChar { it.uppercase() }
    }

    /** "the shop sells wood planks (5 for $10) and glass panes (3 for $9) on a shopping trip". */
    private fun materialsSold(ids: Collection<String>): String =
        "the shop sells " + ids.mapNotNull(content::item).joinToString(" and ") { s -> quantity(s.id, 2).substringAfter(' ') + " (" + (if (s.pack > 1) "${s.pack} for " else "") + "$${s.price})" } +
            if (content.verbs.any { v -> v.ops<BasketOp>().isNotEmpty() }) " on a shopping trip" else ""

    /** Takes [bill]'s materials from his stock; he has them ([shortOf]). */
    private fun takeBill(bill: Map<String, Int>) = bill.forEach { (m, n) -> take(m, n) }

    /**
     * Takes apart [id], a thing he invented: it leaves his world with its book and the verbs he wrote
     * for it, its code stops, what its machine held comes to him, its spot is free, and he gets back
     * [InventRules.salvaged] of its bill. Returns the event's parts.
     */
    private fun dismantle(id: String): List<String> {
        val made = inventions.first { it.item.id == id }
        val spec = made.item
        val back = content.invent!!.salvaged(made.bill)
        val held = works.store(id).let { plus(it.inputs, it.outputs) }
        val hadCode = behaviors.containsKey(id)
        val gone = this.coded.filter { v -> id in v.verb.at || id in v.verb.args.values }
        content = content.minus(id)
        inventions = inventions.filter { it.item.id != id }
        if (gone.isNotEmpty()) this.coded = this.coded - gone.toSet()
        behaviors = behaviors - id
        works.restore(works.export()) { it != id }
        machines.restore(machines.export()) { it != id }
        thingState.remove(id)
        custom.remove(id)
        lampModes.remove(id)
        if (music?.itemId == id) music = null
        newThings.remove(id)
        ownedSince.remove(id)
        owned.remove(id)
        contentChanged()
        back.forEach { (m, n) -> add(m, n) }
        held.forEach { (m, n) -> if (content.item(m) != null) add(m, n) }
        return listOfNotNull(
            "took the ${spec.name} apart" + (spec.slot?.let { ", and $it is free now" } ?: ""),
            if (back.isEmpty()) "nothing in it could be used again" else "got back " + back.entries.joinToString(" and ") { (m, n) -> quantity(m, n) },
            held.takeIf { it.isNotEmpty() }?.let { h -> "took out what it held: " + h.entries.joinToString(" and ") { (m, n) -> quantity(m, n) } },
            if (hadCode) "its code has stopped" else null,
            gone.takeIf { it.isNotEmpty() }?.let { g -> "your verbs done with it are gone (${g.joinToString { it.verb.id }})" },
        )
    }

    /** [Spots.options] for the observation, kept while nothing he owns or the content changes. */
    private var putCache: Pair<Int, PutOptions?>? = null

    private fun putOptions(): PutOptions? {
        putCache?.let { (v, o) -> if (v == version) return o }
        return spots().options().also { putCache = version to it }
    }

    /**
     * Stands [id] at [spot], and what of his stood there where [id] was, which it returns. The caller
     * has checked [Spots.lock]. The move is kept with his customizations, so a restore puts it back.
     */
    private fun relocate(id: String, spot: String): ItemSpec? {
        val from = item(id).slot!!
        val other = content.items.firstOrNull { it.slot == spot }
        content = content.mapItems { when (it.id) { id -> it.copy(slot = spot); other?.id -> it.copy(slot = from); else -> it } }
        custom[id] = (custom[id] ?: BedroomState.Custom()).copy(slot = spot)
        other?.let { custom[it.id] = (custom[it.id] ?: BedroomState.Custom()).copy(slot = from) }
        contentChanged()
        return other
    }

    /** A thing he owns and can change with code (not a fixture of the house or the land, not a piece of art), or why not. */
    private fun ownThing(id: String): ItemSpec {
        val spec = content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.owns() lists yours")
        if (count(id) == 0) throw ScriptProblem("you don't own a ${spec.name} ('$id')")
        if (spec.group == "fixture") throw ScriptProblem("the ${spec.name} is part of the house or the land, not a thing of yours to change")
        return spec
    }

    private fun isWall(slot: String) = slot in content.room.wallSlots

    /**
     * The world as his code sees it: a doing of a verb he wrote, or a thing's hour. [reason] goes on
     * what it changes; [paid] scales need gains (a verb's wear); [minutes] is how long a thing it makes
     * took. [did] is what it changed, for the event.
     */
    private inner class ScriptView(
        private val reason: String,
        private val paid: (String, Double) -> Double = { _, d -> d },
        private val minutes: Int = 60,
        /** The thing whose code this is; null in a verb, where it's him. */
        private val self: String? = null,
    ) : ScriptWorld {
        val did = mutableListOf<String>()
        /** It raised his mood or stimulation, which wears. */
        var gained = false

        // What the world was before his code ran, for [undo]: everything the API below can change.
        private val wasContent = content
        private val wasInventions = inventions
        private val wasOwned = LinkedHashMap(owned)
        private val wasPieces = pieces.size
        private val wasSkills = LinkedHashMap(skills)
        private val wasState = thingState.mapValues { LinkedHashMap(it.value) }
        private val wasCustom = LinkedHashMap(custom)
        private val wasMoney = ledger.balance
        private val wasNeeds = Need.entries.associateWith { needs[it] }
        private val wasWorks = works.export()
        private val wasEmitted = surroundings.export()
        private val wasMarket = market.export(null, ticks, dayTicks)

        /**
         * Puts back everything his code changed, when it broke partway: a verb either does all of what
         * it says or none of it. Money and needs go back through the ledger and the needs, so the
         * record shows the refund. Returns what it had done, now undone ([did]).
         */
        fun undo(): List<String> {
            val why = "undone: $reason broke"
            (ledger.balance - wasMoney).let { if (it > 0) ledger.debit(ticks, it, why) else if (it < 0) ledger.credit(ticks, -it, why) }
            wasNeeds.forEach { (n, level) -> (level - needs[n]).takeIf { it != 0.0 }?.let { needs.change(ticks, n, it, why) } }
            owned.clear(); owned.putAll(wasOwned)
            while (pieces.size > wasPieces) pieces.removeAt(pieces.size - 1)
            syncHolding()
            skills.clear(); skills.putAll(wasSkills)
            thingState.clear(); thingState.putAll(wasState)
            custom.clear(); custom.putAll(wasCustom)
            works.restore(wasWorks) { true }
            surroundings.restore(wasEmitted) { true }
            market.load(wasMarket)
            if (content !== wasContent || inventions !== wasInventions) {
                content = wasContent
                inventions = wasInventions
                contentChanged()
            }
            version++
            return did.toList()
        }

        override fun time(): Map<String, Any?> {
            val m = clock.minuteOfDay(ticks)
            val r = clock.reading(ticks)
            return mapOf("day" to r.day, "time" to r.time, "hour" to m / 60, "minute" to m % 60, "night" to (content.night?.covers(m) == true))
        }

        override fun needs() = Need.entries.associate { it.id to (needs[it] * 10).roundToInt() / 10.0 }
        override fun money() = ledger.balance
        override fun skills() = content.vocabulary.skills.associateWith { (skill(it) * 100).roundToInt() / 100.0 }
        override fun owns(): Map<String, Int> = LinkedHashMap(owned)
        override fun catalog(): Map<String, String> = content.items.filter { isKnown(it.id) }.associate { it.id to it.name }
        override fun here(): String? = layout.placeOf(guy.x, guy.y)?.name

        override fun thing(id: String): Map<String, Any?>? {
            val s = content.item(id) ?: return null
            return mapOf(
                "id" to s.id, "name" to s.name, "group" to s.group, "uses" to s.affords.keys.toList(),
                "qualities" to s.qualities, "spot" to s.slot, "color" to custom[id]?.color, "count" to count(id),
                "state" to state(id),
            )
        }

        override fun spots(): Map<String, Any?> {
            val taken = content.items.mapNotNull { s -> s.slot?.let { it to s.id } }.toMap()
            val free = layout.slotCells.keys.filter { it !in taken }
            return mapOf(
                "free" to mapOf("floor" to free.filter { !isWall(it) }, "wall" to free.filter { isWall(it) }),
                "taken" to taken.filter { (_, id) -> count(id) > 0 && item(id).group != "fixture" },
            )
        }

        override fun state(id: String): Map<String, Any?> = thingState[id].orEmpty().mapValues { (_, v) ->
            when {
                v is kotlinx.serialization.json.JsonNull -> null
                v.isString -> v.content
                v.content == "true" || v.content == "false" -> v.content == "true"
                else -> v.content.toDoubleOrNull()
            }
        }

        override fun need(name: String, delta: Double): Double {
            val need = runCatching { needOf(name) }.getOrElse { throw ScriptProblem("'$name' isn't a need; they are ${Need.entries.joinToString { it.id }}") }
            // Fullness comes from eating and energy from rest: code can spend them, not make them.
            if (delta > 0 && need == Need.Fullness) throw ScriptProblem("fullness comes from eating something; code can't fill you up")
            if (delta > 0 && need == Need.Energy) throw ScriptProblem("energy comes from sleep; code can't give it")
            // Like any verb's, its gains in mood and stimulation shrink as it wears thin.
            val applied = needs.change(ticks, need, paid(name, delta), reason)
            if (applied > 0 && (need == Need.Mood || need == Need.Stimulation)) gained = true
            if (applied.roundToInt() != 0) did += "$name ${signed(applied.roundToInt().toDouble())}"
            return (needs[need] * 10).roundToInt() / 10.0
        }

        override fun spend(amount: Int, why: String): Boolean {
            if (amount < 1) throw ScriptProblem("spend a whole number of dollars above 0")
            if (!ledger.canAfford(amount)) return false
            ledger.debit(ticks, amount, why.ifBlank { reason }.take(80))
            did += "-$$amount" + (why.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            version++
            return true
        }

        override fun practice(skill: String, amount: Double): Double {
            if (self != null) throw ScriptProblem("skill comes from doing things yourself; a thing's code can't practise for you")
            if (skill !in content.vocabulary.skills) throw ScriptProblem("'$skill' isn't a skill; they are ${content.vocabulary.skills.joinToString()}")
            did += practise(skill, amount)
            return skill(skill)
        }

        override fun take(id: String, n: Int): Int {
            if (n < 1) throw ScriptProblem("take at least 1")
            val taken = this@BedroomWorld.take(id, n)
            taken.forEach { (t, k) -> did += "used ${quantity(t, k)}" }
            return taken.values.sum()
        }

        override fun make(design: Map<String, Any?>): String {
            val rules = content.invent ?: throw ScriptProblem("this world has no rules for making things")
            val missing = listOf("name", "use").filter { (design[it] as? String).isNullOrBlank() }
            if (missing.isNotEmpty()) throw ScriptProblem("the design needs ${missing.joinToString(" and ")} as text")
            // A look in words can't be built; the thing is made plain rather than not at all, and he's told what a look is.
            val look = design["look"]
            val worded = look != null && (look !is List<*> || look.any { it !is Map<*, *> })
            val given = if (worded) design - "look" else design
            val d = runCatching { DESIGN_JSON.decodeFromJsonElement(Design.serializer(), toJson(given)) }
                .getOrElse { throw ScriptProblem("the design didn't make sense: ${it.message?.lineSequence()?.firstOrNull()}") }
            val made = when (val checked = rules.check(d, content, minutes, ::inventSpot)) {
                is InventRules.Checked.Refused -> throw ScriptProblem("the ${d.name} can't be made: " + checked.problems.joinToString("; "))
                is InventRules.Checked.Ok -> checked.invention.copy(tick = ticks)
            }
            // It takes what it would at the workbench: materials from his stock, or dollars for food and books.
            if (made.bill.isNotEmpty()) {
                shortOf(made.bill, "the ${made.item.name}")?.let { throw ScriptProblem(it) }
                takeBill(made.bill)
                did += "used " + made.bill.entries.joinToString { (m, n) -> quantity(m, n) }
            } else {
                if (!ledger.canAfford(made.cost)) throw ScriptProblem("the ${made.item.name} costs $${made.cost}, and you have $${ledger.balance}")
                if (made.cost > 0) ledger.debit(ticks, made.cost, "materials for the ${made.item.name}")
            }
            content = content.plus(made)
            inventions = inventions + made
            contentChanged()
            add(made.item.id, 1)
            did += "made ${article(made.item.name)}" +
                if (worded) " (plain to look at: a look is a list of shapes like { shape: \"box\", w: 0.3, h: 0.2, d: 0.3, color: \"#8a5a3c\" }, not words)" else ""
            return made.item.id
        }

        override fun setState(id: String, key: String, value: Any?) {
            if (id != SELF) ownThing(id)
            if (key.isBlank() || key.length > 40) throw ScriptProblem("a key is 1 to 40 characters")
            val kept = thingState.getOrPut(id) { linkedMapOf() }
            val v = when (value) {
                null -> { kept.remove(key); if (kept.isEmpty()) thingState.remove(id); return }
                is String -> kotlinx.serialization.json.JsonPrimitive(value.take(MAX_TEXT))
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                is Double -> kotlinx.serialization.json.JsonPrimitive(if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else value)
                else -> throw ScriptProblem("a value is a string, a number, true or false, or null")
            }
            if (key !in kept && kept.size >= MAX_KEYS) throw ScriptProblem("a thing holds at most $MAX_KEYS keys")
            if (id !in thingState && thingState.size >= MAX_STATEFUL) throw ScriptProblem("at most $MAX_STATEFUL things can hold state")
            kept[key] = v
            version++
        }

        override fun move(id: String, spot: String): String {
            val s = ownThing(id)
            if (spot == s.slot) return spot
            this@BedroomWorld.spots().lock(id, spot)?.let { throw ScriptProblem(if (spot in layout.slotCells) it else "$it; world.spots() lists them") }
            if (s.blocks && guy.cell == layout.slotCells.getValue(spot)) throw ScriptProblem("you're standing on $spot")
            val from = s.slot
            did += "moved the ${s.name} to $spot" + (relocate(id, spot)?.let { ", and the ${it.name} to $from" } ?: "")
            return spot
        }

        override fun recolor(id: String, color: String) {
            val s = ownThing(id)
            val c = color.trim()
            if (c.isNotEmpty() && !c.matches(HEX)) throw ScriptProblem("a colour is hex, like #7aa3c9")
            custom[id] = (custom[id] ?: BedroomState.Custom()).copy(color = c.ifEmpty { null }?.lowercase())
            version++
            did += if (c.isEmpty()) "the ${s.name} has its own colours back" else "painted the ${s.name} $c"
        }

        override fun rename(id: String, name: String) {
            val s = ownThing(id)
            val n = name.trim().replace(Regex("\\s+"), " ")
            if (n.length !in 2..40 || !n.matches(NAME)) throw ScriptProblem("a name is 2 to 40 letters, digits, spaces, hyphens or apostrophes")
            if (content.items.any { it.id != id && it.name.equals(n, ignoreCase = true) }) throw ScriptProblem("there's already a thing called '$n'")
            content = content.mapItems { if (it.id == id) it.copy(name = n) else it }
            custom[id] = (custom[id] ?: BedroomState.Custom()).copy(name = n)
            contentChanged()
            did += "renamed the ${s.name} the $n"
        }

        override fun record(id: String, key: String, value: Double): Int {
            ownThing(id)
            val k = key.trim()
            if (!k.matches(LOG_KEY)) throw ScriptProblem("a key is 1 to 24 letters, digits or underscores, like \"moths\"")
            return try {
                records.record(id, k, ticks, value)
            } catch (e: IllegalArgumentException) {
                throw ScriptProblem(e.message ?: "can't log that")
            }
        }

        override fun readings(id: String, key: String): List<Map<String, Any?>> {
            ownThing(id)
            return records.readings(id, key).map { mapOf("time" to stamp(it.tick), "value" to it.value) }
        }

        override fun conditions(id: String): Map<String, Any?> {
            val s = ownThing(id)
            val slot = s.slot ?: throw ScriptProblem("the ${s.name} doesn't stand anywhere, so it has no weather of its own")
            val c = conditionsAt(layout.slotCells[slot] ?: throw ScriptProblem("the ${s.name} isn't standing anywhere now")) ?: throw ScriptProblem("there's no weather in this world")
            return mapOf(
                "sky" to c.sky.id, "temperature" to Weather.round1(c.temperature), "rain" to Weather.round1(c.rain),
                "pressure" to Weather.round1(c.pressure), "light" to Weather.round1(c.light), "outdoors" to c.outdoors,
            )
        }

        override fun place(id: String) = reach.place(id)
        override fun near(id: String, tiles: Int) = reach.near(id, tiles)
        override fun where() = reach.where()
        override fun patches(place: String?) = reach.patches(place)
        override fun things(place: String) = reach.things(place)
        override fun regrow(patch: String, hours: Double) = reach.regrow(patch, hours, self, did)
        override fun show(id: String, variant: String?) = machines.show(machineHost, id, variant, did)
        override fun motion(id: String, part: String, kind: String?, speed: Double) = machines.motion(machineHost, id, part, kind, speed)
        override fun readout(id: String, text: String?) = machines.readout(machineHost, id, text)
        override fun after(minutes: Int, key: String, id: String?) = machines.after(machineHost, minutes, key, id, self)

        override fun machine(id: String): Map<String, Any?> {
            val s = content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.owns() lists yours")
            if (count(id) == 0) throw ScriptProblem("you don't own a ${s.name}")
            if (!works.isMachine(worksHost, id)) throw ScriptProblem("the ${s.name} isn't a machine: it runs nothing and has no code")
            return works.view(worksHost, id)
        }

        override fun transfer(from: String, to: String, item: String, n: Int) = works.transfer(worksHost, from, to, item, n, self, did)

        override fun sell(id: String, n: Int): Int {
            val s = content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.owns() lists yours")
            if (s.carried) throw ScriptProblem("art is sold at the mail hatch (sell_art)")
            val price = s.sellPrice ?: throw ScriptProblem("nobody buys the ${s.name}")
            if (n < 1) throw ScriptProblem("sell at least 1")
            val k = minOf(n, count(id))
            if (k == 0) throw ScriptProblem("you have no ${s.name}")
            this@BedroomWorld.take(id, k)
            val got = sellPlain(id, k)
            ledger.credit(ticks, got, "$reason sold ${quantity(id, k)}")
            did += "sold ${quantity(id, k)} for $$got" + (lowNote(id)?.let { "; $it" } ?: "")
            return got
        }

        override fun price(id: String): Map<String, Any?>? {
            val s = content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.catalog() lists them")
            if (s.carried) throw ScriptProblem("art is priced as a piece and sold at the mail hatch (sell_art)")
            val usual = s.sellPrice ?: return null
            return mapOf("now" to going(id, usual), "usual" to usual)
        }

        override fun field(spot: String, kind: String): Double {
            val cell = when {
                spot == SELF -> guy.cell
                layout.slotCells[spot] != null -> layout.slotCells.getValue(spot)
                else -> ownThing(spot).let { s -> s.slot?.let { layout.slotCells[it] } ?: throw ScriptProblem("the ${s.name} doesn't stand anywhere") }
            }
            val f = surroundings.field(effectHost, kind) ?: throw ScriptProblem("'$kind' isn't an effect; use ${(EffectKind.entries.filter { it != EffectKind.Lure }.map { it.id } + content.world?.visitors.orEmpty().map { it.id }).joinToString()}")
            return Weather.round1(f[cell])
        }

        override fun emit(id: String, kind: String, strength: Double, radius: Double): Double {
            val s = ownThing(id)
            val k = EffectKind.of(kind)?.takeIf { it in Surroundings.EMITTABLE } ?: throw ScriptProblem("a thing can give off ${Surroundings.EMITTABLE.joinToString { it.id }}, not '$kind'")
            if (s.slot == null) throw ScriptProblem("the ${s.name} doesn't stand anywhere, so it can't give anything off")
            if (strength < 0 || strength > k.most) throw ScriptProblem("$kind strength is 0 (off) to ${k.most.toInt()}")
            if (radius < 0 || radius > Surroundings.MOST_RADIUS) throw ScriptProblem("radius is 0 to ${Surroundings.MOST_RADIUS.toInt()} tiles")
            if (surroundings.emit(s.id, k, strength, radius)) {
                version++
                did += if (strength == 0.0) "the ${s.name} stopped giving off $kind" else "the ${s.name} gives off $kind ${Weather.round1(strength)} over ${Weather.round1(radius)} tiles"
            }
            return Weather.round1(strength)
        }
    }

    /** The colours his code painted his things, item id to hex. */
    fun colors(): Map<String, String> = custom.mapNotNull { (id, c) -> c.color?.let { id to it } }.toMap()

    // --- skills and books ------------------------------------------------------------------------

    fun skill(name: String): Double = skills[name] ?: 0.0

    /** The content as he knows it: without what books teach that he hasn't learned yet. Safe from any thread. */
    fun knownContent(): BedroomContent {
        val k = known
        knownCache?.let { if (it.first == k) return it.second }
        return content.known(k).also { knownCache = k to it }
    }

    private fun isKnown(id: String): Boolean = id !in content.taught || id in known

    /** How much more [a]'s gains pay for the skills it practises (its `practice` ops and the skills of the book it reads). */
    private fun skilled(a: VerbSpec, args: Map<String, String>): Double {
        val per = content.skills.gainPerLevel
        if (per == 0.0) return 1.0
        val used = a.ops<PracticeOp>().flatMap { it.gains.keys } +
            a.ops<ProgressOp>().flatMap { p -> args[p.book]?.let { content.book(it)?.skill?.keys }.orEmpty() }
        if (used.isEmpty()) return 1.0
        return 1.0 + per * used.distinct().map(::skill).average()
    }

    private fun bound(a: VerbSpec, args: Map<String, String>) = Bound(a, args, freshness(a, args), skilled = skilled(a, args))

    private fun practise(name: String, d: Double): String {
        skills[name] = skill(name) + d
        version++
        return "$name skill ${oneDecimal(skill(name))} (${signed(d)})"
    }

    /** What one [spec] sells for, made at [level] in the skill its price grows with. */
    private fun price(spec: ItemSpec, level: Double): Int? = spec.priceAt(level)

    private fun piecePrice(p: Piece): Int = price(item(p.item), p.skill) ?: 0

    private fun readChapter(id: String): List<String> {
        val book = content.book(id) ?: return emptyList()
        val read = chapters[id] ?: 0
        if (read >= book.chapters.size) return listOf("reread \"${book.title}\"")
        chapters[id] = read + 1
        version++
        val parts = mutableListOf("chapter ${read + 1} of ${book.chapters.size} of \"${book.title}\": ${book.chapters[read]}")
        book.skill.forEach { (s, d) -> parts += practise(s, d) }
        if (read + 1 == book.chapters.size) {
            parts += "finished \"${book.title}\""
            val learned = book.teaches.filter { it !in known }
            if (learned.isNotEmpty()) {
                known = known + learned
                parts += "you learned ${taughtText(learned)}"
            }
        }
        return parts
    }

    /** "to paint, and about the easel and the canvas": [ids] as what he'd know. */
    private fun taughtText(ids: List<String>): String {
        val verbs = ids.mapNotNull(content::verb).map { "to " + (it.phrase ?: it.id).replace('_', ' ') }
        val things = ids.mapNotNull(content::item).map { "the ${it.name}" }
        val about = if (things.isEmpty()) null else "about " + things.joinToString(" and ")
        return (verbs + listOfNotNull(about)).joinToString(", and ")
    }

    private fun bookViews(): List<BookView> = content.books.filter { count(it.id) > 0 }.map { b ->
        val read = chapters[b.id] ?: 0
        BookView(
            b.id, b.title, read, b.chapters.size,
            raises = b.skill.keys.toList().ifEmpty { null },
            teaches = b.teaches.filter { it !in known }.takeIf { it.isNotEmpty() && read < b.chapters.size }?.let(::taughtText),
            next = b.chapters.getOrNull(read),
        )
    }

    private fun skillView(): Map<String, Double> = content.vocabulary.skills.associateWith { (skill(it) * 10).roundToInt() / 10.0 }

    override fun failed(intent: Intent, reason: String) {
        content.frustration?.let { needs.change(ticks, Need.Frustration, it.perFailure, "failed: ${intent::class.simpleName?.lowercase()}") }
    }

    // --- habituation and novelty -----------------------------------------------------------------

    /** What counts as new: the verb and the items bound to its arguments, "play_record:jazz_record:record_player". */
    private fun habit(a: VerbSpec, args: Map<String, String>): String = (listOf(a.id) + args.values).joinToString(":")

    /** What wears: the [habit], or the verb alone for one that wears as a whole (reading any book wears "read"). */
    private fun wearKey(a: VerbSpec, args: Map<String, String>): String = if (a.wear == Wear.Verb) a.id else habit(a, args)

    /**
     * 1 when fresh, down to 0 when worn out; always 1 for routine verbs, or without habituation or
     * familiarity. With [familiar], how unfamiliar what it would be like is ([features]); else its wear by id.
     */
    private fun freshness(a: VerbSpec, args: Map<String, String> = emptyMap()): Double {
        if (a.isRoutine || fixing(a)) return 1.0
        familiar?.let { return if (upkeep(a)) 1.0 else it.freshness(features(a, args), ticks) }
        val h = content.habituation ?: return 1.0
        val (level, since) = wear[wearKey(a, args)] ?: return 1.0
        val hours = (ticks - since).toDouble() / clock.ticks(60)
        return 1.0 - (level - h.recoverPerHour * hours).coerceIn(0.0, 1.0)
    }

    /**
     * What doing [a] with [args] is like, as the sim knows it ([Feature]): the verb and its kind; the place it's
     * done (where the first thing it's done at or with stands, else where he is) and the sky there when it's
     * outdoors; each thing it's done at or with, needs or uses, with its group and qualities; the skills it
     * practises; what it makes. Never anything he wrote, so a new name for the same thing isn't new.
     */
    private fun features(a: VerbSpec, args: Map<String, String>): List<Feature> {
        val ids = (args.values + a.at + a.requires).filter { it != VerbSpec.HERE && it !in a.args }.distinct()
        val things = ids.mapNotNull { content.item(it) }
        val stands = placed()
        val cell = things.firstNotNullOfOrNull { s -> stands.firstOrNull { it.first.id == s.id }?.second } ?: guy.cell
        val place = layout.placeOf(cell)
        return buildList {
            add(Feature("verb", a.id))
            add(Feature("kind", a.kind.id))
            place?.let { p ->
                add(Feature("place", p.id))
                if (p.kind == PlaceKind.Outdoor) conditionsAt(cell)?.let { add(Feature("sky", it.sky.id)) }
            }
            things.forEach { s ->
                add(Feature("thing", s.id))
                add(Feature("group", s.group))
                s.qualities.keys.forEach { add(Feature("quality", it)) }
            }
            a.ops<PracticeOp>().forEach { op -> op.gains.keys.forEach { add(Feature("skill", it)) } }
            a.ops<ProgressOp>().forEach { op -> args[op.book]?.let { id -> content.books.firstOrNull { it.id == id } }?.skill?.keys?.forEach { add(Feature("skill", it)) } }
            a.ops<ProduceOp>().forEach { op -> op.items.keys.forEach { add(Feature("makes", it)) } }
        }
    }

    /** Whether [a] writes code while some of his code is broken: fixing it is work, not a habit, so it doesn't wear. */
    private fun fixing(a: VerbSpec): Boolean =
        a.effects.any { it is DefineOp } && (brokenVerbs.isNotEmpty() || behaviors.values.any { it.failures > 0 })

    private fun isNew(a: VerbSpec, args: Map<String, String> = emptyMap()): Boolean = content.novelty != null && !a.isRoutine && habit(a, args) !in tried

    /** The first-time bonus, scaled by his curiosity. */
    private fun novelty(): Map<String, Double> = content.novelty!!.effects.mapValues { it.value * content.personality.curiosity }

    /** Whether [a] is something he can be bored of ([BoredLock]): it wears thin, has mood or stimulation gains to wear, and doesn't feed energy or fullness. */
    private fun boring(a: VerbSpec): Boolean {
        if (a.isRoutine) return false
        return (gains(a, Need.Mood) || gains(a, Need.Stimulation)) && !gains(a, Need.Energy) && !gains(a, Need.Fullness)
    }

    /** Whether [a] raises [n]: at the finish, an hour while it lasts, or by what it plays. */
    private fun gains(a: VerbSpec, n: Need): Boolean =
        (a.needEffects.entries + a.ops<RateOp>().flatMap { it.perHour.entries } + a.ops<BackgroundOp>().flatMap { it.rates.entries }).any { it.key == n.id && it.value > 0 }

    /** Looking after his body, which isn't an experience ([familiar] leaves it out): routine, or it feeds energy or fullness (the oats). */
    private fun upkeep(a: VerbSpec): Boolean = a.isRoutine || gains(a, Need.Energy) || gains(a, Need.Fullness)

    /** [a]'s freshness for the bored lock ([repetition]) when he's bored and it's worn past [BoredLock.worn], or null. Unbound (null [args]), a verb that wears per item leaves it to each binding. */
    private fun boredOf(a: VerbSpec, args: Map<String, String>?): Double? {
        val lock = content.habituation?.boredLock ?: return null
        if (needs[Need.Stimulation] >= lock.below || !boring(a)) return null
        if (args == null && a.args.isNotEmpty() && a.wear != Wear.Verb) return null
        // What he's never done is new, even when it wears with something he's tired of (a new book, read worn thin).
        if (isNew(a, args.orEmpty())) return null
        return repetition(a, args.orEmpty()).takeIf { 1 - it >= lock.worn }
    }

    /**
     * How fresh [a] is for the bored lock: with [familiar], by how much he has done this lately, its own
     * `verb:` feature (or, for a verb that wears per item, the `thing:` features of what's bound), not by
     * what it's like. What it's like shares the place, groups and kind with everything else he does at
     * home, which never fade, so judged by that, anything at home he had done once stayed locked
     * (docs/decisions/2026-11-17-the-bored-lock-reads-what-he-has-done-lately.md). Pay still reads [freshness].
     */
    private fun repetition(a: VerbSpec, args: Map<String, String>): Double {
        val f = familiar ?: return freshness(a, args)
        if (a.isRoutine || fixing(a) || upkeep(a)) return 1.0
        val keys = if (a.wear == Wear.Verb || args.isEmpty()) listOf("verb:${a.id}") else args.values.distinct().map { "thing:$it" }
        return keys.map { 1.0 / (1.0 + f.level(it, ticks)) }.average()
    }

    /**
     * Why boredom refuses [a] now, or null. Work stays open while he can't afford anything new, since
     * buying something new is the way out of boredom and work is the way to the money.
     */
    private fun boredLock(a: VerbSpec, args: Map<String, String>?): String? {
        val fresh = boredOf(a, args) ?: return null
        if (a.work && !canAffordNew()) return null
        val gains = if (familiar != null) "" else "gains at ${percent(fresh)}, "
        return "you're bored, and you've done it a lot lately (${gains}stimulation ${needs.level(Need.Stimulation)})"
    }

    /**
     * Whether the shop has something new to him that he can afford and still keep the bored lock's `keep` for food:
     * a thing for the room he likes, or something for a verb he hasn't done.
     */
    private fun canAffordNew(): Boolean {
        val keep = content.habituation?.boredLock?.keep ?: 0
        return shopItems().any { spec ->
            heldBack(spec) == null && ledger.canAfford(spec.price!! + keep) && (newThingRate(spec) > 0 || usesOf(spec).any { isNew(it) })
        }
    }

    /** What [spec], bought at [since], feeds stimulation an hour now ([NewThings]): 0 out of sight, once it's old, or if he doesn't like it. */
    private fun newThingRate(spec: ItemSpec, since: Long = ticks): Double {
        val t = content.novelty?.things ?: return 0.0
        if (spec.slot == null) return 0.0
        val left = 1 - (ticks - since).toDouble() / clock.ticks(60) / 24 / t.days
        return if (left <= 0) 0.0 else value(spec).coerceAtLeast(0.0) * t.stimulationPerValue * content.personality.curiosity * left
    }

    /** "stimulation +2/h while you're awake and can see it, fading over 3 days". */
    private fun newThingText(rate: Double): String =
        "stimulation ${signed((rate * 10).roundToInt() / 10.0)}/h while you're awake${if (content.comfort != null) " and can see it" else ""}, fading over ${signed(content.novelty!!.things!!.days).removePrefix("+")} days"

    /** [d] as paid at [fresh]: gains in mood and stimulation shrink with wear; costs and other needs don't. */
    private fun paid(need: Need, d: Double, fresh: Double): Double = if (d > 0 && (need == Need.Mood || need == Need.Stimulation)) d * fresh else d

    /**
     * What finishing [a] at [fresh] does to his fun with [familiar] ([familiarFun]): the novelty bonus at
     * [fresh], and the stale cost at 1 - [fresh]. Need id to change, unscaled by anything else.
     */
    private fun familiarFun(fresh: Double): Map<String, Double> {
        val bonus = content.novelty?.let { novelty() }.orEmpty().mapValues { it.value * fresh }
        val stale = content.habituation?.staleStimulation?.takeIf { it < 0 }?.let { it * (1 - fresh) } ?: 0.0
        return bonus + (Need.Stimulation.id to (bonus[Need.Stimulation.id] ?: 0.0) + stale)
    }

    /** "familiar (gains at 20%): stimulation -3, mood +1" or "fresh to you (gains at 90%): ...". */
    private fun familiarText(fresh: Double, changes: Map<String, Double>): String =
        "${if (isFresh(fresh)) "fresh to you" else "familiar"} (gains at ${percent(fresh)})" +
            changes.entries.filter { it.value.roundToInt() != 0 }.joinToString("") { (need, d) -> ", $need ${signed(d.roundToInt().toDouble())}" }

    /**
     * Wears the habit, charges stimulation if it was already worn, and pays the novelty bonus the first time.
     * With [familiar] instead: pays [familiarFun] every time and makes what it was like more familiar.
     */
    private fun habituate(a: VerbSpec, args: Map<String, String>, fresh: Double, parts: MutableList<String>, wears: Boolean = true) {
        val key = habit(a, args)
        familiar?.let { f ->
            if (upkeep(a) || !wears) return
            val paid = familiarFun(fresh).mapValues { (need, d) -> needs.change(ticks, needOf(need), d, if (isFresh(fresh)) "fresh: ${a.id}" else "familiar: ${a.id}") }
            parts += familiarText(fresh, paid)
            f.meet(features(a, args), ticks)
            tried += key
            version++
            return
        }
        content.habituation?.takeIf { !a.isRoutine && wears }?.let { h ->
            val worn = 1.0 - fresh
            if (worn > 0) {
                val stale = if (h.staleStimulation < 0) ", stimulation ${signed(needs.change(ticks, Need.Stimulation, h.staleStimulation * worn, "${a.id} again"))}" else ""
                parts += "done a lot lately, so it paid ${percent(fresh)}$stale"
            }
            wear[wearKey(a, args)] = (worn + h.perRepeat).coerceAtMost(1.0) to ticks
            version++
        }
        if (isNew(a, args)) {
            tried += key
            version++
            parts += "new to you: " + novelty().entries.joinToString { (need, d) -> "$need ${signed(needs.change(ticks, needOf(need), d, "new: $key"))}" }
        }
    }

    override fun passable(x: Int, y: Int): Boolean = map.isFloor(x, y) && placed().none { (spec, cell) -> spec.blocks && cell.x == x && cell.y == y }

    // --- rules: what's possible ------------------------------------------------------------------

    /**
     * Why [a] can't be done now with [args] bound, or null when it can. Unbound (null), each
     * argument only needs some owned match. [extra]: as if he also owned that item.
     */
    fun lockReason(a: VerbSpec, args: Map<String, String>? = null, extra: String? = null): String? {
        val places = a.at.filter { it !in a.args && it != VerbSpec.HERE }
        if (!isKnown(a.id)) return "you don't know how yet"
        if (places.isNotEmpty() && a.at.none { it == VerbSpec.HERE || it in a.args } && places.none { countTag(it, extra) > 0 }) return "needs ${wanted(places.first())}"
        a.requires.firstOrNull { countTag(it, extra) == 0 }?.let { return "needs ${wanted(it)}" }
        // The thing it happens at first (the record player before a record), since the shop holds the rest back until he has it.
        if (args == null) a.args.entries.sortedBy { it.key !in a.at }.firstOrNull { countTag(it.value, extra) == 0 }?.let { return "needs ${wanted(it.value)}" }
        if (a.noRepeat && (justDid as? Intent.Do)?.activity == a.id) return "you just did; do something else first"
        boredLock(a, args)?.let { return it }
        val b = Bound(a, args.orEmpty(), fresh = 1.0, extra = extra)
        a.effects.firstNotNullOfOrNull { it.lockReason(ops, b) }?.let { return it }
        val blocks = a.block + byDay(a)?.block.orEmpty() + if (a.work) content.needs.workFloor else emptyList()
        blocks.firstOrNull { it.blocks(needs[needOf(it.need)]) }?.let { return "${it.reason} (${it.need} ${needs.level(needOf(it.need))})" }
        darkLock(a, args)?.let { return it }
        coldLock(a, args)?.let { return it }
        // Started now, it would end as soon as it began: sleep while starving wakes him in a minute.
        a.stopBelow.entries.firstOrNull { (need, level) -> needs[needOf(need)] < level }?.let { (need, level) ->
            return "it ends below $need ${level.toInt()} ($need ${needs.level(needOf(need))})"
        }
        return null
    }

    /** What his food is: what the verbs that feed him use up ("edible"). */
    private val foodTags: List<String>
        get() = content.verbs.filter { (it.needEffects[Need.Fullness.id] ?: 0.0) > 0 }.flatMap { v -> v.ops<ConsumeOp>().flatMap { op -> op.items.keys.map { v.args[it] ?: it } } }.distinct()

    /** [a]'s daytime changes, when it has some and it isn't night now. */
    private fun byDay(a: VerbSpec): VerbSpec.ByDay? = a.byDay?.takeIf { content.night?.covers(clock.minuteOfDay(ticks)) == false }

    /** "an easel ($120)", "a meal (3 for $18)", "a sketchbook". The cheapest item with [tag] for sale, if any; named as itself when [cheapest], not by its group. */
    private fun wanted(tag: String, cheapest: Boolean = false): String {
        val options = content.withName(tag)
        // Nothing in the content answers to it yet: a thing he invents, before he has built one.
        if (options.isEmpty()) return if (tag == INVENTION) "a thing you invented" else article(tag.replace('_', ' '))
        val spec = options.filter { it.price != null }.minByOrNull { it.price!! } ?: options.first()
        val price = spec.price ?: return article(if (options.size > 1) noun(tag) else spec.name)
        val name = if (options.size > 1 && tag != spec.id && !cheapest) noun(tag) else spec.name
        return article(name) + if (spec.pack > 1) " (${spec.pack} for $$price)" else " ($$price)"
    }

    private fun article(name: String) = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name

    // --- intents ---------------------------------------------------------------------------------

    /** The intent planned before the one being planned now. */
    private var lastPlanned: Intent? = null
    private var planning = false

    /** What he did last, for `no_repeat`. While planning, [last] is already the intent being planned, so it's the one before. */
    private val justDid: Intent? get() = if (planning) lastPlanned else last?.intent

    override fun plan(intent: Intent): Work {
        planning = true
        try {
            return planIntent(intent)
        } finally {
            planning = false
            lastPlanned = intent
        }
    }

    private fun planIntent(intent: Intent): Work = when (intent) {
        is Intent.MoveTo -> when (val t = intent.target) {
            is Target.Place -> goTo(t)
            is Target.Entity -> approach(t)
            is Target.Tile -> walkTo(tile(t), describe(t))
        }
        is Intent.PickUp -> reject("there's nothing to pick up here: what you own is already yours, and do and buy fetch it for you")
        is Intent.Use -> reject("use is for other rooms; here an item is used through a verb, do(verb, what, on)" + (content.verbs.firstOrNull { "on" in it.args && "what" in it.args }?.let { "; ${it.id} puts a ${noun(it.args.getValue("what"))} on the ${noun(it.args.getValue("on"))}" } ?: ""))
        is Intent.Say -> say(intent)
        is Intent.Wait -> waitFor(intent)
        is Intent.Do -> planDo(intent)
        is Intent.Buy -> planBuy(intent.item)
        is Intent.Put -> planPut(intent)
        Intent.Think -> reject("think is for other rooms; here it's an activity, do(think)")
    }

    private fun tile(target: Target.Tile): Cell {
        val cell = Cell(target.x, target.y)
        if (!map.inBounds(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is off the map")
        if (map[cell.x, cell.y] == Terrain.Void) reject("(${cell.x}, ${cell.y}) is off the land")
        if (!passable(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is a wall, water, a tree or furniture")
        return cell
    }

    /**
     * A walk to stand beside one of the things he knows of, anywhere on the land, which says where he
     * went and how long it took. Already there, he stays put and is told so.
     */
    private fun approach(target: Target.Entity): Work {
        if (target.id == guy.id.value) reject("you can't walk to yourself")
        val (spec, cell) = placed().firstOrNull { it.first.id == target.id && isKnown(it.first.id) }
            ?: reject("there's nothing called '${target.id}' that you know of")
        if (spec.id == carrying) reject("you're carrying the ${spec.name}")
        val walk = walkTo(cell, "the ${spec.name}")
        if (walk.path.isEmpty()) return Work.Until(start = {}, done = { true }, finish = { outcome("you're already by the ${spec.name}"); null })
        val there = layout.placeOf(cell)
        val to = "the ${spec.name}" + (if (layout.outdoors && there != null && there != layout.placeOf(guy.cell)) " in ${there.name}" else "")
        val minutes = walkMinutes(walk.path.size.toFloat())
        return Work.Steps(walk, Work.Until(start = {}, done = { true }, finish = { outcome("walked over to $to (${GameClock.duration(minutes.coerceAtLeast(1))} walk)"); null }))
    }

    /** A walk to the middle of a place, which says where he went and how long it took. */
    private fun goTo(target: Target.Place): Work {
        val place = layout.place(target.id) ?: reject("there's no place called '${target.id}'; the places are ${layout.places.joinToString { it.id }}")
        if (layout.placeOf(guy.cell) == place) reject("you're already in ${place.name}")
        val spot = layout.spot(place) ?: reject("there's nowhere to stand in ${place.name}")
        val walk = walkTo(spot, place.name)
        val minutes = walkMinutes(walk.path.size.toFloat())
        return Work.Steps(walk, Work.Until(start = {}, done = { true }, finish = { outcome("went to ${place.name} (${GameClock.duration(minutes.coerceAtLeast(1))} walk)"); null }))
    }

    /** What he's carrying to put down somewhere ([planPut]): hidden from the map until it's set down. */
    private var carrying: String? = null

    /**
     * Puts a thing at a spot ([Intent.Put]): he walks beside it, lifts it, carries it beside the spot
     * and sets it down there, in the game minutes `world.carry` says besides the walks. The rules are
     * [Spots]', checked again as he sets it down in case something changed on the way.
     */
    private fun planPut(intent: Intent.Put): Work {
        spots().lock(intent.item, intent.spot)?.let { reject("can't put ${intent.item} at ${intent.spot}: $it") }
        val spec = item(intent.item)
        val from = layout.slotCells.getValue(spec.slot!!)
        val to = layout.slotCells.getValue(intent.spot)
        val fetch = pathToward(from) ?: reject("there's no way to reach the ${spec.name}")
        val lifted = fetch.cells.lastOrNull() ?: guy.cell
        // Beside the spot, round what blocks; the thing he carries no longer stands in the way.
        val clear = { x: Int, y: Int -> (x == from.x && y == from.y && map.isFloor(x, y)) || passable(x, y) }
        val carry = NEXT_TO.map { (dx, dy) -> Cell(to.x + dx, to.y + dy) }
            .filter { clear(it.x, it.y) }
            .mapNotNull { Pathfinder.find(map.width, map.height, lifted, it, clear) }
            .minByOrNull { it.cost } ?: reject("there's no way to carry the ${spec.name} to ${intent.spot}")
        val minutes = content.world?.carry ?: CarrySpec()
        val place = layout.placeOf(to)?.name
        val walked = walkMinutes(fetch.cost + carry.cost)
        fun step(minutes: Int, face: Cell, then: () -> String?): Work {
            var until = 0L
            return Work.Until(
                start = {
                    if (face != guy.cell) guy.facing = Direction.of(face.x + 0.5f - guy.x, face.y + 0.5f - guy.y)
                    until = ticks + clock.ticks(minutes)
                },
                done = { ticks >= until },
                finish = then,
            )
        }
        return Work.Steps(
            Work.Walk(ArrayDeque(fetch.cells)),
            step(minutes.lift, from) { carrying = spec.id; version++; null },
            Work.Walk(ArrayDeque(carry.cells)),
            step(minutes.setDown, to) {
                carrying = null
                version++
                spots().lock(intent.item, intent.spot)?.let { return@step "couldn't put the ${spec.name} down at ${intent.spot}: $it" }
                val swapped = relocate(intent.item, intent.spot)
                outcome("put the ${spec.name} at ${intent.spot}" + (place?.let { ", in $it" }.orEmpty()) + (swapped?.let { ", and the ${it.name} where it was" }.orEmpty()) +
                    " (${GameClock.duration((walked + minutes.lift + minutes.setDown).coerceAtLeast(1))} with the walks)")
                null
            },
        )
    }

    /** Game minutes to walk [tiles]. */
    fun walkMinutes(tiles: Float): Int = ceil(tiles / (SPEED * clock.ticksPerMinute)).toInt()

    /** Game minutes from where he stands to [cell], round walls, water and trees; null when there's no way. */
    fun walkFromHere(cell: Cell): Int? = layout.distance(guy.cell, cell)?.let(::walkMinutes)

    private var placesCache: Pair<Cell, List<PlaceOption>>? = null

    /** Every place but the one he's in, nearest first, with the walk there; kept while he stands on one tile. */
    private fun placeOptions(): List<PlaceOption>? {
        if (!layout.outdoors) return null
        placesCache?.let { if (it.first == guy.cell) return it.second }
        val here = layout.placeOf(guy.cell)
        val list = layout.places.filter { it != here }.mapNotNull { p ->
            val spot = layout.spot(p) ?: return@mapNotNull null
            walkFromHere(spot)?.let { PlaceOption(p.id, p.name, p.says, it) }
        }.sortedBy { it.walk }
        placesCache = guy.cell to list
        return list
    }

    /** "your bedroom, by the desk": the place he's in, and the nearest thing to him in it. */
    private fun whereText(): String? {
        val place = layout.placeOf(guy.cell) ?: return null
        val near = placed().filter { (spec, cell) -> layout.placeOf(cell) == place && chebyshev(cell, guy.cell) <= 2 && spec.group != "fixture" }
            .minByOrNull { (_, cell) -> chebyshev(cell, guy.cell) }
        return place.name + (near?.let { ", by the ${it.first.name}" }.orEmpty())
    }

    /**
     * A walk to the nearest of [ids] that he owns, and the tile of the one he walks to; the caller
     * has checked there is one. He goes to the side the room's `approach` names when it's free.
     */
    private fun walkToNearest(ids: List<String>): Pair<Work.Walk, Cell> {
        val paths = placed().filter { it.first.id in ids }.mapNotNull { (spec, cell) ->
            val side = content.room.approach[spec.slot]?.let { d ->
                val rad = Math.toRadians(d.degrees.toDouble())
                Cell(cell.x + cos(rad).roundToInt(), cell.y - sin(rad).roundToInt())
            }
            val path = side?.takeIf { passable(it.x, it.y) }?.let { Pathfinder.find(map.width, map.height, guy.cell, it, ::passable) } ?: pathToward(cell)
            path?.let { it to cell }
        }
        val (best, cell) = paths.minByOrNull { it.first.cost } ?: reject("there is no way to reach the ${ids.joinToString(" or ")}")
        return Work.Walk(ArrayDeque(best.cells)) to cell
    }

    private fun planDo(intent: Intent.Do): Work {
        val a = content.verb(intent.activity)?.takeIf { isKnown(it.id) } ?: reject("there's no activity called '${intent.activity}'; the activities are ${content.verbs.filter { isKnown(it.id) }.joinToString { it.id }}")
        val given = mapOf("what" to intent.what, "on" to intent.on).filterValues { it != null }.mapValues { it.value!! }
        (given.keys - a.args.keys).firstOrNull()?.let { reject("${a.id} takes no `$it`" + if (a.args.isEmpty()) "" else "; it takes ${a.args.keys.joinToString(" and ")}") }
        lockReason(a)?.let { reject("can't ${a.id}: $it") }
        val args = a.args.mapValues { (slot, type) ->
            val id = given[slot] ?: return@mapValues owned.keys.first { answers(it, type) }
            val spec = content.item(id)
            if (spec == null || count(id) == 0) reject("you don't have '$id'")
            if (!answers(spec.id, type)) reject("${a.id} takes ${article(noun(type))} as `$slot`, and the ${spec.name} isn't one")
            id
        }
        lockReason(a, args)?.let { reject("can't ${a.id}: $it") }
        // A thing bound to an argument that stands nowhere (a record he holds) is done with where he is.
        val places = a.at.mapNotNull { at -> if (at == VerbSpec.HERE || (at in args && item(args.getValue(at)).slot == null)) null else args[at] ?: at }
        if (VerbSpec.HERE in a.at || places.isEmpty()) return activity(a, guy.cell, args) { complete(a, args) }
        val (walk, at) = walkToNearest(litEnough(a, places))
        return Work.Steps(walk, activity(a, at, args) { complete(a, args) })
    }

    /** Every way to bind [a]'s arguments to items he owns, in the order he owns them; one empty binding when it has none. */
    private fun bindings(a: VerbSpec): List<Map<String, String>> =
        a.args.entries.fold(listOf(emptyMap())) { acc, (slot, type) ->
            acc.flatMap { b -> owned.keys.filter { answers(it, type) && isKnown(it) }.map { b + (slot to it) } }
        }

    private fun planBuy(id: String): Work {
        val spec = content.item(id)?.takeIf { isKnown(it.id) }
        val price = spec?.price ?: reject("'$id' isn't for sale; the shop has ${shopItems().joinToString { it.id }}")
        refusal(spec)?.let(::reject)
        return Work.Steps(walkToNearest(listOf(content.shop.at)).first, Work.Timed(clock.ticks(content.shop.minutes)) { purchase(spec, price) })
    }

    /**
     * The timed part of a verb: sets [doing] when it starts and ends by duration or `until`.
     * [at] is the tile of what he does it at; he turns to face it unless he's on it.
     */
    private fun activity(a: VerbSpec, at: Cell, args: Map<String, String>, finish: () -> String?): Work.Until {
        val until = a.until
        val untilNeed = until?.need?.let(::needOf)
        val stops = a.stopBelow.mapKeys { needOf(it.key) }
        return Work.Until(
            start = {
                if (at != guy.cell) guy.facing = Direction.of(at.x + 0.5f - guy.x, at.y + 0.5f - guy.y)
                val full = if (until != null) clock.ticksUntil(ticks, GameClock.parseHhmm(until.time)) else clock.ticks(a.minutes!!).toLong()
                val length = byDay(a)?.minutes?.let { min(full, clock.ticks(it).toLong()) } ?: full
                doing = Doing(bound(a, args), ticks, ticks + length)
                if (a.effects.any { it is SleepOp }) machines.queue(Trigger.Sleep)
            },
            done = {
                val d = doing!!
                ticks >= d.endTick || (untilNeed != null && needs[untilNeed] >= until.reaches!!) ||
                    stops.any { (need, level) -> needs[need] < level }
            },
            finish = finish,
        )
    }

    override fun workEnded(handle: Handle) {
        if (doing?.activity?.effects?.any { it is SleepOp } == true) machines.queue(Trigger.Wake)
        doing = null
        if (carrying != null) {
            carrying = null
            version++
        }
    }

    private fun complete(a: VerbSpec, args: Map<String, String>): String? {
        val b = bound(a, args)
        a.effects.firstNotNullOfOrNull { it.gone(ops, b) }?.let { return it }
        val fresh = b.fresh
        val fix = fixing(a)
        val parts = a.effects.flatMapTo(mutableListOf()) { it.finish(ops, b) }
        a.stopBelow.forEach { (need, level) -> if (needs[needOf(need)] < level) parts += "stopped early: $need ${needs.level(needOf(need))}" }
        habituate(a, args, fresh, parts, wears = !fix)
        content.frustration?.takeIf { !a.isRoutine }?.let { f ->
            val eased = needs.change(ticks, Need.Frustration, f.perSuccess, "did ${a.id}")
            if (eased != 0.0) parts += "frustration ${signed(eased.roundToInt().toDouble())}"
        }
        outcome("finished ${a.id}" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", "))
        done = done + (a.id to (done[a.id]?.let { it.copy(count = it.count + 1, lastTick = ticks) } ?: VerbTally(1, ticks)))
        if (!a.isRoutine) recent.addLast(a.id to ticks)
        machines.queue(Trigger.Finish(a.id))
        return null
    }

    private fun sell(tag: String): String {
        val sale = saleOf(held(tag))
        val sold = take(tag, countTag(tag))
        sale.forEach { (p, _) -> p.state = PieceState.Sold }
        val total = sale.sumOf { it.second } + sold.entries.sumOf { (id, n) -> sellPlain(id, n) }
        val what = sold.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }
        val low = sold.keys.mapNotNull(::lowNote)
        val winner = winner(sale.map { it.first })
        val bonus = winner?.let {
            commission!!.fulfilled = true
            version++
            " (${pieceName(it)} met the request for ${commission!!.wants}: +$${content.commissions!!.reward})"
        }.orEmpty()
        if (total > 0) ledger.credit(ticks, total, "sold $what" + if (winner != null) " (one for a commission)" else "")
        return "sold $what for $$total$bonus (money $${ledger.balance})" + low.joinToString("") { "; $it" }
    }

    /** What one [id] sells for when it's a plain thing he owns and not a piece of art, which is priced as a piece; 0 otherwise. A ware's is its going price. */
    private fun plainPrice(id: String): Int = item(id).takeIf { !it.carried }?.sellPrice?.let { going(id, it) } ?: 0

    /** Game ticks in a day, for how fast the market recovers. */
    private val dayTicks: Long get() = clock.ticks(GameClock.MINUTES_PER_DAY).toLong()

    /** What one [id] fetches now from its usual [price]: lower for a ware he has sold a lot of lately. */
    private fun going(id: String, price: Int): Int {
        val rules = content.market ?: return price
        return if (market.covers(item(id))) market.price(rules, id, price, ticks, dayTicks) else price
    }

    /** What [n] of a plain thing would fetch together now, each sold for less than the last when it's a ware. */
    private fun quote(id: String, n: Int): Int {
        val spec = item(id)
        val price = spec.takeIf { !it.carried }?.sellPrice ?: return 0
        val rules = content.market
        return if (rules == null || !market.covers(spec)) price * n else market.quote(rules, id, price, n, ticks, dayTicks)
    }

    /** Sells [n] of a plain thing: a ware one after another at its falling going price, anything else at its price; the dollars. */
    private fun sellPlain(id: String, n: Int): Int {
        val spec = item(id)
        val price = spec.takeIf { !it.carried }?.sellPrice ?: return 0
        val rules = content.market
        if (rules == null || !market.covers(spec)) return price * n
        version++
        return market.sell(rules, id, price, n, ticks, dayTicks)
    }

    /** "clay pots fetch $15 now, not $20: ...", for a ware selling below its usual price; null otherwise. */
    private fun lowNote(id: String): String? = wareText(id, always = false)

    /**
     * What a ware fetches: "a clay pot fetches $15 now, not $20: ..." when it's below its usual
     * price; when it isn't, "a clay pot sells for $20, ..." if [always], else null. Null for what isn't a ware.
     */
    private fun wareText(id: String, always: Boolean): String? {
        val spec = item(id)
        val usual = spec.sellPrice?.takeIf { market.covers(spec) } ?: return null
        val now = going(id, usual)
        return when {
            now < usual -> "a ${spec.name} fetches $$now now, not $$usual: selling a lot of one ware lowers its price, and it climbs back over a few days"
            !always -> null
            content.market == null -> "a ${spec.name} sells for $$usual"
            else -> "a ${spec.name} sells for $$usual, less after you sell a lot"
        }
    }

    /** "a thing to look at", for an affordance he can build for. */
    private fun useNoun(use: String): String = when (use) {
        "viewable" -> "something to look at in your room"
        "playable" -> "a record to play"
        "readable" -> "a book to read"
        "edible" -> "something to eat"
        "sellable" -> "something to sell"
        else -> "something $use"
    }

    /** Hangs his newest held piece with [tag] on the wall. */
    private fun hang(tag: String): String {
        val p = held(tag).lastOrNull() ?: return "you no longer have any ${noun(tag)} to hang"
        take(p.item, 1)
        p.state = PieceState.Hung
        return "hung ${pieceName(p)} on the wall; comfort ${comfort()}, so mood settles toward ${moodTarget().toInt()}"
    }

    // --- art -------------------------------------------------------------------------------------

    /**
     * Hands over a commission's wording: applied at the start of the next tick. Safe from any
     * thread; a note for a commission that isn't open is dropped.
     */
    fun annotate(note: ArtNote) {
        notes.add(note)
    }

    /**
     * Puts [letter] through the mail hatch: it arrives at the start of the next tick and waits there
     * until he opens it. Safe from any thread; one already waiting or opened is dropped.
     */
    fun post(letter: Letter) {
        posted.add(letter)
    }

    /**
     * Works out [correspondence] again: everyone whose letters he has opened, newest name first, and
     * whether rules.yaml `letters` lets him write to them now. On the sim thread, when a letter is
     * opened or written and when a day begins.
     */
    private fun correspond() {
        val rules = content.letters
        val today = clock.day(ticks)
        correspondence = read.filter { it.sender != null }.groupBy { it.sender!! }.map { (sender, theirs) ->
            val name = theirs.last().from
            val mine = sent.filter { it.to == sender }
            val lastMine = mine.lastOrNull()?.tick ?: -1
            val lastTheirs = theirs.last().tick ?: 0
            val inARow = mine.count { it.tick > lastTheirs }
            val blocked = when {
                rules == null -> "you can't write letters"
                mine.count { it.day == today } >= rules.perDay -> "you've written to $name today"
                inARow >= rules.unanswered -> "$name hasn't written back to your last ${if (inARow == 1) "letter" else "$inARow letters"}"
                else -> null
            }
            val exchanged = (theirs.map { Correspondent.Exchanged(false, it.body, it.tick ?: 0, stamp(it.tick ?: 0)) } + mine.map { Correspondent.Exchanged(true, it.body, it.tick, stamp(it.tick)) }).sortedBy { it.tick }
            Correspondent(sender, name, exchanged, theirs.filter { (it.tick ?: 0) > lastMine }, blocked)
        }.sortedByDescending { c -> c.letters.last().tick }
    }

    /**
     * Hands over the result of authored op [id] ([AuthoredView], from the observation): applied at
     * the start of the next tick. Safe from any thread; an id that isn't waiting is dropped.
     */
    fun deliver(id: Int, result: AuthoredResult) {
        deliveries.add(id to result)
    }

    /**
     * Hands over what a `before` authored op picked for [verb] before the verb starts, for an op
     * that acts on it when the verb finishes (a trip's [BasketOp]): applied at the end of the next
     * tick, long before any checkout. A later one for the same verb replaces it; a failed trip's
     * is replaced by the next trip's.
     */
    fun stage(verb: String, result: AuthoredResult) {
        if (result is AuthoredResult.Basket || result is AuthoredResult.Designed || result is AuthoredResult.Composed || result is AuthoredResult.Coded) staging.add(verb to result)
    }

    private fun applyNotes() {
        while (true) {
            val (verb, staged) = staging.poll() ?: break
            when (staged) {
                is AuthoredResult.Basket -> baskets[verb] = staged
                is AuthoredResult.Designed -> designs[verb] = staged
                is AuthoredResult.Composed -> drafts[verb] = staged
                is AuthoredResult.Coded -> codes[verb] = staged
                else -> {}
            }
        }
        while (true) {
            val l = posted.poll() ?: break
            if (l.id in opened || mailbox.any { it.id == l.id }) continue
            mailbox.addLast(l)
            version++
            event("a letter from ${l.from} came through the mail hatch")
        }
        while (true) {
            when (val note = notes.poll() ?: break) {
                is ArtNote.CommissionWorded -> commission?.takeIf { it.id == note.commission }?.let { it.request = note.request }
            }
        }
        while (true) {
            val (id, result) = deliveries.poll() ?: return
            val i = authored.indexOfFirst { it.id == id && !it.delivered }
            if (i < 0) continue
            val view = authored[i]
            authored[i] = view.copy(delivered = true)
            version++
            if (result is AuthoredResult.Drawn) view.piece?.let { pieces.getOrNull(it - 1) }?.let { p -> drawn(p, result) }
        }
    }

    private fun drawn(p: Piece, d: AuthoredResult.Drawn) {
        p.title = d.title
        p.tags = d.tags
        p.shows = d.shows
        val c = commission?.takeIf { it.id == p.commission && !it.fulfilled }
        val verdict = when {
            c == null -> ""
            matches(p, c) -> ": it matches the request for ${c.wants}, so it earns $${content.commissions!!.reward} more when sold"
            else -> ": it doesn't match the request for ${c.wants}: ${unmet(p, c)}"
        }
        event("${item(p.item).name} ${p.id} is drawn, \"${d.title}\"$verdict")
        subjectNovelty(d)
    }

    /**
     * Pays the novelty bonus the first time he draws a subject: the commission noun the drawing showed,
     * or the one its tags name. Subjects are the novelty a broke guy can always reach.
     */
    private fun subjectNovelty(d: AuthoredResult.Drawn) {
        if (content.novelty == null) return
        val nouns = content.commissions?.nouns ?: return
        val noun = (listOfNotNull(d.shows) + d.tags).firstNotNullOfOrNull { w -> nouns.firstOrNull { it.equals(w, ignoreCase = true) } } ?: return
        if (!tried.add("$SUBJECT$noun")) return
        subjectsDrawn = subjectsDrawn + noun
        version++
        event("drawing ${article(noun)} was new to you: " + novelty().entries.joinToString { (need, v) -> "$need ${signed((needs.change(ticks, needOf(need), v, "new: $SUBJECT$noun") * 10).roundToInt() / 10.0)}" })
    }

    private fun held(tag: String): List<Piece> = pieces.filter { it.state == PieceState.Held && tag in item(it.item).names }

    private fun hung(): List<Piece> = pieces.filter { it.state == PieceState.Hung }

    private fun openCommission(): Commission? = commission?.takeIf { !it.fulfilled && ticks < it.until }

    /**
     * Why [p] doesn't meet request [c], or null when it does: it was made for it, its title or tags
     * name the adjective, and it drew a subject shape for the noun ([AuthoredResult.Drawn.shows]).
     */
    private fun unmet(p: Piece, c: Commission): String? {
        if (p.commission != c.id) return "it wasn't made for it"
        if (p.title == null) return "it isn't drawn yet"
        val text = (p.title + " " + p.tags.joinToString(" ")).lowercase()
        if (c.adjective.lowercase() !in text) return "it isn't ${c.adjective}"
        if (!c.noun.equals(p.shows, ignoreCase = true)) return "there's no ${c.noun} drawn in it"
        return null
    }

    private fun matches(p: Piece, c: Commission): Boolean = unmet(p, c) == null

    /** The first of [selling] that meets the open request, if any: only one is paid for it. */
    private fun winner(selling: List<Piece>): Piece? = openCommission()?.let { c -> selling.firstOrNull { matches(it, c) } }

    /** Each piece with what it sells for, the request's reward included. */
    private fun saleOf(selling: List<Piece>): List<Pair<Piece, Int>> {
        val w = winner(selling)
        return selling.map { p -> p to piecePrice(p) + if (p === w) content.commissions!!.reward else 0 }
    }

    /** "sketch 3" or "sketch 3, \"Sleepy lighthouse\"". */
    private fun pieceName(p: Piece): String = "${item(p.item).name} ${p.id}" + (p.title?.let { ", \"$it\"" } ?: "")

    private fun newCommission(rules: CommissionRules) {
        openCommission()?.let { event("the request for ${it.wants} lapsed") }
        val n = ++commissions
        val random = Random(seed * 1_000_003 + n)
        val adjective = rules.adjectives.random(random)
        val noun = rules.nouns.random(random)
        val c = Commission(n, adjective, noun, article(adjective) + " " + noun, ticks + clock.ticks(GameClock.MINUTES_PER_DAY))
        commission = c
        version++
        event("a request came in: ${c.wants}. The first piece you finish from now that matches it earns $${rules.reward} more when sold, until ${stamp(c.until)}")
    }

    private fun commissionView(): CommissionView? = openCommission()?.let { CommissionView(it.id, it.wants, it.request, content.commissions!!.reward, stamp(it.until)) }

    private fun artViews(): List<PieceView> {
        val prices = saleOf(pieces.filter { it.state == PieceState.Held }).associate { (p, price) -> p.id to price }
        val c = openCommission()
        return pieces.filter { it.state != PieceState.Sold }.map { p ->
            val note = when {
                c == null || p.commission != c.id || p.state != PieceState.Held -> null
                p.title == null -> "made for the request; not drawn yet"
                matches(p, c) -> "matches the request: +$${content.commissions!!.reward} when sold"
                else -> "doesn't match the request: ${unmet(p, c)}"
            }
            PieceView(p.id, p.item, p.title, p.state == PieceState.Hung, prices[p.id], note)
        }
    }

    private fun purchase(spec: ItemSpec, price: Int): String? {
        if (!ledger.canAfford(price)) return "the ${spec.name} costs $$price and you have $${ledger.balance}"
        if (!spec.stackable && count(spec.id) > 0) return "you already have the ${spec.name}"
        outcome(bought(spec, price))
        return null
    }

    /** Why the shop won't sell him [spec] now, or null: it's priced and he knows it. */
    private fun refusal(spec: ItemSpec): String? = when {
        spec.price == null -> "the ${spec.name} isn't for sale"
        !spec.stackable && count(spec.id) > 0 -> "you already have the ${spec.name}"
        heldBack(spec) != null -> "the ${spec.name} is no use to you yet, so the shop won't sell it to you until you have ${wanted(heldBack(spec)!!)}"
        !ledger.canAfford(spec.price) -> "the ${spec.name} costs $${spec.price} and you have $${ledger.balance}"
        else -> null
    }

    /** Pays for [spec] and adds it; "bought 1 easel for $17 (money $5)". */
    private fun bought(spec: ItemSpec, price: Int): String {
        val what = quantity(spec.id, spec.pack)
        ledger.debit(ticks, price, "bought $what")
        add(spec.id, spec.pack)
        val comfortNote = if (comfortOf(spec) != 0) "; ${comfortText()}" else ""
        val newNote = newThingRate(spec).takeIf { it > 0 }?.let { newThings[spec.id] = ticks; "; new: ${newThingText(it)}" } ?: ""
        return "bought $what for $$price (money $${ledger.balance})$comfortNote$newNote"
    }

    private fun quantity(id: String, n: Int): String {
        val name = item(id).name
        return if (n == 1) "1 $name" else "$n " + if (name.endsWith("s") || name.endsWith("sh") || name.endsWith("ch")) "${name}es" else "${name}s"
    }

    // --- time and needs --------------------------------------------------------------------------

    override fun afterWork() {
        applyNotes()
        music?.let {
            if (ticks >= it.endTick) {
                music = null
                version++
                event("the ${item(it.itemId).name} finished")
            }
        }
        if (!clock.isMinuteStart(ticks)) return machines.afterTick(machineHost)
        applyMinute()
        works.minute(worksHost, clock.minutes(ticks))
        val minute = clock.minuteOfDay(ticks)
        if (minute % 60 == 0) needs.flush(ticks)
        if (minute % 60 == 0) weatherHour()
        if (minute % 60 == 0) effectsHour(minute / 60)
        content.commissions?.let { if (minute == GameClock.parseHhmm(it.arrive)) newCommission(it) }
        if (minute == 0) {
            event("day ${clock.day(ticks)} begins")
            correspond()
        }
        machines.afterTick(machineHost)
    }

    /** What stimulation loses an awake hour now: flat, or scaled by his level when the rules say where it's full ([NeedRules.stimulationDrainAt]). */
    private fun stimulationDrain(): Double {
        val r = content.needs
        val scale = r.stimulationDrainAt?.let { needs[Need.Stimulation] / it } ?: 1.0
        return r.stimulationPerHour * scale * content.personality.curiosity
    }

    /** One game minute of the need rules; every rate is per hour. */
    private fun applyMinute() {
        val r = content.needs
        val d = doing
        val rate = ops::rate
        if (d == null || d.activity.effects.all { it.awake }) {
            rate(Need.Energy, r.awakeEnergyPerHour, "awake")
            rate(Need.Stimulation, stimulationDrain(), "awake")
            newThings.forEach { (id, since) ->
                val seen = item(id).slot?.let { layout.slotCells[it] }?.let(::seenWeight) ?: 0.0
                rate(Need.Stimulation, newThingRate(item(id), since) * seen, "new: ${item(id).name}")
            }
        }
        newThings.entries.removeIf { (id, since) -> count(id) == 0 || newThingRate(item(id), since) <= 0 }
        if (content.comfort?.adapt != null) {
            ownedSince.keys.retainAll(owned.keys)
            owned.keys.forEach { ownedSince.putIfAbsent(it, ticks) }
        }
        val asleep = d != null && d.activity.effects.any { !it.awake }
        rate(Need.Fullness, if (asleep) r.asleepFullnessPerHour ?: r.fullnessPerHour else r.fullnessPerHour, "hunger")
        content.frustration?.let { rate(Need.Frustration, it.perHour, "fading") }
        coldNow()?.let { c ->
            rate(Need.Mood, c.moodPerHour, "cold")
            if (c.energyPerHour != 0.0) rate(Need.Energy, c.energyPerHour, "cold")
        }
        d?.activity?.effects?.forEach { it.minute(ops, d.bound) }
        // A cold or noisy bed gives back only some of sleep's energy.
        if (asleep) content.sleep?.let { s ->
            surroundings.restless(effectHost, guy.cell)?.let { why ->
                val energy = d!!.activity.ops<RateOp>().sumOf { it.perHour[Need.Energy.id] ?: 0.0 }
                if (energy > 0) rate(Need.Energy, -energy * (1 - s.rest), "slept badly: $why")
            }
        }
        // Music lifts him as much as he can hear it from where he is.
        music?.let { m ->
            val heard = m.on?.let { on -> content.item(on)?.takeIf { it.sound != null }?.let { surroundings.heard(effectHost, it, guy.cell) } } ?: 1.0
            if (heard > 0) m.spec.rates.forEach { (need, perHour) -> rate(needOf(need), paid(needOf(need), perHour, m.fresh) * if (perHour > 0) heard else 1.0, m.spec.name) }
        }
        // Low energy or fullness replaces the drift: mood just falls, at the steeper rate.
        if (needs[Need.Energy] < r.low || needs[Need.Fullness] < r.low) {
            rate(Need.Mood, r.lowMoodPerHour, "low energy or fullness")
        } else {
            val gap = moodTarget() - needs[Need.Mood]
            if (gap != 0.0) needs.change(ticks, Need.Mood, sign(gap) * min(r.moodDriftPerHour / 60, abs(gap)), "drift toward ${moodTarget().toInt()}", rate = true)
        }
    }

    // --- checkpoints ----------------------------------------------------------------------------

    /**
     * True when the world can be saved: nothing running or queued, no activity, no authored result,
     * basket or commission wording on its way in. A decision point after waking is such a moment.
     * Authored views still waiting are saved as they are: whether a result is still coming is the
     * agent side's to say (with no handler for an op, none ever is).
     */
    val canExport: Boolean
        get() = atRest && doing == null && notes.isEmpty() && posted.isEmpty() && deliveries.isEmpty() && staging.isEmpty() && baskets.isEmpty() && designs.isEmpty() && drafts.isEmpty()

    /** Everything a [restore] needs to carry on from here. Only when [canExport]; call on the sim thread. */
    fun exportState(): BedroomState {
        check(canExport) { "can't save the bedroom with something in flight" }
        return BedroomState(
            grid = gridState(),
            money = ledger.balance,
            needs = Need.entries.associate { it.id to needs[it] },
            needsPending = needs.pending().map { (k, d) -> BedroomState.PendingNeed(k.first.id, k.second, d) },
            owned = LinkedHashMap(owned),
            skills = LinkedHashMap(skills),
            chapters = chapters.toMap(),
            known = known.sorted(),
            wear = wear.mapValues { (_, w) -> BedroomState.Wear(w.first, w.second) },
            tried = tried.sorted(),
            newThings = LinkedHashMap(newThings),
            done = done,
            music = music?.let { BedroomState.Music(it.itemId, it.on, it.spec.name, it.endTick, it.fresh) },
            savingFor = savingFor,
            pieces = pieces.map { BedroomState.Piece(it.id, it.item, it.commission, it.skill, it.state.name.lowercase(), it.title, it.tags, it.shows) },
            commission = commission?.let { BedroomState.Commission(it.id, it.adjective, it.noun, it.wants, it.until, it.request, it.fulfilled) },
            commissions = commissions,
            authored = authored.toList(),
            authoredCount = authoredCount,
            inventions = inventions,
            explored = explored.toList(),
            house = LinkedHashMap(house),
            bare = bareUntil.filterValues { it > ticks },
            lamps = lampModes.mapValues { it.value.id },
            mail = mailbox.toList(),
            opened = opened.toList(),
            read = read.toList(),
            sent = sent.toList(),
            coded = coded,
            thingState = thingState.mapValues { LinkedHashMap(it.value) },
            custom = LinkedHashMap(custom),
            behaviors = behaviors,
            records = records.export(),
            machines = machines.export(),
            familiar = familiar?.export().orEmpty(),
            ownedSince = LinkedHashMap(ownedSince),
            works = works.export(),
            surroundings = surroundings.export(),
            market = market.export(content.market, ticks, dayTicks),
        )
    }

    /** Overwrites this fresh world with [s]; returns what it had to drop because the content no longer has it. */
    private fun load(s: BedroomState): List<String> {
        val dropped = mutableListOf<String>()
        s.inventions.forEach { made ->
            runCatching { content = content.plus(made) }
                .onSuccess { inventions = inventions + made }
                .onFailure { dropped += "invention '${made.item.id}' no longer fits the content: ${it.message}" }
        }
        s.coded.forEach { made ->
            runCatching { content = content.plusVerb(made.verb) }
                .onSuccess { coded = coded.filter { it.verb.id != made.verb.id } + made }
                .onFailure { dropped += "his verb '${made.verb.id}' no longer fits the content: ${it.message}" }
        }
        fun has(id: String, what: String) = (content.item(id) != null).also { if (!it) dropped += "$what '$id' is no longer in the content" }
        owned.clear()
        pieces.clear()
        s.owned.forEach { (id, n) -> if (has(id, "item")) owned[id] = n }
        // A fixture he starts with is always there, though an older checkpoint may not have it (the land's lookouts and patches).
        content.start.items.forEach { (id, n) -> if (item(id).group == "fixture" && id !in owned) owned[id] = n }
        house.clear()
        s.house.forEach { (room, site) -> if (content.world?.room(room) != null && content.world?.site(site) != null) house[room] = site else dropped += "room '$room' on '$site' is no longer in the content" }
        if (house.isNotEmpty()) {
            layout = Layout.of(content, house)
            map = layout.map
        }
        explored.clear(); explored.addAll(s.explored.filter { layout.place(it) != null })
        bareUntil.clear(); s.bare.forEach { (id, t) -> if (content.item(id) != null) bareUntil[id] = t }
        lampModes.clear(); s.lamps.forEach { (id, m) -> if (content.item(id)?.affords?.containsKey(LAMP) == true) lampModes[id] = LampMode.of(m) else dropped += "lamp '$id' is no longer a lamp in the content" }
        s.pieces.forEach { p ->
            if (has(p.item, "piece ${p.id}'s item")) pieces += Piece(p.id, p.item, p.commission, p.skill).also {
                it.state = PieceState.valueOf(p.state.replaceFirstChar(Char::uppercase))
                it.title = p.title
                it.tags = p.tags
                it.shows = p.shows
            }
        }
        ledger.restore(s.money)
        needs.restore(
            Need.entries.associateWith { n -> s.needs[n.id] ?: needs[n] },
            s.needsPending.mapNotNull { p -> Need.entries.firstOrNull { it.id == p.need }?.let { (it to p.reason) to p.delta } }.toMap(),
        )
        skills.clear(); skills.putAll(s.skills)
        chapters.clear()
        s.chapters.forEach { (id, n) -> if (content.book(id) != null) chapters[id] = n else dropped += "book '$id' is no longer in the content" }
        known = s.known.filter { (it in content.taught).also { ok -> if (!ok) dropped += "taught '$it' is no longer taught" } }.toSet()
        knownCache = null
        wear.clear(); s.wear.forEach { (k, w) -> wear[k] = w.level to w.tick }
        tried.clear(); tried.addAll(s.tried)
        subjectsDrawn = tried.filter { it.startsWith(SUBJECT) }.map { it.removePrefix(SUBJECT) }.toSet()
        newThings.clear(); s.newThings.forEach { (id, t) -> if (has(id, "new thing")) newThings[id] = t }
        // A checkpoint from before tallies kept their last tick has the first in its place: count those as lately, not as long ago.
        done = s.done.mapValues { (_, t) -> if (t.count > 1 && t.lastTick == t.firstTick) t.copy(lastTick = s.grid.tick) else t }
        music = s.music?.let { m ->
            val op = content.verbs.flatMap { it.effects }.filterIsInstance<BackgroundOp>().firstOrNull { it.name == m.op }
            if (op == null || content.item(m.item) == null) null.also { dropped += "music '${m.op}' of '${m.item}' is no longer in the content" }
            else Music(m.item, m.on, op, m.endTick, m.fresh)
        }
        savingFor = s.savingFor?.takeIf { has(it, "saving for") }
        commission = s.commission?.let { Commission(it.id, it.adjective, it.noun, it.wants, it.until).also { c -> c.request = it.request; c.fulfilled = it.fulfilled } }
        commissions = s.commissions
        authored.clear(); authored.addAll(s.authored)
        mailbox.clear(); mailbox.addAll(s.mail)
        opened.clear(); opened.addAll(s.opened)
        lettersOpened = opened.toSet()
        read.clear(); read.addAll(s.read)
        sent.clear(); sent.addAll(s.sent)
        lettersSent = sent.toList()
        correspond()
        // What his code changed about his things, once the house (and its slots) stands again.
        custom.clear()
        s.custom.forEach { (id, c) ->
            if (!has(id, "changed thing")) return@forEach
            runCatching { content = content.mapItems { if (it.id == id) it.copy(slot = c.slot ?: it.slot, name = c.name ?: it.name) else it } }
                .onSuccess { custom[id] = c }
                .onFailure { custom[id] = c.copy(slot = null); dropped += "the ${item(id).name} couldn't go back to ${c.slot}: ${it.message}" }
        }
        behaviors = s.behaviors.filterKeys { content.item(it) != null }
        records.restore(s.records) { content.item(it) != null }
        machines.restore(s.machines) { content.item(it) != null }
        works.restore(s.works) { content.item(it) != null }
        market.load(s.market.filterKeys { content.item(it) != null })
        surroundings.restore(s.surroundings) { content.item(it) != null }
        thingState.clear()
        s.thingState.forEach { (id, kept) -> if (id == SELF || content.item(id) != null) thingState[id] = LinkedHashMap(kept) }
        knownCache = null
        authoredCount = s.authoredCount
        lastPlanned = s.grid.last?.intent
        version++
        options = null
        syncHolding()
        // Before version 2 the bedroom was the whole map: his position moves with the room onto the lot.
        restoreGrid(if (s.version >= 2) s.grid else s.grid.copy(x = s.grid.x + layout.roomOrigin.x, y = s.grid.y + layout.roomOrigin.y))
        ownedSince.clear()
        // An older save: he has had everything he owns since the start.
        s.ownedSince?.let { ownedSince.putAll(it.filterKeys { id -> id in owned }) } ?: owned.keys.forEach { ownedSince[it] = 0L }
        familiar?.let { f ->
            f.restore(s.familiar)
            if (s.familiar.isEmpty()) familiarFromTallies(f)
        }
        return dropped
    }

    /**
     * A save from before familiarity: rebuild it from his tallies, each verb's finishes spread evenly from its
     * first to its last, met in order. Without the items each was done with, so it's only near.
     */
    private fun familiarFromTallies(f: Familiarity) {
        done.flatMap { (id, t) ->
            val v = content.verb(id)?.takeIf { !it.isRoutine } ?: return@flatMap emptyList()
            val fs = features(v, emptyMap())
            (0 until t.count).map { i -> (if (t.count == 1) t.lastTick else t.firstTick + (t.lastTick - t.firstTick) * i / (t.count - 1)) to fs }
        }.sortedBy { it.first }.forEach { (tick, fs) -> f.meet(fs, tick) }
    }

    /** A world [restore]d from a checkpoint, and what of it the content no longer has. */
    class Restored(val world: BedroomWorld, val dropped: List<String>)

    // --- what the agent and the renderer see ----------------------------------------------------

    override fun stamp(tick: Long): String = "d${clock.day(tick)} ${GameClock.hhmm(clock.minuteOfDay(tick))}"

    private fun shopItems(): List<ItemSpec> = content.items.filter { it.price != null && isKnown(it.id) && (it.stackable || count(it.id) == 0) }

    /** What a shopping trip looks through: everything the shop has for him, held back or not. */
    private fun tripItems(): List<ItemSpec> = shopItems()

    /** One option per way to bind [a]'s arguments that isn't locked, or one locked option saying why. */
    private fun activityOptions(a: VerbSpec): List<ActivityOption> {
        lockReason(a)?.let { return listOf(ActivityOption(a.id, locked = it)) }
        val open = bindings(a).map { it to lockReason(a, it) }
        // Things he owns but doesn't know of yet (patches he hasn't found) bind to nothing.
        if (open.isEmpty()) return listOf(ActivityOption(a.id, locked = "you haven't found anywhere to ${a.id} yet"))
        if (open.none { it.second == null }) return listOf(ActivityOption(a.id, locked = open.first().second))
        return open.filter { it.second == null }.map { (args, _) ->
            val (where, walk) = whereFor(a, args) ?: (null to null)
            ActivityOption(a.id, args["what"], args["on"], takes(a), gives(a, args = args), where = where, walk = walk)
        }
    }

    /**
     * Where [a] with [args] would happen and the walk there, when that's another place than where
     * he is: the nearest of the things it's done at. Null when it's done here or in this place.
     */
    private fun whereFor(a: VerbSpec, args: Map<String, String>): Pair<String, Int>? {
        if (!layout.outdoors || VerbSpec.HERE in a.at) return null
        val here = layout.placeOf(guy.cell)
        val ids = a.at.map { args[it] ?: it }
        val cells = placed().filter { it.first.id in ids }.map { it.second }
        if (cells.any { layout.placeOf(it) == here }) return null
        return cells.mapNotNull { c -> walkFromHere(c)?.let { c to it } }.minByOrNull { it.second }
            ?.let { (c, minutes) -> (layout.placeOf(c)?.name ?: "elsewhere") to minutes }
    }

    private fun takes(a: VerbSpec): String {
        val nap = byDay(a)?.minutes
        val until = a.until ?: return GameClock.duration(nap?.let { min(it, a.minutes!!) } ?: a.minutes!!) + a.effects.joinToString("") { it.takes() }
        val toClock = clock.ticksUntil(ticks, GameClock.parseHhmm(until.time)) / clock.ticksPerMinute
        val byClock = nap?.let { min(it.toLong(), toClock) } ?: toClock
        val need = until.need?.let(::needOf)
        val perHour = need?.let { n -> a.ops<RateOp>().firstNotNullOfOrNull { it.perHour[n.id] } }
        val byNeed = if (need != null && perHour != null && perHour > 0) ((until.reaches!! - needs[need]) / perHour * 60).toLong() else Long.MAX_VALUE
        val estimate = (min(byClock, byNeed) + ESTIMATE_MINUTES / 2) / ESTIMATE_MINUTES * ESTIMATE_MINUTES
        val about = GameClock.duration(estimate.coerceAtLeast(ESTIMATE_MINUTES.toLong()).toInt())
        val stops = a.stopBelow.entries.joinToString("") { (n, level) -> ", or $n below ${level.toInt()}" }
        val ends = if (nap != null) "up to ${GameClock.duration(byClock.toInt())}" else "until ${until.time}"
        return ends + (need?.let { " or ${it.id} ${until.reaches!!.toInt()}" } ?: "") + stops + ", about $about"
    }

    /** [forSale]: describing a verb he can't do yet, so leave out what he has now. [args]: what its arguments are bound to. */
    private fun gives(a: VerbSpec, forSale: Boolean = false, args: Map<String, String> = emptyMap()): String {
        val b = bound(a, args)
        val parts = a.effects.flatMapTo(mutableListOf()) { it.describe(ops, b, forSale) }
        if (forSale) return parts.joinToString("; ")
        val fresh = b.fresh
        if (familiar != null) {
            if (!upkeep(a) && !fixing(a)) parts += familiarText(fresh, familiarFun(fresh))
        } else {
            content.habituation?.takeIf { fresh < 1 }?.let { h ->
                val stale = (h.staleStimulation * (1 - fresh)).roundToInt()
                parts += "done a lot lately: gains at ${percent(fresh)}" + if (stale < 0) ", and stimulation $stale" else ""
            }
            if (isNew(a, args)) parts += "new to you: " + novelty().entries.joinToString { (need, d) -> "$need ${signed(d.roundToInt().toDouble())}" }
        }
        // Offered while bored of it: only work, while he can't afford anything new.
        if (boredOf(a, args) != null) parts += "you're bored of it, but you can't afford anything new yet, so you keep at it"
        return parts.joinToString("; ")
    }

    /**
     * The verbs [spec] is for: it's where they happen, or what they need or use. A verb it fills every
     * argument of, but which one of its ops refuses for it, isn't one of them.
     */
    private fun usesOf(spec: ItemSpec): List<VerbSpec> = memo?.uses?.getOrPut(spec.id) { findUses(spec) } ?: findUses(spec)

    private fun findUses(spec: ItemSpec): List<VerbSpec> = content.verbs.filter { a ->
        isKnown(a.id) && (spec.id in a.at || (a.requires + a.effects.flatMap { it.asksFor } + a.args.values).any { it in spec.names } ||
            // A material is for building: the shop holds it back until he has a workbench, and says it's for invent.
            (content.invent?.materials?.containsKey(spec.id) == true && a.ops<BuildOp>().isNotEmpty()))
    }.filterNot { a ->
        a.args.isNotEmpty() && a.args.values.all { it in spec.names } &&
            a.effects.any { it.lockReason(ops, Bound(a, a.args.mapValues { spec.id }, 1.0, extra = spec.id)) != null }
    }

    /** Where [a] happens, as item ids or affordances (an `on` argument by what fills it); empty for `here`. */
    private fun placesOf(a: VerbSpec): List<String> = if (VerbSpec.HERE in a.at) emptyList() else a.at.map { a.args[it] ?: it }

    /**
     * What [spec] waits on when it's no use to him yet, so the shop holds it back: every verb it's for
     * happens at something he doesn't have, and this is the first of those (record_player for a
     * record). Null when one of them can happen, or when it's for no verb at all (decor). The thing a
     * verb happens at is never held back itself, so there's always something to buy first.
     */
    private fun heldBack(spec: ItemSpec): String? {
        val m = memo ?: return findHeldBack(spec)
        return if (spec.id in m.held) m.held[spec.id] else findHeldBack(spec).also { m.held[spec.id] = it }
    }

    private fun findHeldBack(spec: ItemSpec): String? {
        val missing = usesOf(spec).map { a -> placesOf(a).takeIf { ps -> ps.isNotEmpty() && ps.none { countTag(it, spec.id) > 0 } } }
        if (missing.isEmpty() || missing.any { it == null }) return null
        return missing.first()!!.first()
    }

    private fun shopOption(spec: ItemSpec): ShopOption {
        val parts = mutableListOf<String>()
        if (spec.pack > 1) parts += quantity(spec.id, spec.pack)
        content.book(spec.id)?.let { b ->
            parts += "\"${b.title}\": ${b.blurb}"
            parts += "${b.chapters.size} chapters, one a session"
            if (b.skill.isNotEmpty()) parts += "reading it raises " + b.skill.keys.joinToString(" and ")
            ops.teaching(b.id)?.let { parts += "finishing it teaches you $it" }
        }
        // What it's for. When it's what a locked activity is missing, say so and what that activity gives.
        // Switching a lamp is said with its light, below.
        usesOf(spec).filter { a -> a.ops<SwitchOp>().isEmpty() }.forEach { a ->
            val locked = lockReason(a)
            val after = lockReason(a, extra = spec.id)
            val what = takes(a) + gives(a, forSale = true).let { if (it.isEmpty()) "" else ": $it" }
            parts += when {
                locked == null || locked == after -> "for ${a.id}"
                after == null -> "unlocks ${a.id} ($what)"
                else -> "for ${a.id} ($what), which also $after"
            }
        }
        // What its batches make to sell, at the going price.
        spec.machine?.runs.orEmpty().mapNotNull { content.machines?.recipe(it) }.flatMap { it.makes.keys }.distinct()
            .map(::item).filter { it.sellPrice != null && market.covers(it) }.forEach { parts += wareText(it.id, always = true)!! }
        val comfort = comfortOf(spec)
        val seen = if (content.comfort != null) " in view (near you${if (content.light != null) ", lit" else ""})" else ""
        when {
            comfort > 0 -> parts += "comfort +$comfort$seen: mood settles $comfort higher"
            comfort < 0 -> parts += "comfort $comfort$seen: you don't like it, so mood settles ${-comfort} lower"
        }
        spec.light?.let { parts += "gives light ${Lighting.level(it.strength)}, reaching ${Lighting.level(it.radius)} tiles" + if (LAMP in spec.affords) ", on a timer (lit ${timerOf(spec).text}) you can switch" else "" }
        newThingRate(spec).takeIf { it > 0 }?.let { parts += "new to you: ${newThingText(it)}" }
        if (spec.qualities.isNotEmpty()) parts += spec.qualities.keys.joinToString(", ")
        // What owning it opens up: items the shop holds back until he has this.
        shopItems().filter { other -> other.id != spec.id && heldBack(other) != null && usesOf(other).any { a -> placesOf(a).any { it in spec.names } } }
            .map { "${article(it.name)} ($${it.price})" }.filter { o -> parts.none { o in it } }
            .takeIf { it.isNotEmpty() }?.let { parts += "then the shop sells you " + it.joinToString() }
        // Spending the food money: said when he has no food left and this would leave him short of the cheapest.
        val left = ledger.balance - spec.price!!
        foodTags.filter { tag -> tag !in spec.names && countTag(tag) == 0 }.firstOrNull { tag ->
            val cheapest = content.withName(tag).mapNotNull { it.price }.minOrNull()
            cheapest != null && left in 0 until cheapest
        }?.let { parts += "leaves you $$left with no food, short of ${wanted(it, cheapest = true)}" }
        return ShopOption(spec.id, spec.price!!, ledger.canAfford(spec.price), parts.joinToString("; "))
    }

    private fun musicText(): String? = music?.let { "${it.itemId}, ${GameClock.duration(((it.endTick - ticks) / clock.ticksPerMinute).toInt().coerceAtLeast(1))} left" }

    override fun publish(): Published {
        val visited = if (content.world?.visitors.isNullOrEmpty()) emptyList() else visits()
        val entities = buildList {
            add(EntitySnapshot(guy.id, "guy", guy.x, guy.y, 1f, guy.facing.degrees, place = layout.placeOf(guy.x, guy.y)?.id, light = content.light?.lantern))
            placed().forEach { (spec, cell) ->
                val state = when {
                    spec.id == music?.on -> "playing ${music!!.itemId}"
                    (bareUntil[spec.id] ?: 0) > ticks -> "bare"
                    LAMP in spec.affords && !lampLit(spec) -> "off"
                    spec.lure != null && visited.any { it.lure.id == spec.id } -> VISITED
                    spec.grows != null && surroundings.harvestLock(spec) == null -> RIPE
                    else -> if (works.isMachine(worksHost, spec.id)) works.state(worksHost, spec.id) else null
                }
                val look = machines.look(spec.id)
                val light = spec.light?.takeIf { lampLit(spec) }
                add(
                    EntitySnapshot(
                        EntityId(spec.id), spec.group, cell.x + 0.5f, cell.y + 0.5f, if (spec.blocks) 1f else 0.2f, 0f, state, carriedBy = guy.id.takeIf { spec.id == carrying }, place = layout.placeOf(cell)?.id, color = custom[spec.id]?.color,
                        variant = look?.variant, motions = look?.motions.orEmpty(), readout = look?.readout ?: works.readout(worksHost, spec.id), light = light,
                    ),
                )
            }
        }
        val d = doing
        val life = LifeSnapshot(
            clock = clock.reading(ticks),
            money = ledger.balance,
            energy = needs[Need.Energy],
            fullness = needs[Need.Fullness],
            mood = needs[Need.Mood],
            stimulation = needs[Need.Stimulation],
            frustration = needs[Need.Frustration],
            skills = skillView(),
            books = bookViews(),
            moodTarget = moodTarget(),
            doing = d?.activity?.id,
            progress = d?.let { ((ticks - it.startTick).toFloat() / (it.endTick - it.startTick).coerceAtLeast(1)).coerceIn(0f, 1f) },
            music = musicText(),
            holding = holding(),
            owns = ownsMap(),
            transactions = ledger.transactions.takeLast(SNAPSHOT_TRANSACTIONS),
            clockScale = clock,
            commission = commissionView(),
            art = artViews(),
            catalog = current.catalog,
            inventions = invented,
            verbs = current.verbs,
            done = done,
            coded = coded,
            behaviors = behaviors,
            stored = thingState.mapValues { (_, kept) -> kept.mapValues { it.value.toString() } },
            custom = LinkedHashMap(custom),
            weather = weatherView(),
        )
        val snapshot = WorldSnapshot(ticks, map, entities, listOf(agentSnapshot()), eventLog(), life = life, layout = layout)
        return Published(snapshot, observe(entities))
    }

    private fun observe(entities: List<EntitySnapshot>): Observation {
        // What's near him is what's in his place, and only what he knows of.
        val here = layout.placeOf(guy.cell)?.id
        // At night only what's lit is seen; what he knows is there shows as remembered, and the rest not at all.
        val field = lightField()
        val see = content.light?.see ?: 0.0
        val around = entities.filter { it.id != guy.id && (!layout.outdoors || it.place == here) && isKnown(it.id.value) }
        val nearby = around.filter { e -> field[Cell(e.x.toInt(), e.y.toInt())] >= see || knownThere(e) }.map { e ->
            val dx = e.x - guy.x
            val dy = e.y - guy.y
            val cell = Cell(e.x.toInt(), e.y.toInt())
            SeenEntity(e.id.value, e.kind, cell.x, cell.y, Direction.of(dx, dy), round(hypot(dx, dy), 1), e.state.takeIf { field[cell] >= see }, chebyshev(guy.cell, cell) <= REACH, remembered = field[cell] < see)
        }.sortedBy { it.distance }
        val light = content.light?.let { rules ->
            val things = around.map { Landmark(item(it.id.value).name, Cell(it.x.toInt(), it.y.toInt()), knownThere(it), key = item(it.id.value).group in KEY_GROUPS) }
            Lighting.describe(lightField(lantern = false), field, rules, guy.cell, layout.placeOf(guy.cell)?.kind == PlaceKind.Outdoor, things, current.activities.any { it.locked?.startsWith(DARK) == true })
        }
        val evening = content.night?.covers(clock.minuteOfDay(ticks)) == true || clock.minuteOfDay(ticks) >= EVENING
        val bed = placed().firstOrNull { (spec, _) -> content.verbs.any { v -> v.ops<SleepOp>().isNotEmpty() && spec.id in v.at } }?.second
        val senses = surroundings.senses(effectHost, music?.let { m -> m.on?.let { content.item(it) }?.takeIf { it.sound != null }?.let { it to item(m.itemId).name } }, bed.takeIf { doing == null }, evening)
        return Observation(
            place = whereText(),
            light = light,
            senses = senses,
            places = placeOptions(),
            put = putOptions(),
            tick = ticks,
            self = SelfState(guy.id, round(guy.x, 2), round(guy.y, 2), guy.facing, guy.inventory.toList()),
            nearby = nearby,
            localMap = roomMap().second,
            localMapAt = roomMap().first.takeIf { layout.outdoors }?.let { Cell(it.x0, it.y0) },
            lastIntent = last?.intent,
            lastResult = last?.status,
            recentEvents = recentEvents(),
            clock = clock.reading(ticks),
            money = ledger.balance,
            needs = needs.levels(),
            holding = holding(),
            owns = ownsMap(),
            music = musicText(),
            activities = current.activities,
            shop = current.shop,
            commission = content.commissions?.let { commissionView() },
            art = if (pieces.isEmpty()) null else artViews(),
            skills = content.vocabulary.skills.takeIf { it.isNotEmpty() }?.let { skillView() },
            books = bookViews().ifEmpty { null },
            lately = lately(),
            authored = authored.toList(),
            outcome = last?.outcome,
            news = news(),
            weather = weatherText(),
            newsPlaces = newsPlaces { layout.place(it)?.name },
        )
    }

    /**
     * "in the last 2 days: paint 5, play_guitar 4", "not for 2 days or more: explore (last d2)", "never yet: invent":
     * the verbs he could do now that aren't routine. Null on the first day, when everything is new.
     */
    private fun lately(): List<String>? {
        val window = clock.ticks(LATELY_DAYS * GameClock.MINUTES_PER_DAY)
        if (clock.day(ticks) < 2) return null
        while (recent.isNotEmpty() && recent.first().second < ticks - window) recent.removeFirst()
        val open = current.activities.filter { it.locked == null }.map { it.id }.distinct()
            .mapNotNull { content.verb(it) }.filter { !it.isRoutine }
        val lines = mutableListOf<String>()
        recent.groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }
            .takeIf { it.isNotEmpty() }?.let { lines += "in the last $LATELY_DAYS days: " + it.joinToString { (id, n) -> "$id $n" } }
        open.filter { v -> done[v.id]?.let { it.lastTick < ticks - window } == true }
            .takeIf { it.isNotEmpty() }?.let { lines += "not for $LATELY_DAYS days or more: " + it.joinToString { v -> "${v.id} (last d${clock.day(done[v.id]!!.lastTick)})" } }
        open.filter { it.id !in done }.takeIf { it.isNotEmpty() }?.let { lines += "never yet: " + it.joinToString { v -> v.id } }
        return lines.ifEmpty { null }
    }

    /**
     * The option lists, rebuilt only when their inputs change: what he owns (money moves with it) and
     * his last intent at once; the need levels he sees, the 10-minute step of the clock (sleep's
     * estimate is rounded to it) and the music left only between activities. Only then does a model
     * read them; during one, the screens keep the lists from its start. Building them is most of a
     * tick's cost, and the levels change every few game minutes.
     */
    private val current: Options
        get() {
            val key = listOf(version, last?.intent ?: Unit)
            val moment = listOf(clock.minutes(ticks) / ESTIMATE_MINUTES, needs.levels(), hearable())
            options?.let { if (it.key == key && (it.moment == moment || running != null)) return it }
            memo = Memo()
            try {
                val activities = content.verbs.filter { isKnown(it.id) }.flatMap(::activityOptions)
                val shop = shopItems().filter { heldBack(it) == null }.map(::shopOption)
                return Options(key, moment, activities, shop, catalog(shop), verbViews(activities)).also { options = it }
            } finally {
                memo = null
            }
        }

    /** For the options' key: which `hear` verbs the music still has time for, which can change between the clock's 10-minute steps. */
    private fun hearable(): List<Boolean> = music?.let { m ->
        val left = (m.endTick - ticks) / clock.ticksPerMinute
        content.verbs.filter { v -> v.ops<HearOp>().any { it.background == m.spec.name } }.map { left >= (it.minutes ?: 0) }
    }.orEmpty()

    /** The book that teaches [id], while he doesn't know it: "the drawing book, \"Drawing What You See\"". */
    private fun taughtBy(id: String): String? = if (isKnown(id)) null else
        content.books.firstOrNull { id in it.teaches }?.let { "the ${item(it.id).name}, \"${it.title}\"" }

    /** Every priced item for screens, reusing [shop]'s wording for what the shop offers. */
    private fun catalog(shop: List<ShopOption>): List<CatalogEntry> {
        val offered = shop.associateBy { it.id }
        return content.items.filter { it.price != null }.map { spec ->
            val o = offered[spec.id] ?: shopOption(spec)
            val gives = heldBack(spec)?.takeIf { isKnown(spec.id) }?.let { "not sold to you until you have ${wanted(it)}; ${o.gives}" } ?: o.gives
            CatalogEntry(spec.id, spec.name, spec.group, spec.price!!, spec.pack, spec.stackable, count(spec.id), o.affordable, gives, taughtBy(spec.id))
        }
    }

    /** Every verb for screens: its first open option from [activities], or why it's locked. */
    private fun verbViews(activities: List<ActivityOption>): List<VerbView> {
        val byVerb = activities.groupBy { it.id }
        return content.verbs.map { a ->
            val taught = taughtBy(a.id)
            val o = byVerb[a.id]?.firstOrNull { it.locked == null } ?: byVerb[a.id]?.firstOrNull()
            when {
                taught != null -> VerbView(a.id, a.phrase, "a book has to teach it first", null, taught)
                o == null -> VerbView(a.id, a.phrase, lockReason(a) ?: "not offered", null)
                else -> VerbView(a.id, a.phrase, o.locked, if (o.locked != null) null else listOfNotNull(o.takes, o.gives?.ifEmpty { null }).joinToString(": "))
            }
        }
    }

    /**
     * The map around him, one string per row, with owned furniture as its slot letter (legend in
     * [mapLegend]): the whole room he's in, or outdoors a window of the land around him. Its top-left
     * tile is [mapRect]'s.
     */
    private var mapCache: Pair<Pair<Int, Cell>, Pair<Rect, List<String>>>? = null

    private fun roomMap(): Pair<Rect, List<String>> {
        val key = version to guy.cell
        mapCache?.let { if (it.first == key) return it.second }
        return drawRoomMap().also { mapCache = key to it }
    }

    private fun mapRect(): Rect {
        val here = layout.placeOf(guy.cell)
        if (here?.kind == PlaceKind.Room) return here.bounds
        val c = guy.cell
        return Rect(maxOf(0, c.x - OUTDOOR_MAP / 2), maxOf(0, c.y - OUTDOOR_MAP / 3), minOf(map.width - 1, c.x + OUTDOOR_MAP / 2), minOf(map.height - 1, c.y + OUTDOOR_MAP / 3))
    }

    private fun drawRoomMap(): Pair<Rect, List<String>> {
        val rect = mapRect()
        // Things on the land (lookouts, patches he knows of) are 'o'; ones he hasn't found aren't drawn.
        val letters = placed().filter { (spec, _) -> isKnown(spec.id) }.associate { (spec, cell) -> cell to (letterOf(spec.slot!!) ?: 'o') }
        return rect to (rect.y0..rect.y1).map { y ->
            buildString {
                for (x in rect.x0..rect.x1) {
                    val cell = Cell(x, y)
                    append(
                        when {
                            cell == guy.cell -> '@'
                            cell in letters -> letters.getValue(cell)
                            else -> layout.rows[y][x]
                        },
                    )
                }
            }
        }
    }

    private fun letterOf(slot: String): Char? = content.room.slots.entries.firstOrNull { it.value == slot }?.key?.single()

    /** "@ you, # wall, . floor, b bed, ..." */
    val mapLegend: String
        get() = "@ you, # wall, . floor, " + (if (layout.outdoors) "+ doorway, , grass, = path, : bare ground, * flowers, ~ water, T tree, o something outdoors you know of, " else "") + content.room.slots.entries.mapNotNull { (letter, slot) ->
            val spec = content.items.firstOrNull { it.slot == slot }
            // A spare spot is nothing until something he invented stands in it.
            if (spec == null && slot in content.room.spots.let { it.floor + it.wall }) null else "$letter ${spec?.name ?: slot}"
        }.joinToString() + " (furniture shows only once you own it)"

    companion object {
        /** How many tiles across the map of the land round him is, outdoors. */
        private const val OUTDOOR_MAP = 16
        /** The prefix of a drawn subject in [tried]. */
        private const val SUBJECT = "subject:"
        private const val SNAPSHOT_TRANSACTIONS = 8
        /** Authored ops kept in the observation: enough for any the agent hasn't settled yet. */
        private const val AUTHORED_KEPT = 16
        /** Duration estimates are rounded to this many minutes, so the option lists change less often. */
        private const val ESTIMATE_MINUTES = 10
        /** How far back [lately] looks, in game days. */
        private const val LATELY_DAYS = 2

        /** The eight tiles round one, straight sides first: where he stands to set a thing down. */
        private val NEXT_TO = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        /** What his code may store state on besides his things: himself. */
        const val SELF = "self"
        private const val MAX_TEXT = 200
        private const val MAX_KEYS = 20
        private const val MAX_STATEFUL = 200
        /** Hours in a row a thing's code may break before it stops. */
        /** What a thing's code wears by, before its id. */
        private const val THING_WEAR = "thing:"
        /** At this freshness or more, as shown to a tenth, what he does reads "fresh to you"; below it, "familiar". */
        private const val FRESH = 0.5

        /** Whether [fresh] reads "fresh to you": judged as [percent] shows it, so "50%" is never "familiar". */
        private fun isFresh(fresh: Double): Boolean = (fresh * 10).roundToInt() >= (FRESH * 10).roundToInt()

        /** How a lock for want of light begins. */
        private const val DARK = "too dark to "

        /** Entity states for the renderer: visitors round a thing that draws them, and a planter ready to pick. */
        const val VISITED = "visited"
        const val RIPE = "ripe"

        /** From here (19:00) on, or at night, what would spoil his sleep is said. */
        private const val EVENING = 19 * 60

        /** His lantern's emitter id. */
        private const val LANTERN = "lantern"

        /** What the light phrases name first: the things he does things at, before the decor. */
        private val KEY_GROUPS = setOf("furniture", "fixture", "tool")
        private val HEX = Regex("#[0-9a-fA-F]{6}")
        private val NAME = Regex("[\\p{L}\\p{N} '\\-]+")
        private val LOG_KEY = Regex("[A-Za-z0-9_]{1,24}")
        private val DESIGN_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** A plain Kotlin value (from [ScriptSandbox.fromJs]) as JSON. */
        private fun toJson(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is String -> kotlinx.serialization.json.JsonPrimitive(v)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            is Number -> kotlinx.serialization.json.JsonPrimitive(v)
            is Map<*, *> -> kotlinx.serialization.json.JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
            is List<*> -> kotlinx.serialization.json.JsonArray(v.map(::toJson))
            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
        }

        /** "60%", to the nearest 10. */
        private fun percent(fresh: Double): String = "${(fresh * 10).roundToInt() * 10}%"

        private fun oneDecimal(v: Double): String = "%.1f".format(v)

        /**
         * A world of [content] with only tier 0 and the [granted] ids in it; [BedroomWorld.content]
         * is that unlocked part.
         */
        fun create(
            content: BedroomContent = BedroomContent.default(),
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
            characterId: String = "guy",
            granted: Set<String> = emptySet(),
            /** Picks the commissions; the same seed gives the same requests. */
            seed: Long = 0,
        ): BedroomWorld = build(content.unlocked(granted), ticksPerMinute, characterId, seed)

        /**
         * A world made as [create] makes it, then set to [state]: a checkpoint carried on. Ids the
         * content no longer has are dropped and listed ([Restored.dropped]); with [strict], any drop
         * is refused instead.
         */
        fun restore(
            state: BedroomState,
            content: BedroomContent = BedroomContent.default(),
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
            characterId: String = "guy",
            granted: Set<String> = emptySet(),
            seed: Long = 0,
            strict: Boolean = false,
        ): Restored {
            require(state.version in 1..BedroomState.VERSION) { "checkpoint is version ${state.version}; this build reads 1 to ${BedroomState.VERSION}" }
            val world = create(content, ticksPerMinute, characterId, granted, seed)
            val dropped = world.load(state)
            require(!strict || dropped.isEmpty()) { "checkpoint doesn't match the content: ${dropped.joinToString("; ")}" }
            return Restored(world, dropped)
        }

        private fun build(content: BedroomContent, ticksPerMinute: Int, characterId: String, seed: Long): BedroomWorld {
            val layout = Layout.of(content)
            val guy = Character(EntityId(characterId), layout.start.x + 0.5f, layout.start.y + 0.5f)
            val clock = GameClock(ticksPerMinute, GameClock.parseHhmm(content.start.time))
            return BedroomWorld(layout, guy, content, clock, seed)
        }
    }
}
