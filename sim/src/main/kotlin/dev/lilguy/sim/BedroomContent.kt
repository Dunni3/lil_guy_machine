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
) {
    @Transient private val byId = items.associateBy { it.id }

    fun item(id: String): ItemSpec? = byId[id]

    fun activity(id: String): ActivitySpec? = activities.find { it.id == id }

    /** Items with [tag] (an id is its own tag), in file order. */
    fun withTag(tag: String): List<ItemSpec> = items.filter { tag in it.allTags }

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
            (a.requires + a.consumes.keys + listOfNotNull(a.sells, a.item)).forEach { require(it in tags) { "$where names '$it', which no item has as a tag" } }
            a.produces.keys.forEach { require(it in ids) { "$where produces '$it', which isn't an item" } }
            a.on?.let { require(it in ids) { "$where is used on '$it', which isn't an item" } }
            require((a.minutes != null) != (a.until != null)) { "$where needs exactly one of `minutes` and `until`" }
            a.until?.let { GameClock.parseHhmm(it.time) }
            (a.effects.keys + a.rates.keys + a.stopBelow.keys + a.block.map { it.need } + a.background?.rates?.keys.orEmpty()).forEach { needOf(it) }
        }
        uses.forEach { require(it.item != null && it.on != null) { "use ${it.id} needs `item` and `on`" } }
        activities.forEach { require(it.item == null && it.on == null) { "activity ${it.id}: `item` and `on` are only for uses" } }
        needs.workFloor.forEach { needOf(it.need) }
        GameClock.parseHhmm(start.time)
    }
}

@Serializable
data class RoomContent(
    val rows: List<String>,
    /** Map letter to slot name. */
    val slots: Map<String, String>,
    @SerialName("wall_slots") val wallSlots: List<String> = emptyList(),
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
) {
    @Serializable
    data class Until(val time: String, val need: String? = null, val reaches: Double? = null)

    @Serializable
    data class Background(val name: String, val minutes: Int, val rates: Map<String, Double>)
}

/** "energy" to [Need.Energy]; anything else is a content error. */
fun needOf(id: String): Need = Need.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("'$id' isn't a need; use energy, fullness or mood")
