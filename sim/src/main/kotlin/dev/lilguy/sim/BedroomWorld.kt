package dev.lilguy.sim

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
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
    map: TileMap,
    guy: Character,
    val content: BedroomContent,
    val clock: GameClock,
    /** Slot name to its tile. */
    private val slotCells: Map<String, Cell>,
    /** Picks the commissions. */
    private val seed: Long,
) : GridWorld(map, guy) {
    val ledger = Ledger(content.start.money, clock)
    val needs = Needs(content.start.needs, clock)

    /** Item id to count; only counts above zero. */
    private val owned = LinkedHashMap<String, Int>()
    private var doing: Doing? = null

    /** Wear per habit ([habit]): its level and the tick it was set; it fades from there. */
    private val wear = mutableMapOf<String, Pair<Double, Long>>()

    /** Habits he has finished at least once, for the novelty bonus. */
    private val tried = mutableSetOf<String>()

    /** Skill name to level, from 0 up. */
    private val skills = LinkedHashMap<String, Double>()

    /** Book id to how many chapters he has read. */
    private val chapters = mutableMapOf<String, Int>()

    /** What books have taught him ([BedroomContent.taught]); read from the agent's thread through [knownContent]. */
    @Volatile private var known: Set<String> = emptySet()
    @Volatile private var knownCache: Pair<Set<String>, BedroomContent>? = null

    private var music: Music? = null

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

    /** Authored ops whose verb succeeded, newest last ([AuthoredView]); the oldest drop off. */
    private val authored = ArrayDeque<AuthoredView>()
    private var authoredCount = 0
    private val deliveries = ConcurrentLinkedQueue<Pair<Int, AuthoredResult>>()
    /** Shopping trips' picks as they arrive ([stage]), and as staged for each trip verb until its checkout. */
    private val staging = ConcurrentLinkedQueue<Pair<String, AuthoredResult.Basket>>()
    private val baskets = mutableMapOf<String, AuthoredResult.Basket>()
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
            return "$${sale.sumOf { it.second }} for the ${counts.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }} you hold$bonus"
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

        override fun hangLock(tag: String): String? {
            if (countTag(tag) == 0) return "you have no ${noun(tag)} to hang"
            if (hung().size >= content.gallery.max) return "your wall is full (${content.gallery.max} pieces)"
            return null
        }

        override fun hang(tag: String) = this@BedroomWorld.hang(tag)

        override fun hangText(tag: String) = listOfNotNull(held(tag).lastOrNull()?.let { "hangs ${pieceName(it)}" }) +
            "comfort +${content.gallery.comfortEach}: mood settles ${content.gallery.comfortEach} higher (${hung().size} of ${content.gallery.max} on the wall)"

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
        spec.slot?.let { spec to slotCells.getValue(it) }
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

    private fun comfort(): Int = placed().sumOf { (spec, _) -> comfortOf(spec) } + hung().size * content.gallery.comfortEach

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
    private fun price(spec: ItemSpec, level: Double): Int? = spec.sellPrice?.let { (it * (1 + content.skills.pricePerLevel * level)).roundToInt() }

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
        if (a.isRoutine) return 1.0
        val (level, since) = wear[wearKey(a, args)] ?: return 1.0
        val hours = (ticks - since).toDouble() / clock.ticks(60)
        return 1.0 - (level - h.recoverPerHour * hours).coerceIn(0.0, 1.0)
    }

    private fun isNew(a: VerbSpec, args: Map<String, String> = emptyMap()): Boolean = content.novelty != null && !a.isRoutine && habit(a, args) !in tried

    /** The first-time bonus, scaled by his curiosity. */
    private fun novelty(): Map<String, Double> = content.novelty!!.effects.mapValues { it.value * content.personality.curiosity }

    /** [d] as paid at [fresh]: gains in mood and stimulation shrink with wear; costs and other needs don't. */
    private fun paid(need: Need, d: Double, fresh: Double): Double = if (d > 0 && (need == Need.Mood || need == Need.Stimulation)) d * fresh else d

    /** Wears the habit, charges stimulation if it was already worn, and pays the novelty bonus the first time. */
    private fun habituate(a: VerbSpec, args: Map<String, String>, fresh: Double, parts: MutableList<String>) {
        val key = habit(a, args)
        content.habituation?.takeIf { !a.isRoutine }?.let { h ->
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
        get() = content.verbs.filter { (it.needEffects[Need.Fullness.id] ?: 0.0) > 0 }.flatMap { v -> v.ops<ConsumeOp>().flatMap { it.items.keys } }.distinct()

    /** [a]'s daytime changes, when it has some and it isn't night now. */
    private fun byDay(a: VerbSpec): VerbSpec.ByDay? = a.byDay?.takeIf { content.night?.covers(clock.minuteOfDay(ticks)) == false }

    /** "an easel ($120)", "a meal (3 for $18)", "a sketchbook". The cheapest item with [tag] for sale, if any. */
    private fun wanted(tag: String): String {
        val options = content.withName(tag)
        val spec = options.filter { it.price != null }.minByOrNull { it.price!! } ?: options.first()
        val price = spec.price ?: return article(if (options.size > 1) noun(tag) else spec.name)
        val name = if (options.size > 1 && tag != spec.id) noun(tag) else spec.name
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
        is Intent.MoveTo -> walkTo(goalOf(intent.target), describe(intent.target))
        is Intent.PickUp -> reject("there's nothing to pick up here: what you own is already yours, and do and buy fetch it for you")
        is Intent.Use -> reject("use is for other rooms; here an item is used through a verb, do(verb, what, on)" + (content.verbs.firstOrNull { "on" in it.args }?.let { "; ${it.id} puts a ${noun(it.args.getValue("what"))} on the ${noun(it.args.getValue("on"))}" } ?: ""))
        is Intent.Say -> say(intent)
        is Intent.Wait -> waitFor(intent)
        is Intent.Do -> planDo(intent)
        is Intent.Buy -> planBuy(intent.item)
        Intent.Think -> reject("think is for other rooms; here it's an activity, do(think)")
    }

    private fun goalOf(target: Target): Cell = when (target) {
        is Target.Tile -> {
            val cell = Cell(target.x, target.y)
            if (!map.inBounds(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is outside the room")
            if (!passable(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is a wall or furniture")
            cell
        }
        is Target.Entity -> {
            if (target.id == guy.id.value) reject("you can't move to yourself")
            placed().firstOrNull { it.first.id == target.id }?.second ?: reject("there is nothing called '${target.id}' in the room")
        }
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
            acc.flatMap { b -> owned.keys.filter { type in item(it).names }.map { b + (slot to it) } }
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
    }

    private fun complete(a: VerbSpec, args: Map<String, String>): String? {
        val b = bound(a, args)
        a.effects.firstNotNullOfOrNull { it.gone(ops, b) }?.let { return it }
        val fresh = b.fresh
        val parts = a.effects.flatMapTo(mutableListOf()) { it.finish(ops, b) }
        a.stopBelow.forEach { (need, level) -> if (needs[needOf(need)] < level) parts += "stopped early: $need ${needs.level(needOf(need))}" }
        habituate(a, args, fresh, parts)
        content.frustration?.takeIf { !a.isRoutine }?.let { f ->
            val eased = needs.change(ticks, Need.Frustration, f.perSuccess, "did ${a.id}")
            if (eased != 0.0) parts += "frustration ${signed(eased.roundToInt().toDouble())}"
        }
        outcome("finished ${a.id}" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", "))
        done = done + (a.id to (done[a.id]?.let { it.copy(count = it.count + 1) } ?: VerbTally(1, ticks)))
        return null
    }

    private fun sell(tag: String): String {
        val sale = saleOf(held(tag))
        val sold = take(tag, countTag(tag))
        sale.forEach { (p, _) -> p.state = PieceState.Sold }
        val total = sale.sumOf { it.second }
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
        if (result is AuthoredResult.Basket) staging.add(verb to result)
    }

    private fun applyNotes() {
        while (true) {
            val (verb, basket) = staging.poll() ?: break
            baskets[verb] = basket
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
        val comfortNote = if (comfortOf(spec) != 0) "; comfort ${comfort()}, so mood settles toward ${moodTarget().toInt()}" else ""
        return "bought $what for $$price (money $${ledger.balance})$comfortNote"
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
        content.commissions?.let { if (minute == GameClock.parseHhmm(it.arrive)) newCommission(it) }
        if (minute == 0) event("day ${clock.day(ticks)} begins")
    }

    /** One game minute of the need rules; every rate is per hour. */
    private fun applyMinute() {
        val r = content.needs
        val d = doing
        val rate = ops::rate
        if (d == null || d.activity.effects.all { it.awake }) {
            rate(Need.Energy, r.awakeEnergyPerHour, "awake")
            rate(Need.Stimulation, r.stimulationPerHour * content.personality.curiosity, "awake")
        }
        val asleep = d != null && d.activity.effects.any { !it.awake }
        rate(Need.Fullness, if (asleep) r.asleepFullnessPerHour ?: r.fullnessPerHour else r.fullnessPerHour, "hunger")
        content.frustration?.let { rate(Need.Frustration, it.perHour, "fading") }
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

    // --- what the agent and the renderer see ----------------------------------------------------

    override fun stamp(tick: Long): String = "d${clock.day(tick)} ${GameClock.hhmm(clock.minuteOfDay(tick))}"

    private fun shopItems(): List<ItemSpec> = content.items.filter { it.price != null && isKnown(it.id) && (it.stackable || count(it.id) == 0) }

    /** What a shopping trip looks through: everything the shop has for him, held back or not. */
    private fun tripItems(): List<ItemSpec> = shopItems()

    /** One option per way to bind [a]'s arguments that isn't locked, or one locked option saying why. */
    private fun activityOptions(a: VerbSpec): List<ActivityOption> {
        lockReason(a)?.let { return listOf(ActivityOption(a.id, locked = it)) }
        val open = bindings(a).map { it to lockReason(a, it) }
        if (open.none { it.second == null }) return listOf(ActivityOption(a.id, locked = open.first().second))
        return open.filter { it.second == null }.map { (args, _) -> ActivityOption(a.id, args["what"], args["on"], takes(a), gives(a, args = args)) }
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
        if (comfort > 0) parts += "comfort +$comfort: mood settles $comfort higher"
        if (comfort < 0) parts += "comfort $comfort: you don't like it, so mood settles ${-comfort} lower"
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
        }?.let { parts += "leaves you $$left with no food, short of ${wanted(it)}" }
        return ShopOption(spec.id, spec.price!!, ledger.canAfford(spec.price), parts.joinToString("; "))
    }

    private fun musicText(): String? = music?.let { "${it.itemId}, ${GameClock.duration(((it.endTick - ticks) / clock.ticksPerMinute).toInt().coerceAtLeast(1))} left" }

    override fun publish(): Published {
        val entities = buildList {
            add(EntitySnapshot(guy.id, "guy", guy.x, guy.y, 1f, guy.facing.degrees))
            placed().forEach { (spec, cell) ->
                val state = if (spec.id == music?.on) "playing ${music!!.itemId}" else null
                add(EntitySnapshot(EntityId(spec.id), spec.group, cell.x + 0.5f, cell.y + 0.5f, if (spec.blocks) 1f else 0.2f, 0f, state))
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
            verbs = current.verbs,
            done = done,
        )
        val snapshot = WorldSnapshot(ticks, map, entities, listOf(agentSnapshot()), eventLog(), life = life)
        return Published(snapshot, observe(entities))
    }

    private fun observe(entities: List<EntitySnapshot>): Observation {
        val nearby = entities.filter { it.id != guy.id }.map { e ->
            val dx = e.x - guy.x
            val dy = e.y - guy.y
            val cell = Cell(e.x.toInt(), e.y.toInt())
            SeenEntity(e.id.value, e.kind, cell.x, cell.y, Direction.of(dx, dy), round(hypot(dx, dy), 1), e.state, chebyshev(guy.cell, cell) <= REACH)
        }.sortedBy { it.distance }
        return Observation(
            tick = ticks,
            self = SelfState(guy.id, round(guy.x, 2), round(guy.y, 2), guy.facing, guy.inventory.toList()),
            nearby = nearby,
            localMap = roomMap(),
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
            authored = authored.toList(),
            outcome = last?.outcome,
            news = news(),
        )
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

    /** The whole room, one string per row, with owned furniture as its slot letter; legend in [mapLegend]. */
    private var mapCache: Pair<Pair<Int, Cell>, List<String>>? = null

    private fun roomMap(): List<String> {
        val key = version to guy.cell
        mapCache?.let { if (it.first == key) return it.second }
        return drawRoomMap().also { mapCache = key to it }
    }

    private fun drawRoomMap(): List<String> {
        val letters = placed().associate { (spec, cell) -> cell to letterOf(spec.slot!!) }
        return (0 until map.height).map { y ->
            buildString {
                for (x in 0 until map.width) {
                    val cell = Cell(x, y)
                    append(
                        when {
                            cell == guy.cell -> '@'
                            cell in letters -> letters.getValue(cell)
                            else -> when (map[x, y]) { Terrain.Floor -> '.'; Terrain.Wall -> '#'; Terrain.Void -> ' ' }
                        },
                    )
                }
            }
        }
    }

    private fun letterOf(slot: String): Char = content.room.slots.entries.first { it.value == slot }.key.single()

    /** "@ you, # wall, . floor, b bed, ..." */
    val mapLegend: String
        get() = "@ you, # wall, . floor, " + content.room.slots.entries.joinToString { (letter, slot) ->
            val spec = content.items.firstOrNull { it.slot == slot }
            "$letter ${spec?.name ?: slot}"
        } + " (furniture shows only once you own it)"

    companion object {
        private const val SNAPSHOT_TRANSACTIONS = 8
        /** Authored ops kept in the observation: enough for any the agent hasn't settled yet. */
        private const val AUTHORED_KEPT = 16
        /** Duration estimates are rounded to this many minutes, so the option lists change less often. */
        private const val ESTIMATE_MINUTES = 10

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

        private fun build(content: BedroomContent, ticksPerMinute: Int, characterId: String, seed: Long): BedroomWorld {
            val rows = content.room.rows
            require(rows.isNotEmpty() && rows.all { it.length == rows[0].length }) { "room rows must all be the same length" }
            val height = rows.size
            val width = rows[0].length
            var guy: Character? = null
            val slotCells = mutableMapOf<String, Cell>()
            val terrain = Array(width * height) { i ->
                val x = i % width
                val y = i / width
                when (val c = rows[y][x]) {
                    '#' -> Terrain.Wall
                    ' ' -> Terrain.Void
                    '.' -> Terrain.Floor
                    '@' -> Terrain.Floor.also { guy = Character(EntityId(characterId), x + 0.5f, y + 0.5f) }
                    else -> {
                        val slot = content.room.slots[c.toString()] ?: error("unknown room tile '$c' at ($x, $y)")
                        require(slot !in slotCells) { "slot '$slot' appears twice in the room" }
                        slotCells[slot] = Cell(x, y)
                        if (slot in content.room.wallSlots) Terrain.Wall else Terrain.Floor
                    }
                }
            }
            val missing = content.room.slots.values.toSet() - slotCells.keys
            require(missing.isEmpty()) { "slots $missing aren't in the room" }
            val clock = GameClock(ticksPerMinute, GameClock.parseHhmm(content.start.time))
            return BedroomWorld(TileMap(width, height, terrain), guy ?: error("room has no '@'"), content, clock, slotCells, seed)
        }
    }
}
