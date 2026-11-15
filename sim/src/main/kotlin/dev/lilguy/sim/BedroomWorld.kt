package dev.lilguy.sim

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
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

    /** What his things do by themselves, item id to its `hourly` code; replaced rather than changed. */
    @Volatile var behaviors: Map<String, ThingCode> = emptyMap()
        private set
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
        override fun priceOf(id: String) = price(item(id), item(id).sellSkill?.let(::skill) ?: 0.0)
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
            return "$${sale.sumOf { it.second } + counts.entries.sumOf { (id, n) -> plainPrice(id) * n }} for the ${counts.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }} you hold$bonus"
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
            if (!ledger.canAfford(r.cheapest)) return "materials cost at least $${r.cheapest} and you have $${ledger.balance}"
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
            return when (val checked = content.invent!!.check(design, content, verb.minutes!!)) {
                is InventRules.Checked.Refused -> "the ${design.name.ifBlank { "design" }} didn't work: " + checked.problems.joinToString("; ")
                is InventRules.Checked.Ok -> checked.invention.cost.takeIf { !ledger.canAfford(it) }?.let { "the materials for the ${checked.invention.item.name} cost $$it and you have $${ledger.balance}" }
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

        override fun build(verb: VerbSpec): List<String> {
            val staged = designs.remove(verb.id)!!
            val design = staged.design!!
            val made = (content.invent!!.check(design, content, verb.minutes!!) as InventRules.Checked.Ok).invention.copy(tick = ticks)
            val spec = made.item
            this@BedroomWorld.content = content.plus(made)
            inventions = inventions + made
            knownCache = null
            ledger.debit(ticks, made.cost, "materials for the ${spec.name}")
            add(spec.id, 1)
            val like = spec.qualities.entries.joinToString { (q, s) -> "$q ${s.toInt()}" }
            val comfortNote = if (comfortOf(spec) != 0) "comfort ${comfort()}, so mood settles toward ${moodTarget().toInt()}" else null
            val newNote = newThingRate(spec).takeIf { it > 0 }?.let { newThings[spec.id] = ticks; "new: ${newThingText(it)}" }
            return listOfNotNull(
                "built the ${spec.name}, ${article(spec.group)} of your own design" + if (like.isEmpty()) "" else " ($like)",
                "materials $${made.cost} (money $${ledger.balance})",
                spec.price?.let { "the shop will send more for $$it" },
                spec.sellPrice?.let { "it sells for $$it" },
                comfortNote,
                newNote,
            ) + (
                // What it does, written with it: its verbs and its behavior, now that it has an id.
                staged.code?.takeIf { !it.isEmpty }?.let { putCode(it.about(spec.id)) }
                    ?: listOfNotNull(staged.codeFailed?.let { "you didn't get its code written ($it), so it doesn't do anything yet" })
                )
        }

        override fun buildText(): String {
            val r = content.invent ?: return "design and build a new thing"
            return "design a new thing of your own and build it: ${r.uses.keys.joinToString(" or ") { useNoun(it) }}; materials from $${r.cheapest}"
        }

        override fun hangLock(tag: String): String? {
            if (countTag(tag) == 0) return "you have no ${noun(tag)} to hang"
            if (hung().size >= content.gallery.max) return "your wall is full (${content.gallery.max} pieces)"
            return null
        }

        override fun hang(tag: String) = this@BedroomWorld.hang(tag)

        override fun hangText(tag: String) = listOfNotNull(held(tag).lastOrNull()?.let { "hangs ${pieceName(it)}" }) +
            fits(content.gallery.comfortEach).let { c ->
                if (c > 0) "comfort +$c: mood settles $c higher (${hung().size} of ${content.gallery.max} on the wall)"
                else "comfort +0: ${fullText()} (${hung().size} of ${content.gallery.max} on the wall)"
            }

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
        owned.entries.sumOf { (id, n) -> if (tag in item(id).names) n else 0 } + if (extra != null && tag in item(extra).names) 1 else 0

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

    /** Everything in view, as if the house held all of it. */
    private fun rawComfort(): Int = placed().sumOf { (spec, _) -> comfortOf(spec) } + hung().size * content.gallery.comfortEach + collectionComfort() + roomsComfort()

    /** [rawComfort], up to what the house holds ([ComfortRules]). */
    private fun comfort(): Int = rawComfort().let { raw -> holds()?.let { minOf(raw, it) } ?: raw }

    /** How much comfort the house holds now, more for each room he has built; null with no limit. */
    private fun holds(): Int? = content.comfort?.let { it.holds + it.perRoom * house.size }

    /** How much of [c] more comfort the house has room for now. */
    private fun fits(c: Int): Int {
        val h = holds() ?: return c
        return if (c <= 0) c else minOf(c, (h - rawComfort()).coerceAtLeast(0))
    }

    /** "comfort 30, so mood settles toward 80", saying when the house is full and [fit] is false (what was added didn't fit). */
    private fun comfortText(fit: Boolean = true): String =
        "comfort ${comfort()}" + (if (!fit) ", all ${fullText().removePrefix("your ")}" else "") + ", so mood settles toward ${moodTarget().toInt()}"

    /** "your house is full (it holds 35 comfort; each room you build holds 10 more)". */
    private fun fullText(): String {
        val r = content.comfort!!
        val rooms = if (r.perRoom > 0 && content.world?.rooms?.isNotEmpty() == true) "; each room you build holds ${r.perRoom} more" else ""
        return "your house is full (it holds ${holds()} comfort$rooms)"
    }

    /** What the rooms he has built are like to him: their qualities, by his affinities, as decor's are. */
    private fun roomsComfort(): Int = house.keys.sumOf { id ->
        val r = content.world?.room(id) ?: return@sumOf 0
        (r.qualities.entries.sumOf { (q, s) -> s * content.personality.affinity(q) } * content.qualityValue.inView).roundToInt()
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
            val was = behaviors[b.thing]
            behaviors = behaviors + (b.thing to ThingCode(b.code, b.says.trim(), (was?.version ?: 0) + 1, ticks))
            parts += "the ${item(b.thing).name} " + (if (was != null) "does something new by itself: " else "now does something by itself: ") + b.says.trim()
        }
        version++
        return parts
    }

    /** Each game hour: every thing he owns that has a behavior runs its `hourly`; what it does is a world event. Three broken hours in a row stop it. */
    private fun runBehaviors() {
        behaviors.forEach { (id, code) ->
            if (count(id) == 0 || content.item(id) == null) return@forEach
            val spec = item(id)
            val view = ScriptView("your ${spec.name}")
            val run = sandbox.call(code.source, ScriptSandbox.HOURLY, view, emptyMap(), id, extra = view.thing(id))
            val said = run.says.map { "\"$it\"" }
            if (run.error != null) {
                val failures = code.failures + 1
                val stop = failures >= BROKEN_HOURS
                behaviors = if (stop) behaviors - id else behaviors + (id to code.copy(failures = failures))
                val undone = view.undo()
                event(
                    "your ${spec.name}'s code broke: ${run.error}" + (if (undone.isEmpty()) "" else "; what it did before that is undone (${undone.joinToString(", ")})") +
                        if (stop) "; after $BROKEN_HOURS hours of that it stopped, until you write it again" else "",
                )
                return@forEach
            }
            if (code.failures > 0) behaviors = behaviors + (id to code.copy(failures = 0))
            val what = view.did + said + listOfNotNull(run.result?.takeIf { it.isNotBlank() })
            if (what.isNotEmpty()) event("your ${spec.name}: " + what.joinToString(", "))
        }
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
            records.record(id, StationLog.TEMPERATURE, ticks, Weather.round1(c.temperature))
            records.record(id, StationLog.RAIN, ticks, Weather.round1(c.rain))
            records.record(id, StationLog.PRESSURE, ticks, Weather.round1(c.pressure))
            records.record(id, StationLog.LIGHT, ticks, Weather.round1(c.light))
        }
    }

    private fun outdoors(cell: Cell): Boolean = layout.placeOf(cell)?.kind == PlaceKind.Outdoor

    /** The weather at [cell] now; null without weather. */
    fun conditionsAt(cell: Cell): Conditions? = weather?.at(clock.minutes(ticks), outdoors(cell))

    /** How much of the sun gets through the sky now, 0 to 1: what a light engine multiplies daylight by. 1 without weather. */
    fun daylight(): Double = weather?.daylight(clock.minutes(ticks) / 60) ?: 1.0

    /** His mood's fall an hour while he's out in the cold, or null. */
    private fun coldRate(): Double? {
        val cold = weather?.rules?.cold ?: return null
        val c = conditionsAt(guy.cell)?.takeIf { it.outdoors } ?: return null
        return if (c.temperature < cold.below) cold.moodPerHour else null
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
    private inner class ScriptView(private val reason: String, private val paid: (String, Double) -> Double = { _, d -> d }, private val minutes: Int = 60) : ScriptWorld {
        val did = mutableListOf<String>()

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
            // Like any verb's, its gains in mood and stimulation shrink as it wears thin.
            val applied = needs.change(ticks, need, paid(name, delta), reason)
            if (applied.roundToInt() != 0) did += "$name ${signed(applied.roundToInt().toDouble())}"
            return (needs[need] * 10).roundToInt() / 10.0
        }

        override fun earn(amount: Int, why: String): Int {
            if (amount < 1) throw ScriptProblem("earn a whole number of dollars above 0")
            ledger.credit(ticks, amount, why.ifBlank { reason }.take(80))
            did += "+$$amount" + (why.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            version++
            return ledger.balance
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
            if (skill !in content.vocabulary.skills) throw ScriptProblem("'$skill' isn't a skill; they are ${content.vocabulary.skills.joinToString()}")
            did += practise(skill, amount)
            return skill(skill)
        }

        override fun give(id: String, n: Int): Int {
            val s = content.item(id)?.takeIf { isKnown(id) } ?: throw ScriptProblem("there's no kind of thing '$id'; world.catalog() lists them")
            if (s.carried) throw ScriptProblem("art comes from drawing it, not from code")
            if (s.group == "fixture") throw ScriptProblem("the ${s.name} is part of the house or the land")
            if (n !in 1..MAX_GIVE) throw ScriptProblem("give 1 to $MAX_GIVE at a time")
            add(id, n)
            did += "+${quantity(id, n)}"
            return count(id)
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
            val made = when (val checked = rules.check(d, content, minutes)) {
                is InventRules.Checked.Refused -> throw ScriptProblem("the ${d.name} can't be made: " + checked.problems.joinToString("; "))
                is InventRules.Checked.Ok -> checked.invention.copy(cost = 0, tick = ticks)
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

    /** 1 when fresh, down to 0 when worn out; always 1 for routine verbs or without habituation. */
    private fun freshness(a: VerbSpec, args: Map<String, String> = emptyMap()): Double {
        val h = content.habituation ?: return 1.0
        if (a.isRoutine || fixing(a)) return 1.0
        val (level, since) = wear[wearKey(a, args)] ?: return 1.0
        val hours = (ticks - since).toDouble() / clock.ticks(60)
        return 1.0 - (level - h.recoverPerHour * hours).coerceIn(0.0, 1.0)
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
        val gains = a.needEffects.entries + a.ops<RateOp>().flatMap { it.perHour.entries } + a.ops<BackgroundOp>().flatMap { it.rates.entries }
        fun gains(n: Need) = gains.any { it.key == n.id && it.value > 0 }
        return (gains(Need.Mood) || gains(Need.Stimulation)) && !gains(Need.Energy) && !gains(Need.Fullness)
    }

    /** [a]'s freshness when he's bored and it's worn past [BoredLock.worn], or null. Unbound (null [args]), a verb that wears per item leaves it to each binding. */
    private fun boredOf(a: VerbSpec, args: Map<String, String>?): Double? {
        val lock = content.habituation?.boredLock ?: return null
        if (needs[Need.Stimulation] >= lock.below || !boring(a)) return null
        if (args == null && a.args.isNotEmpty() && a.wear != Wear.Verb) return null
        // What he's never done is new, even when it wears with something he's tired of (a new book, read worn thin).
        if (isNew(a, args.orEmpty())) return null
        return freshness(a, args.orEmpty()).takeIf { 1 - it >= lock.worn }
    }

    /**
     * Why boredom refuses [a] now, or null. Work stays open while he can't afford anything new, since
     * buying something new is the way out of boredom and work is the way to the money.
     */
    private fun boredLock(a: VerbSpec, args: Map<String, String>?): String? {
        val fresh = boredOf(a, args) ?: return null
        if (a.work && !canAffordNew()) return null
        return "you're bored, and you've done it a lot lately (gains at ${percent(fresh)}, stimulation ${needs.level(Need.Stimulation)})"
    }

    /** Whether the shop has something new to him that he can afford: a thing for the room he likes, or something for a verb he hasn't done. */
    private fun canAffordNew(): Boolean = shopItems().any { spec ->
        heldBack(spec) == null && ledger.canAfford(spec.price!!) && ((feelsNew(spec) && newThingRate(spec) > 0) || usesOf(spec).any { isNew(it) })
    }

    /** What [spec], bought at [since], feeds stimulation an hour now ([NewThings]): 0 out of sight, once it's old, or if he doesn't like it. */
    private fun newThingRate(spec: ItemSpec, since: Long = ticks): Double {
        val t = content.novelty?.things ?: return 0.0
        if (spec.slot == null) return 0.0
        val left = 1 - (ticks - since).toDouble() / clock.ticks(60) / 24 / t.days
        return if (left <= 0) 0.0 else value(spec).coerceAtLeast(0.0) * t.stimulationPerValue * content.personality.curiosity * left
    }

    /** Whether [spec], bought now, would feel new: only while the house has room for its comfort ([ComfortRules]). */
    private fun feelsNew(spec: ItemSpec): Boolean = comfortOf(spec) <= 0 || fits(comfortOf(spec)) > 0

    /** "stimulation +2/h while you're awake, fading over 3 days". */
    private fun newThingText(rate: Double): String =
        "stimulation ${signed((rate * 10).roundToInt() / 10.0)}/h while you're awake, fading over ${signed(content.novelty!!.things!!.days).removePrefix("+")} days"

    /** [d] as paid at [fresh]: gains in mood and stimulation shrink with wear; costs and other needs don't. */
    private fun paid(need: Need, d: Double, fresh: Double): Double = if (d > 0 && (need == Need.Mood || need == Need.Stimulation)) d * fresh else d

    /** Wears the habit, charges stimulation if it was already worn, and pays the novelty bonus the first time. */
    private fun habituate(a: VerbSpec, args: Map<String, String>, fresh: Double, parts: MutableList<String>, wears: Boolean = true) {
        val key = habit(a, args)
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
        is Intent.MoveTo -> (intent.target as? Target.Place)?.let(::goTo) ?: walkTo(goalOf(intent.target), describe(intent.target))
        is Intent.PickUp -> reject("there's nothing to pick up here: what you own is already yours, and do and buy fetch it for you")
        is Intent.Use -> reject("use is for other rooms; here an item is used through a verb, do(verb, what, on)" + (content.verbs.firstOrNull { "on" in it.args }?.let { "; ${it.id} puts a ${noun(it.args.getValue("what"))} on the ${noun(it.args.getValue("on"))}" } ?: ""))
        is Intent.Say -> say(intent)
        is Intent.Wait -> waitFor(intent)
        is Intent.Do -> planDo(intent)
        is Intent.Buy -> planBuy(intent.item)
        is Intent.Put -> planPut(intent)
        Intent.Think -> reject("think is for other rooms; here it's an activity, do(think)")
    }

    private fun goalOf(target: Target): Cell = when (target) {
        is Target.Tile -> {
            val cell = Cell(target.x, target.y)
            if (!map.inBounds(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is off the map")
            if (map[cell.x, cell.y] == Terrain.Void) reject("(${cell.x}, ${cell.y}) is off the land")
            if (!passable(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is a wall, water, a tree or furniture")
            cell
        }
        is Target.Entity -> {
            if (target.id == guy.id.value) reject("you can't move to yourself")
            placed().firstOrNull { it.first.id == target.id }?.second ?: reject("there is nothing called '${target.id}' in the room")
        }
        is Target.Place -> error("planned by goTo")
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
            val id = given[slot] ?: return@mapValues owned.keys.first { type in item(it).names }
            val spec = content.item(id)
            if (spec == null || count(id) == 0) reject("you don't have '$id'")
            if (type !in spec.names) reject("${a.id} takes ${article(noun(type))} as `$slot`, and the ${spec.name} isn't one")
            id
        }
        lockReason(a, args)?.let { reject("can't ${a.id}: $it") }
        val places = a.at.mapNotNull { if (it == VerbSpec.HERE) null else args[it] ?: it }
        if (VerbSpec.HERE in a.at) return activity(a, guy.cell, args) { complete(a, args) }
        val (walk, at) = walkToNearest(places)
        return Work.Steps(walk, activity(a, at, args) { complete(a, args) })
    }

    /** Every way to bind [a]'s arguments to items he owns, in the order he owns them; one empty binding when it has none. */
    private fun bindings(a: VerbSpec): List<Map<String, String>> =
        a.args.entries.fold(listOf(emptyMap())) { acc, (slot, type) ->
            acc.flatMap { b -> owned.keys.filter { type in item(it).names && isKnown(it) }.map { b + (slot to it) } }
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
        return null
    }

    private fun sell(tag: String): String {
        val sale = saleOf(held(tag))
        val sold = take(tag, countTag(tag))
        sale.forEach { (p, _) -> p.state = PieceState.Sold }
        val total = sale.sumOf { it.second } + sold.entries.sumOf { (id, n) -> plainPrice(id) * n }
        val what = sold.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }
        val winner = winner(sale.map { it.first })
        val bonus = winner?.let {
            commission!!.fulfilled = true
            version++
            " (${pieceName(it)} met the request for ${commission!!.wants}: +$${content.commissions!!.reward})"
        }.orEmpty()
        if (total > 0) ledger.credit(ticks, total, "sold $what" + if (winner != null) " (one for a commission)" else "")
        return "sold $what for $$total$bonus (money $${ledger.balance})"
    }

    /** What one [id] sells for when it's a plain thing he owns and not a piece of art, which is priced as a piece; 0 otherwise. */
    private fun plainPrice(id: String): Int = item(id).takeIf { !it.carried }?.sellPrice ?: 0

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
        val new = feelsNew(spec)
        ledger.debit(ticks, price, "bought $what")
        add(spec.id, spec.pack)
        val comfortNote = if (comfortOf(spec) != 0) "; ${comfortText(new)}" else ""
        val newNote = newThingRate(spec).takeIf { new && it > 0 }?.let { newThings[spec.id] = ticks; "; new: ${newThingText(it)}" } ?: ""
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
        if (!clock.isMinuteStart(ticks)) return
        applyMinute()
        val minute = clock.minuteOfDay(ticks)
        if (minute % 60 == 0) needs.flush(ticks)
        if (minute % 60 == 0) weatherHour()
        if (minute % 60 == 0 && behaviors.isNotEmpty()) runBehaviors()
        content.commissions?.let { if (minute == GameClock.parseHhmm(it.arrive)) newCommission(it) }
        if (minute == 0) {
            event("day ${clock.day(ticks)} begins")
            correspond()
        }
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
            newThings.forEach { (id, since) -> rate(Need.Stimulation, newThingRate(item(id), since), "new: ${item(id).name}") }
        }
        newThings.entries.removeIf { (id, since) -> count(id) == 0 || newThingRate(item(id), since) <= 0 }
        val asleep = d != null && d.activity.effects.any { !it.awake }
        rate(Need.Fullness, if (asleep) r.asleepFullnessPerHour ?: r.fullnessPerHour else r.fullnessPerHour, "hunger")
        content.frustration?.let { rate(Need.Frustration, it.perHour, "fading") }
        coldRate()?.let { rate(Need.Mood, it, "cold") }
        d?.activity?.effects?.forEach { it.minute(ops, d.bound) }
        music?.let { m -> m.spec.rates.forEach { (need, perHour) -> rate(needOf(need), paid(needOf(need), perHour, m.fresh), m.spec.name) } }
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
            mail = mailbox.toList(),
            opened = opened.toList(),
            read = read.toList(),
            sent = sent.toList(),
            coded = coded,
            thingState = thingState.mapValues { LinkedHashMap(it.value) },
            custom = LinkedHashMap(custom),
            behaviors = behaviors,
            records = records.export(),
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
        return dropped
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
        content.habituation?.takeIf { fresh < 1 }?.let { h ->
            val stale = (h.staleStimulation * (1 - fresh)).roundToInt()
            parts += "done a lot lately: gains at ${percent(fresh)}" + if (stale < 0) ", and stimulation $stale" else ""
        }
        if (isNew(a, args)) parts += "new to you: " + novelty().entries.joinToString { (need, d) -> "$need ${signed(d.roundToInt().toDouble())}" }
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
        isKnown(a.id) && (spec.id in a.at || (a.requires + a.effects.flatMap { it.asksFor } + a.args.values).any { it in spec.names })
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
        usesOf(spec).forEach { a ->
            val locked = lockReason(a)
            val after = lockReason(a, extra = spec.id)
            val what = takes(a) + gives(a, forSale = true).let { if (it.isEmpty()) "" else ": $it" }
            parts += when {
                locked == null || locked == after -> "for ${a.id}"
                after == null -> "unlocks ${a.id} ($what)"
                else -> "for ${a.id} ($what), which also $after"
            }
        }
        val comfort = comfortOf(spec)
        val fit = fits(comfort)
        when {
            comfort > 0 && fit == 0 -> parts += "comfort +0: ${fullText()}, so it adds nothing and doesn't feel new"
            comfort > 0 && fit < comfort -> parts += "comfort +$fit: mood settles $fit higher (it's worth $comfort, but that's all the room your house has left)"
            comfort > 0 -> parts += "comfort +$comfort: mood settles $comfort higher"
            comfort < 0 -> parts += "comfort $comfort: you don't like it, so mood settles ${-comfort} lower"
        }
        if (feelsNew(spec)) newThingRate(spec).takeIf { it > 0 }?.let { parts += "new to you: ${newThingText(it)}" }
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
        val entities = buildList {
            add(EntitySnapshot(guy.id, "guy", guy.x, guy.y, 1f, guy.facing.degrees, place = layout.placeOf(guy.x, guy.y)?.id))
            placed().forEach { (spec, cell) ->
                val state = when {
                    spec.id == music?.on -> "playing ${music!!.itemId}"
                    (bareUntil[spec.id] ?: 0) > ticks -> "bare"
                    else -> null
                }
                add(EntitySnapshot(EntityId(spec.id), spec.group, cell.x + 0.5f, cell.y + 0.5f, if (spec.blocks) 1f else 0.2f, 0f, state, carriedBy = guy.id.takeIf { spec.id == carrying }, place = layout.placeOf(cell)?.id, color = custom[spec.id]?.color))
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
        val nearby = entities.filter { it.id != guy.id && (!layout.outdoors || it.place == here) && isKnown(it.id.value) }.map { e ->
            val dx = e.x - guy.x
            val dy = e.y - guy.y
            val cell = Cell(e.x.toInt(), e.y.toInt())
            SeenEntity(e.id.value, e.kind, cell.x, cell.y, Direction.of(dx, dy), round(hypot(dx, dy), 1), e.state, chebyshev(guy.cell, cell) <= REACH)
        }.sortedBy { it.distance }
        return Observation(
            place = whereText(),
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
        private const val MAX_GIVE = 100
        private const val MAX_TEXT = 200
        private const val MAX_KEYS = 20
        private const val MAX_STATEFUL = 200
        /** Hours in a row a thing's code may break before it stops. */
        private const val BROKEN_HOURS = 3
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
