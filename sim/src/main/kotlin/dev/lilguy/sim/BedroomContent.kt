package dev.lilguy.sim

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

/**
 * The bedroom as data: the room, need rules, items and activities, loaded from YAML
 * (`content/bedroom.yaml` on the classpath by default). Field names match the file. [load]
 * checks that every id an activity names exists, so a typo fails at start-up, not mid-run.
 *
 * Items, activities and uses above tier 0 are gated: they exist in the file but not in the world
 * until granted. [unlocked] is the content he can see, and it is checked the same way, so a
 * tier-0 activity can't depend on a gated item.
 */
@Serializable
data class BedroomContent(
    val room: RoomContent,
    val start: StartState,
    val needs: NeedRules,
    val shop: ShopRules,
    val items: List<ItemSpec>,
    val activities: List<ActivitySpec>,
    val uses: List<ActivitySpec> = emptyList(),
    val think: ThinkRules = ThinkRules(),
    /** A daily request for a piece; null for none. */
    val commissions: CommissionRules? = null,
    val gallery: GalleryRules = GalleryRules(),
) {
    @Transient private val byId = items.associateBy { it.id }

    fun item(id: String): ItemSpec? = byId[id]

    fun activity(id: String): ActivitySpec? = activities.find { it.id == id }

    /** Items with [tag] (an id is its own tag), in file order. */
    fun withTag(tag: String): List<ItemSpec> = items.filter { tag in it.allTags }

    /** Ids of everything above tier 0: what can be granted. */
    val gated: Set<String> get() = (items.filter { it.tier > 0 }.map { it.id } + (activities + uses).filter { it.tier > 0 }.map { it.id }).toSet()

    /** The content with only tier 0 and the [granted] ids in it. */
    fun unlocked(granted: Set<String>): BedroomContent {
        val unknown = granted - gated
        require(unknown.isEmpty()) { "can't grant $unknown: only gated ids (tier above 0) can be granted" }
        fun open(id: String, tier: Int) = tier == 0 || id in granted
        return copy(
            items = items.filter { open(it.id, it.tier) },
            activities = activities.filter { open(it.id, it.tier) },
            uses = uses.filter { open(it.id, it.tier) },
        ).also { it.validate() }
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

        fun parse(text: String): BedroomContent = yaml.decodeFromString(serializer(), text).also { it.validate() }

        fun load(file: File): BedroomContent = parse(file.readText())

        /** The content shipped with the sim. */
        fun default(): BedroomContent {
            val stream = BedroomContent::class.java.getResourceAsStream("/content/bedroom.yaml")
                ?: error("content/bedroom.yaml is missing from the classpath")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }

    private fun validate() {
        val ids = items.map { it.id }
        require(ids.size == ids.toSet().size) { "item ids must be unique: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        val tags = items.flatMap { it.allTags }.toSet()
        val slots = room.slots.values.toSet()
        items.forEach { item ->
            item.slot?.let { require(it in slots) { "item ${item.id} goes in slot '$it', which the room doesn't have" } }
            require(item.pack >= 1) { "item ${item.id}: pack must be at least 1" }
            require(item.price == null || item.price > 0) { "item ${item.id}: price must be positive" }
        }
        start.items.forEach { (id, n) ->
            require(id in ids) { "start item '$id' isn't in items" }
            require(n >= 1) { "start item '$id' needs a count of at least 1" }
        }
        require(shop.at in ids) { "the shop is at '${shop.at}', which isn't an item" }
        val activityIds = (activities + uses).map { it.id }
        require(activityIds.size == activityIds.toSet().size) { "activity ids must be unique" }
        (activities + uses).forEach { a ->
            val where = "activity ${a.id}"
            require(a.at.isNotEmpty()) { "$where needs at least one place in `at`" }
            a.at.forEach { require(byId[it]?.slot != null) { "$where happens at '$it', which isn't a slot item" } }
            (a.requires + a.consumes.keys + listOfNotNull(a.sells, a.hangs, a.item)).forEach { require(it in tags) { "$where names '$it', which no item has as a tag" } }
            a.produces.keys.forEach { require(it in ids) { "$where produces '$it', which isn't an item" } }
            a.on?.let { require(it in ids) { "$where is used on '$it', which isn't an item" } }
            require((a.minutes != null) != (a.until != null)) { "$where needs exactly one of `minutes` and `until`" }
            a.until?.let { GameClock.parseHhmm(it.time) }
            (a.effects.keys + a.rates.keys + a.stopBelow.keys + a.block.map { it.need } + a.background?.rates?.keys.orEmpty()).forEach { needOf(it) }
        }
        uses.forEach { require(it.item != null && it.on != null) { "use ${it.id} needs `item` and `on`" } }
        activities.forEach { require(it.item == null && it.on == null) { "activity ${it.id}: `item` and `on` are only for uses" } }
        needs.workFloor.forEach { needOf(it.need) }
        needs.neglect.forEach { n ->
            require(activities.any { it.id == n.activity }) { "neglect names activity '${n.activity}', which isn't in activities" }
            require(n.afterHours >= 1 && n.moodPerHour < 0) { "neglect of ${n.activity}: after_hours must be at least 1 and mood_per_hour negative" }
        }
        items.forEach { item -> item.canvas?.let { require(item.carried && it.size in 4..64 && it.colors in 2..32 && it.ops >= 1) { "item ${item.id}: a canvas needs a carried item, size 4 to 64, 2 to 32 colors and at least 1 op" } } }
        commissions?.let { c ->
            GameClock.parseHhmm(c.arrive)
            require(c.reward > 0 && c.adjectives.isNotEmpty() && c.nouns.isNotEmpty()) { "commissions need a positive reward and at least one adjective and noun" }
        }
        require(gallery.max >= 1 && gallery.comfortEach >= 0) { "gallery.max must be at least 1" }
        require(think.minutes >= 1) { "think needs at least 1 minute" }
        require(think.every == null || think.every >= 1) { "think.every must be at least 1" }
        GameClock.parseHhmm(start.time)
        require(room.approach.keys.all { it in room.slots.values }) { "room.approach names a slot that isn't in room.slots" }
    }
}

@Serializable
data class RoomContent(
    val rows: List<String>,
    /** Map letter to slot name. */
    val slots: Map<String, String>,
    @SerialName("wall_slots") val wallSlots: List<String> = emptyList(),
    /** Slot name to the side he works it from (N, E, SE, ...), when that tile is free; else the nearest side. */
    val approach: Map<String, Direction> = emptyMap(),
)

@Serializable
data class StartState(
    val money: Int,
    val time: String,
    val needs: NeedLevels,
    /** Item id to count. */
    val items: Map<String, Int>,
)

@Serializable
data class NeedRules(
    @SerialName("awake_energy_per_hour") val awakeEnergyPerHour: Double,
    @SerialName("fullness_per_hour") val fullnessPerHour: Double,
    @SerialName("mood_drift_per_hour") val moodDriftPerHour: Double,
    @SerialName("mood_baseline") val moodBaseline: Double,
    val low: Double,
    @SerialName("low_mood_per_hour") val lowMoodPerHour: Double,
    @SerialName("work_floor") val workFloor: List<NeedBlock>,
    /** Mood falls instead of drifting while an activity has gone undone too long. */
    val neglect: List<Neglect> = emptyList(),
)

/**
 * Once [activity] hasn't finished for [afterHours] (counting from the start of the run), mood
 * falls [moodPerHour] instead of drifting, until he does it again. [reason] says why, as an event.
 */
@Serializable
data class Neglect(
    val activity: String,
    @SerialName("after_hours") val afterHours: Int,
    @SerialName("mood_per_hour") val moodPerHour: Double,
    val reason: String,
)

/** Refuses an activity when [need] is below [below] or above [above]. */
@Serializable
data class NeedBlock(
    val need: String,
    val below: Double? = null,
    val above: Double? = null,
    val reason: String,
) {
    fun blocks(level: Double): Boolean = (below != null && level < below) || (above != null && level > above)
}

@Serializable
data class ShopRules(val at: String, val minutes: Int)

/**
 * A request for a piece that arrives each day at [arrive] and stays open until the next one: an
 * adjective and a noun picked from the lists. The first piece made while it's open, drawn to match,
 * and sold earns [reward] on top of its price.
 */
@Serializable
data class CommissionRules(
    val arrive: String,
    val reward: Int,
    val adjectives: List<String>,
    val nouns: List<String>,
)

/** Hung art: at most [max] pieces on the wall, each adding [comfortEach] to the room's comfort. */
@Serializable
data class GalleryRules(val max: Int = 3, @SerialName("comfort_each") val comfortEach: Int = 3)

/** What a drawing model gets for a piece of this item: a [size] by [size] grid, up to [colors] colors and [ops] operations. */
@Serializable
data class CanvasSpec(val size: Int, val colors: Int, val ops: Int)

/**
 * The think intent: he stands where he is for [minutes] and it costs [energy] on top of being
 * awake. [every]: the agent is made to think after this many decisions without one; null leaves it
 * to the model.
 */
@Serializable
data class ThinkRules(val minutes: Int = 10, val energy: Double = -2.0, val every: Int? = 4)

@Serializable
data class ItemSpec(
    val id: String,
    val name: String,
    val kind: String,
    val slot: String? = null,
    val blocks: Boolean = false,
    /** Null when it isn't for sale. Buys [pack] of it. */
    val price: Int? = null,
    val pack: Int = 1,
    val stackable: Boolean = false,
    val tags: List<String> = emptyList(),
    /** Carried in his hands (art) rather than kept in the room. */
    val carried: Boolean = false,
    @SerialName("sell_price") val sellPrice: Int? = null,
    val comfort: Int = 0,
    /** Above 0, it's gated until granted; see [BedroomContent.unlocked]. */
    val tier: Int = 0,
    /** Art that is drawn: the canvas a drawing model gets. */
    val canvas: CanvasSpec? = null,
) {
    val allTags: Set<String> get() = setOf(id) + tags
}

@Serializable
data class ActivitySpec(
    val id: String,
    val at: List<String>,
    val requires: List<String> = emptyList(),
    val minutes: Int? = null,
    val until: Until? = null,
    val work: Boolean = false,
    val consumes: Map<String, Int> = emptyMap(),
    val produces: Map<String, Int> = emptyMap(),
    /** Sells every carried item with this tag at its sell_price. */
    val sells: String? = null,
    /** Hangs his newest carried item with this tag on the wall (see [GalleryRules]). */
    val hangs: String? = null,
    val effects: Map<String, Double> = emptyMap(),
    val rates: Map<String, Double> = emptyMap(),
    /** Asleep: the awake energy loss doesn't apply. */
    val asleep: Boolean = false,
    val block: List<NeedBlock> = emptyList(),
    /** Ends early when a need falls below its level: he wakes up hungry. */
    @SerialName("stop_below") val stopBelow: Map<String, Double> = emptyMap(),
    /** For uses: the tag of the item used, and the item it is used on. */
    val item: String? = null,
    val on: String? = null,
    val background: Background? = null,
    /** Above 0, it's gated until granted; see [BedroomContent.unlocked]. */
    val tier: Int = 0,
    /** What he does, for the prompt's "the only things you can do are ..."; the id when null. */
    val phrase: String? = null,
) {
    @Serializable
    data class Until(val time: String, val need: String? = null, val reaches: Double? = null)

    @Serializable
    data class Background(val name: String, val minutes: Int, val rates: Map<String, Double>)
}

/** "energy" to [Need.Energy]; anything else is a content error. */
fun needOf(id: String): Need = Need.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("'$id' isn't a need; use energy, fullness or mood")
