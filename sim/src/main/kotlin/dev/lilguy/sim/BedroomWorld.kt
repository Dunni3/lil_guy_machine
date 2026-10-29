package dev.lilguy.sim

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

/**
 * Milestone 4 world: a bedroom where the guy makes art, sells it, and spends the money on food,
 * tools and things he likes. Everything he can do is a row in [content]; this class is the
 * engine that reads those rows. State changes only through five parts: the [clock], the money
 * [ledger], the [needs], what he owns, and the running activity (plus background music).
 *
 * do(activity) and buy(item) walk him to the right spot on their own; use(item, on) puts one
 * item on another (a record on the player). Decisions happen when an activity ends.
 */
class BedroomWorld private constructor(
    map: TileMap,
    guy: Character,
    val content: BedroomContent,
    val clock: GameClock,
    /** Slot name to its tile. */
    private val slotCells: Map<String, Cell>,
) : GridWorld(map, guy) {
    val ledger = Ledger(content.start.money, clock)
    val needs = Needs(content.start.needs, clock)

    /** Item id to count; only counts above zero. */
    private val owned = LinkedHashMap<String, Int>()
    private var doing: Doing? = null
    private var music: Music? = null

    private class Doing(val activity: ActivitySpec, val startTick: Long, val endTick: Long)
    private class Music(val itemId: String, val spec: ActivitySpec.Background, val endTick: Long)

    init {
        content.start.items.forEach { (id, n) -> add(id, n) }
        publishFirst()
    }

    // --- ownership -----------------------------------------------------------------------------

    fun count(id: String): Int = owned[id] ?: 0

    private fun item(id: String): ItemSpec = content.item(id) ?: error("no item '$id'")

    /** How many owned items carry [tag], plus one more [extra] if given (for "what if he bought it"). */
    private fun countTag(tag: String, extra: String? = null): Int =
        owned.entries.sumOf { (id, n) -> if (tag in item(id).allTags) n else 0 } + if (extra != null && tag in item(extra).allTags) 1 else 0

    private fun add(id: String, n: Int) {
        owned[id] = count(id) + n
        syncHolding()
    }

    /** Removes [n] items with [tag], first-listed first; returns what was taken. */
    private fun take(tag: String, n: Int): Map<String, Int> {
        val taken = LinkedHashMap<String, Int>()
        var left = n
        for (id in owned.keys.toList()) {
            if (left == 0) break
            if (tag !in item(id).allTags) continue
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
    private fun ownsMap(): Map<String, Int> = owned.filter { (id, _) -> item(id).let { !it.carried && it.kind != "fixture" } }

    /** Owned items that sit in a slot, with their tiles. */
    private fun placed(): List<Pair<ItemSpec, Cell>> = owned.keys.mapNotNull { id ->
        val spec = item(id)
        spec.slot?.let { spec to slotCells.getValue(it) }
    }

    private fun comfort(): Int = owned.entries.sumOf { (id, n) -> item(id).comfort * n }

    private fun moodTarget(): Double = (content.needs.moodBaseline + comfort()).coerceAtMost(Needs.MAX)

    override fun passable(x: Int, y: Int): Boolean = map.isFloor(x, y) && placed().none { (spec, cell) -> spec.blocks && cell.x == x && cell.y == y }

    // --- rules: what's possible ------------------------------------------------------------------

    /** Why [a] can't be done now, or null when it can. Also used for uses. [extra]: as if he also owned that item. */
    fun lockReason(a: ActivitySpec, extra: String? = null): String? {
        if (a.at.none { countTag(it, extra) > 0 }) return "needs ${wanted(a.at.first())}"
        a.requires.firstOrNull { countTag(it, extra) == 0 }?.let { return "needs ${wanted(it)}" }
        a.consumes.entries.firstOrNull { (tag, n) -> countTag(tag, extra) < n }?.let { return "needs ${wanted(it.key)}" }
        a.sells?.let { tag ->
            if (countTag(tag) == 0) {
                val makers = content.activities.filter { m -> m.produces.keys.any { tag in item(it).allTags } }.map { it.id }
                return "you have no $tag to sell" + if (makers.isEmpty()) "" else "; ${makers.joinToString(" or ")} makes some"
            }
        }
        val blocks = a.block + if (a.work) content.needs.workFloor else emptyList()
        blocks.firstOrNull { it.blocks(needs[needOf(it.need)]) }?.let { return "${it.reason} (${it.need} ${needs.level(needOf(it.need))})" }
        return null
    }

    /** "an easel ($120)", "a meal (3 for $18)", "a sketchbook". The cheapest item with [tag] for sale, if any. */
    private fun wanted(tag: String): String {
        val options = content.withTag(tag)
        val spec = options.filter { it.price != null }.minByOrNull { it.price!! } ?: options.first()
        val price = spec.price ?: return article(if (options.size > 1) tag else spec.name)
        val name = if (options.size > 1 && tag != spec.id) tag.replace('_', ' ') else spec.name
        return article(name) + if (spec.pack > 1) " (${spec.pack} for $$price)" else " ($$price)"
    }

    private fun article(name: String) = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name

    // --- intents ---------------------------------------------------------------------------------

    override fun plan(intent: Intent): Work = when (intent) {
        is Intent.MoveTo -> walkTo(goalOf(intent.target), describe(intent.target))
        is Intent.PickUp -> reject("there's nothing to pick up here: what you own is already yours, and do, use and buy fetch it for you")
        is Intent.Use -> planUse(intent)
        is Intent.Say -> say(intent)
        is Intent.Wait -> waitFor(intent)
        is Intent.Do -> planDo(intent.activity)
        is Intent.Buy -> planBuy(intent.item)
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

    /** A walk to the nearest of [ids] that he owns; the caller has checked there is one. */
    private fun walkToNearest(ids: List<String>): Work.Walk {
        val paths = placed().filter { it.first.id in ids }.mapNotNull { (_, cell) -> pathToward(cell) }
        val best = paths.minByOrNull { it.cost } ?: reject("there is no way to reach the ${ids.joinToString(" or ")}")
        return Work.Walk(ArrayDeque(best.cells))
    }

    private fun planDo(id: String): Work {
        val a = content.activity(id)
            ?: reject("there's no activity called '$id'" + (content.uses.find { it.id == id }?.let { "; it's done with use(${it.item}, on ${it.on})" } ?: "; the activities are ${content.activities.joinToString { it.id }}"))
        lockReason(a)?.let { reject("can't ${a.id}: $it") }
        return Work.Steps(walkToNearest(a.at), activity(a) { complete(a) })
    }

    private fun planUse(intent: Intent.Use): Work {
        val spec = content.item(intent.itemId)
        if (spec == null || count(spec.id) == 0) reject("you don't have '${intent.itemId}'")
        val rules = content.uses.filter { it.item in spec.allTags }
        if (rules.isEmpty()) reject("the ${spec.name} can't be used on anything")
        val on = intent.on ?: reject("use the ${spec.name} on something: ${rules.joinToString(" or ") { it.on!! }}")
        val rule = rules.find { it.on == on } ?: reject("using the ${spec.name} on $on does nothing; try ${rules.joinToString(" or ") { it.on!! }}")
        lockReason(rule)?.let { reject("can't use the ${spec.name} on $on: $it") }
        return Work.Steps(walkToNearest(rule.at), activity(rule) { complete(rule, usedItem = spec.id) })
    }

    private fun planBuy(id: String): Work {
        val spec = content.item(id)
        val price = spec?.price ?: reject("'$id' isn't for sale; the shop has ${shopItems().joinToString { it.id }}")
        if (!spec.stackable && count(id) > 0) reject("you already have the ${spec.name}")
        if (!ledger.canAfford(price)) reject("the ${spec.name} costs $$price and you have $${ledger.balance}")
        return Work.Steps(walkToNearest(listOf(content.shop.at)), Work.Timed(clock.ticks(content.shop.minutes)) { purchase(spec, price) })
    }

    /** The timed part of an activity: sets [doing] when it starts and ends by duration or `until`. */
    private fun activity(a: ActivitySpec, finish: () -> String?): Work.Until {
        val until = a.until
        val untilNeed = until?.need?.let(::needOf)
        val stops = a.stopBelow.mapKeys { needOf(it.key) }
        return Work.Until(
            start = {
                val length = if (until != null) clock.ticksUntil(ticks, GameClock.parseHhmm(until.time)) else clock.ticks(a.minutes!!).toLong()
                doing = Doing(a, ticks, ticks + length)
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

    private fun complete(a: ActivitySpec, usedItem: String? = null): String? {
        a.consumes.forEach { (tag, n) -> if (countTag(tag) < n) return "you no longer have ${wanted(tag)}" }
        val parts = mutableListOf<String>()
        a.consumes.forEach { (tag, n) -> take(tag, n).forEach { (id, k) -> parts += "used ${quantity(id, k)}" } }
        a.produces.forEach { (id, n) ->
            add(id, n)
            parts += "+${quantity(id, n)}"
        }
        a.effects.forEach { (need, d) ->
            val applied = needs.change(ticks, needOf(need), d, a.id)
            parts += "$need ${signed(applied)}"
        }
        a.sells?.let { parts += sell(it) }
        a.background?.let { bg ->
            music = Music(usedItem!!, bg, ticks + clock.ticks(bg.minutes))
            parts += "${item(usedItem).name} playing for ${GameClock.duration(bg.minutes)}"
        }
        if (a.rates.isNotEmpty()) parts += a.rates.keys.joinToString { "$it ${needs.level(needOf(it))}" }
        a.stopBelow.forEach { (need, level) -> if (needs[needOf(need)] < level) parts += "stopped early: $need ${needs.level(needOf(need))}" }
        event("finished ${a.id}" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", "))
        return null
    }

    private fun sell(tag: String): String {
        val sold = take(tag, countTag(tag))
        val total = sold.entries.sumOf { (id, n) -> (item(id).sellPrice ?: 0) * n }
        val what = sold.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }
        if (total > 0) ledger.credit(ticks, total, "sold $what")
        return "sold $what for $$total (money $${ledger.balance})"
    }

    private fun purchase(spec: ItemSpec, price: Int): String? {
        if (!ledger.canAfford(price)) return "the ${spec.name} costs $$price and you have $${ledger.balance}"
        if (!spec.stackable && count(spec.id) > 0) return "you already have the ${spec.name}"
        val what = quantity(spec.id, spec.pack)
        ledger.debit(ticks, price, "bought $what")
        add(spec.id, spec.pack)
        val comfortNote = if (spec.comfort > 0) "; comfort ${comfort()}, so mood settles toward ${moodTarget().toInt()}" else ""
        event("bought $what for $$price (money $${ledger.balance})$comfortNote")
        return null
    }

    private fun quantity(id: String, n: Int): String {
        val name = item(id).name
        return if (n == 1) "1 $name" else "$n " + if (name.endsWith("s") || name.endsWith("sh") || name.endsWith("ch")) "${name}es" else "${name}s"
    }

    // --- time and needs --------------------------------------------------------------------------

    override fun afterWork() {
        music?.let {
            if (ticks >= it.endTick) {
                music = null
                event("the ${item(it.itemId).name} finished")
            }
        }
        if (!clock.isMinuteStart(ticks)) return
        applyMinute()
        val minute = clock.minuteOfDay(ticks)
        if (minute % 60 == 0) needs.flush(ticks)
        if (minute == 0) event("day ${clock.day(ticks)} begins")
    }

    /** One game minute of the need rules; every rate is per hour. */
    private fun applyMinute() {
        val r = content.needs
        val activity = doing?.activity
        fun rate(need: Need, perHour: Double, reason: String) {
            if (perHour != 0.0) needs.change(ticks, need, perHour / 60, reason, rate = true)
        }
        if (activity?.asleep != true) rate(Need.Energy, r.awakeEnergyPerHour, "awake")
        rate(Need.Fullness, r.fullnessPerHour, "hunger")
        activity?.rates?.forEach { (need, perHour) -> rate(needOf(need), perHour, activity.id) }
        music?.spec?.rates?.forEach { (need, perHour) -> rate(needOf(need), perHour, music!!.spec.name) }
        // Low energy or fullness replaces the drift: mood just falls.
        if (needs[Need.Energy] < r.low || needs[Need.Fullness] < r.low) {
            rate(Need.Mood, r.lowMoodPerHour, "low energy or fullness")
        } else {
            val gap = moodTarget() - needs[Need.Mood]
            if (gap != 0.0) needs.change(ticks, Need.Mood, sign(gap) * min(r.moodDriftPerHour / 60, abs(gap)), "drift toward ${moodTarget().toInt()}", rate = true)
        }
    }

    // --- what the agent and the renderer see ----------------------------------------------------

    override fun stamp(tick: Long): String = "d${clock.day(tick)} ${GameClock.hhmm(clock.minuteOfDay(tick))}"

    private fun shopItems(): List<ItemSpec> = content.items.filter { it.price != null && (it.stackable || count(it.id) == 0) }

    private fun activityOption(a: ActivitySpec): ActivityOption {
        lockReason(a)?.let { return ActivityOption(a.id, locked = it) }
        return ActivityOption(a.id, takes = takes(a), gives = gives(a))
    }

    private fun takes(a: ActivitySpec): String {
        val until = a.until ?: return GameClock.duration(a.minutes!!) + if (a.sells != null) ", however much you sell" else ""
        val byClock = clock.ticksUntil(ticks, GameClock.parseHhmm(until.time)) / clock.ticksPerMinute
        val need = until.need?.let(::needOf)
        val perHour = need?.let { n -> a.rates[n.id] }
        val byNeed = if (need != null && perHour != null && perHour > 0) ((until.reaches!! - needs[need]) / perHour * 60).toLong() else Long.MAX_VALUE
        val about = GameClock.duration(min(byClock, byNeed).coerceAtLeast(1).toInt())
        val stops = a.stopBelow.entries.joinToString("") { (n, level) -> ", or $n below ${level.toInt()}" }
        return "until ${until.time}" + (need?.let { " or ${it.id} ${until.reaches!!.toInt()}" } ?: "") + stops + ", about $about"
    }

    /** [forSale]: describing an activity he can't do yet, so leave out what he has now. */
    private fun gives(a: ActivitySpec, forSale: Boolean = false): String {
        val parts = mutableListOf<String>()
        a.produces.forEach { (id, n) -> parts += quantity(id, n) + (item(id).sellPrice?.let { ", sells for $$it" } ?: "") }
        if (forSale) {
            a.consumes.forEach { (tag, n) -> parts += "uses ${if (n == 1) "a" else "$n"} ${tag.replace('_', ' ')}" }
            return parts.joinToString("; ")
        }
        a.sells?.let { tag ->
            val held = owned.filter { (id, _) -> tag in item(id).allTags }
            val total = held.entries.sumOf { (id, n) -> (item(id).sellPrice ?: 0) * n }
            parts += "$$total for the ${held.entries.joinToString(" and ") { (id, n) -> quantity(id, n) }} you hold"
        }
        a.consumes.forEach { (tag, n) -> parts += "uses ${if (n == 1) "a" else "$n"} ${tag.replace('_', ' ')} (you have ${countTag(tag)})" }
        a.effects.forEach { (need, d) -> parts += "$need ${signed(d)}" }
        a.rates.forEach { (need, d) -> parts += "$need ${signed(d)}/h" }
        a.background?.let { bg -> parts += "${bg.name} for ${GameClock.duration(bg.minutes)}: " + bg.rates.entries.joinToString { (need, d) -> "$need ${signed(d)}/h" } }
        return parts.joinToString("; ")
    }

    private fun useOptions(): List<UseOption> = content.uses.flatMap { rule ->
        if (lockReason(rule) != null) emptyList()
        else owned.keys.filter { rule.item!! in item(it).allTags }.map { UseOption(it, rule.on!!, takes(rule), gives(rule)) }
    }

    private fun shopOption(spec: ItemSpec): ShopOption {
        val parts = mutableListOf<String>()
        if (spec.pack > 1) parts += quantity(spec.id, spec.pack)
        val unlocks = (content.activities + content.uses).filter { a ->
            spec.id in a.at || a.requires.any { it in spec.allTags } || a.consumes.keys.any { it in spec.allTags } || a.item in spec.allTags
        }
        // What it's for. When it's what a locked activity is missing, say so and what that activity gives.
        unlocks.forEach { a ->
            val locked = lockReason(a)
            val after = lockReason(a, extra = spec.id)
            val what = "${takes(a)}: ${gives(a, forSale = true)}"
            parts += when {
                locked == null || locked == after -> "for ${a.id}"
                after == null -> "unlocks ${a.id} ($what)"
                else -> "for ${a.id} ($what), which also $after"
            }
        }
        if (spec.comfort > 0) parts += "comfort +${spec.comfort}: mood settles ${spec.comfort} higher"
        return ShopOption(spec.id, spec.price!!, ledger.canAfford(spec.price), parts.joinToString("; "))
    }

    private fun musicText(): String? = music?.let { "${it.itemId}, ${GameClock.duration(((it.endTick - ticks) / clock.ticksPerMinute).toInt().coerceAtLeast(1))} left" }

    override fun publish(): Published {
        val entities = buildList {
            add(EntitySnapshot(guy.id, "guy", guy.x, guy.y, 1f, guy.facing.degrees))
            placed().forEach { (spec, cell) ->
                val state = if (spec.id == music?.let { m -> content.uses.firstOrNull { it.background?.name == m.spec.name }?.on }) "playing ${music!!.itemId}" else null
                add(EntitySnapshot(EntityId(spec.id), spec.kind, cell.x + 0.5f, cell.y + 0.5f, if (spec.blocks) 1f else 0.2f, 0f, state))
            }
        }
        val d = doing
        val life = LifeSnapshot(
            clock = clock.reading(ticks),
            money = ledger.balance,
            energy = needs[Need.Energy],
            fullness = needs[Need.Fullness],
            mood = needs[Need.Mood],
            moodTarget = moodTarget(),
            doing = d?.activity?.id,
            progress = d?.let { ((ticks - it.startTick).toFloat() / (it.endTick - it.startTick).coerceAtLeast(1)).coerceIn(0f, 1f) },
            music = musicText(),
            holding = holding(),
            owns = ownsMap(),
            transactions = ledger.transactions.takeLast(SNAPSHOT_TRANSACTIONS),
            clockScale = clock,
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
            activities = content.activities.map(::activityOption),
            uses = useOptions(),
            shop = shopItems().map(::shopOption),
        )
    }

    /** The whole room, one string per row, with owned furniture as its slot letter; legend in [mapLegend]. */
    private fun roomMap(): List<String> {
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

        private fun signed(v: Double): String {
            val text = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
            return if (v >= 0) "+$text" else text
        }

        fun create(
            content: BedroomContent = BedroomContent.default(),
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
            characterId: String = "guy",
        ): BedroomWorld {
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
            return BedroomWorld(TileMap(width, height, terrain), guy ?: error("room has no '@'"), content, clock, slotCells)
        }
    }
}
