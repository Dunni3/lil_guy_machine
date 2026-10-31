package dev.lilguy.sim

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlPath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

/**
 * The bedroom as data: the room, need rules, items and verbs, loaded from the YAML files in
 * `content/bedroom/` ([FILES] says which keys each holds; on the classpath by default). Field names
 * match the files. Loading checks that every id an activity names exists, so a typo fails at
 * start-up, not mid-run.
 *
 * Items and verbs above tier 0 are gated: they exist in the file but not in the world
 * until granted. [unlocked] is the content he can see, and it is checked the same way, so a
 * tier-0 verb can't depend on a gated item.
 *
 * Items are described in a closed [vocabulary]: affordances (what can be done with one; verbs
 * ask for them, and some take parameters) and qualities (what it's like to him; his [personality]
 * scores them). What a verb does is its list of [Op]s; every verb has a [VerbKind], and its ops must
 * keep that kind's contract.
 */
@Serializable
data class BedroomContent(
    val room: RoomContent,
    val start: StartState,
    val needs: NeedRules,
    val shop: ShopRules,
    val items: List<ItemSpec>,
    val verbs: List<VerbSpec>,
    val think: ThinkRules = ThinkRules(),
    /** A daily request for a piece; null for none. */
    val commissions: CommissionRules? = null,
    val gallery: GalleryRules = GalleryRules(),
    /** Repeating an activity pays less; null turns it off. */
    val habituation: HabituationRules? = null,
    /** The first time with an activity pays a bonus; null turns it off. */
    val novelty: NoveltyRules? = null,
    val vocabulary: Vocabulary = Vocabulary(),
    /** His default affinities and curiosity; a run can swap in another ([withPersonality]). */
    val personality: Personality = Personality(),
    @SerialName("quality_value") val qualityValue: QualityValue = QualityValue(),
) {
    @Transient private val byId = items.associateBy { it.id }

    fun item(id: String): ItemSpec? = byId[id]

    fun verb(id: String): VerbSpec? = verbs.find { it.id == id }

    /** Items that answer to [name], an id or an affordance, in file order. */
    fun withName(name: String): List<ItemSpec> = items.filter { name in it.names }

    /** Null when [name] is an item id or an affordance some item has; otherwise what's wrong. */
    internal fun unknownName(name: String): String? = if (items.any { name in it.names }) null else "'$name' isn't an item or an affordance any item has"

    /**
     * What [item] is worth to him: each quality's strength times his affinity for it, summed. In
     * view it adds comfort ([QualityValue.inView]); used, mood ([QualityValue.inUse]).
     */
    fun value(item: ItemSpec): Double = item.qualities.entries.sumOf { (q, strength) -> strength * personality.affinity(q) }

    /** The same content with [p] as his personality, checked again. */
    fun withPersonality(p: Personality): BedroomContent = copy(personality = p).also { it.validate() }

    /** Ids of everything above tier 0: what can be granted. */
    val gated: Set<String> get() = (items.filter { it.tier > 0 }.map { it.id } + verbs.filter { it.tier > 0 }.map { it.id }).toSet()

    /** The content with only tier 0 and the [granted] ids in it. */
    fun unlocked(granted: Set<String>): BedroomContent {
        val unknown = granted - gated
        require(unknown.isEmpty()) { "can't grant $unknown: only gated ids (tier above 0) can be granted" }
        fun open(id: String, tier: Int) = tier == 0 || id in granted
        return copy(
            items = items.filter { open(it.id, it.tier) },
            verbs = verbs.filter { open(it.id, it.tier) },
        ).also { it.validate() }
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

        /** The content's files, each with the top-level keys it holds. */
        val FILES: Map<String, List<String>> = linkedMapOf(
            "room" to listOf("room", "start"),
            "rules" to listOf("needs", "habituation", "novelty", "shop", "think", "commissions", "gallery", "quality_value"),
            "vocabulary" to listOf("vocabulary"),
            "personality" to listOf("personality"),
            "items" to listOf("items"),
            "verbs" to listOf("verbs"),
        )

        /** Content from [files], file name (no extension) to its text. Each file may hold only its own keys ([FILES]). */
        fun parse(files: Map<String, String>): BedroomContent {
            val entries = LinkedHashMap<com.charleskorn.kaml.YamlScalar, com.charleskorn.kaml.YamlNode>()
            files.forEach { (name, text) ->
                val keys = FILES[name] ?: throw IllegalArgumentException("'$name.yaml' isn't a content file; they are ${FILES.keys.joinToString { "$it.yaml" }}")
                val map = yaml.parseToYamlNode(text) as? YamlMap ?: throw IllegalArgumentException("$name.yaml must be a map of ${keys.joinToString()}")
                map.entries.forEach { (k, v) ->
                    require(k.content in keys) { "$name.yaml: '${k.content}' belongs in ${FILES.entries.firstOrNull { k.content in it.value }?.let { "${it.key}.yaml" } ?: "no file"}; this one holds ${keys.joinToString()}" }
                    entries[k] = v
                }
            }
            return yaml.decodeFromYamlNode(serializer(), YamlMap(entries, YamlPath.root)).also { it.validate() }
        }

        /** Content from a directory of the [FILES]. */
        fun load(dir: File): BedroomContent = parse(FILES.keys.associateWith { File(dir, "$it.yaml").readText() })

        /** The content shipped with the sim. */
        fun default(): BedroomContent = parse(
            FILES.keys.associateWith { name ->
                val stream = BedroomContent::class.java.getResourceAsStream("/content/bedroom/$name.yaml")
                    ?: error("content/bedroom/$name.yaml is missing from the classpath")
                stream.bufferedReader().use { it.readText() }
            },
        )
    }

    private fun validate() {
        val ids = items.map { it.id }
        require(ids.size == ids.toSet().size) { "item ids must be unique: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        val tags = items.flatMap { it.names }.toSet()
        val slots = room.slots.values.toSet()
        val affordances = vocabulary.affords.toSet()
        val qualities = vocabulary.qualities.toSet()
        require(affordances.none { it in ids }) { "affordances can't share a name with an item: ${affordances.filter { it in ids }}" }
        items.forEach { item ->
            item.affords.forEach { (a, params) ->
                require(a in affordances) { "item ${item.id} affords '$a', which isn't in vocabulary.affords" }
                Affordance.problems(a, params).forEach { throw IllegalArgumentException("item ${item.id}, $a: $it") }
            }
            item.qualities.forEach { (q, s) ->
                require(q in qualities) { "item ${item.id} is '$q', which isn't in vocabulary.qualities" }
                require(s > 0 && s <= QualityValue.MAX_STRENGTH) { "item ${item.id}: quality $q needs a strength above 0 and at most ${QualityValue.MAX_STRENGTH}" }
            }
            item.slot?.let { require(it in slots) { "item ${item.id} goes in slot '$it', which the room doesn't have" } }
            require(item.pack >= 1) { "item ${item.id}: pack must be at least 1" }
            require(item.price == null || item.price > 0) { "item ${item.id}: price must be positive" }
        }
        start.items.forEach { (id, n) ->
            require(id in ids) { "start item '$id' isn't in items" }
            require(n >= 1) { "start item '$id' needs a count of at least 1" }
        }
        require(shop.at in ids) { "the shop is at '${shop.at}', which isn't an item" }
        val verbIds = verbs.map { it.id }
        require(verbIds.size == verbIds.toSet().size) { "verb ids must be unique" }
        verbs.forEach { a ->
            val where = "verb ${a.id}"
            (a.args.keys - VerbSpec.SLOTS).forEach { throw IllegalArgumentException("$where: argument '$it' isn't a slot; use ${VerbSpec.SLOTS.joinToString(" or ")}") }
            require(a.at.isNotEmpty()) { "$where needs at least one place in `at`" }
            a.at.forEach { at ->
                val places = a.args[at]?.let(::withName) ?: if (at == VerbSpec.HERE) null else listOfNotNull(byId[at])
                require(places == null || (places.isNotEmpty() && places.all { it.slot != null })) { "$where happens at '$at', which isn't a slot item, an argument naming slot items, or `here`" }
            }
            (a.requires + a.args.values).forEach { require(it in tags) { "$where names '$it', which isn't an item or an affordance any item has" } }
            require(a.effects.isNotEmpty()) { "$where does nothing: give it `effects`" }
            (a.effects.flatMap { it.problems(this, a) } + a.kind.violations(a, affordances)).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("$where: " + it.joinToString("; ")) }
            require((a.minutes != null) != (a.until != null)) { "$where needs exactly one of `minutes` and `until`" }
            a.until?.let { u ->
                GameClock.parseHhmm(u.time)
                require(u.need == null || a.effects.any { it is RateOp && u.need in it.perHour }) { "$where: until ${u.need} needs a rate for ${u.need}" }
            }
            (a.stopBelow.keys + a.block.map { it.need }).forEach { needOf(it) }
        }
        needs.workFloor.forEach { needOf(it.need) }
        require(needs.stimulationPerHour <= 0 && needs.boredMood >= 0) { "stimulation_per_hour can't be positive or bored_mood negative" }
        habituation?.let { h ->
            require(h.perRepeat in 0.0..1.0 && h.recoverPerHour > 0 && h.staleStimulation <= 0) { "habituation: per_repeat 0 to 1, recover_per_hour positive, stale_stimulation not positive" }
        }
        novelty?.effects?.keys?.forEach { needOf(it) }
        personality.affinities.forEach { (q, w) ->
            require(q in qualities) { "personality likes '$q', which isn't in vocabulary.qualities" }
            require(w in Personality.AFFINITY_RANGE) { "personality: affinity for $q must be in ${Personality.AFFINITY_RANGE}" }
        }
        require(personality.curiosity > 0) { "personality: curiosity must be positive" }
        require(qualityValue.inView >= 0 && qualityValue.inUse >= 0) { "quality_value: in_view and in_use can't be negative" }
        needs.neglect.forEach { n ->
            require(verbs.any { it.id == n.verb }) { "neglect names verb '${n.verb}', which isn't in verbs" }
            require(n.afterHours >= 1 && n.moodPerHour < 0) { "neglect of ${n.verb}: after_hours must be at least 1 and mood_per_hour negative" }
        }
        items.forEach { item -> item.canvas?.let { require(item.carried) { "item ${item.id}: only a carried item can be drawable" } } }
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
    /** Stimulation lost per waking hour. */
    @SerialName("stimulation_per_hour") val stimulationPerHour: Double = 0.0,
    /** Below [bored] stimulation, each point under it lowers the mood he settles to by [boredMood]. */
    val bored: Double = 0.0,
    @SerialName("bored_mood") val boredMood: Double = 0.0,
)

/**
 * Repetition wears an activity thin. Each finish adds [perRepeat] to its wear (0 to 1), which
 * fades [recoverPerHour] an hour. Its positive mood and stimulation effects are paid at
 * 1 - wear, and finishing one that was already worn costs [staleStimulation] times the wear.
 * Routine verbs (marked, or of a routine [VerbKind]) don't wear. A verb with arguments wears per item (each record on its own).
 */
@Serializable
data class HabituationRules(
    @SerialName("per_repeat") val perRepeat: Double,
    @SerialName("recover_per_hour") val recoverPerHour: Double,
    @SerialName("stale_stimulation") val staleStimulation: Double = 0.0,
)

/**
 * The closed lists items are described in. [affords]: what can be done with an item; verbs
 * ask for these in `requires`, a use's `item` and their ops, as well as for item ids. Some take
 * parameters on the item ([Affordance]). [qualities]: what an item is like to him, each with a
 * strength, scored by his affinities.
 */
@Serializable
data class Vocabulary(val affords: List<String> = emptyList(), val qualities: List<String> = emptyList())

/**
 * Who he is, as numbers, and something a run can vary. [affinities]: how much he likes each quality,
 * from -1 (it puts him off) to 2; a quality left out counts 0. [curiosity] scales how fast
 * stimulation drains and how much a first time pays; 1 is the content's numbers.
 */
@Serializable
data class Personality(val affinities: Map<String, Double> = emptyMap(), val curiosity: Double = 1.0) {
    fun affinity(quality: String): Double = affinities[quality] ?: 0.0

    companion object {
        val AFFINITY_RANGE = -1.0..2.0
    }
}

/**
 * How an item's value ([BedroomContent.value]) is paid. [inView]: comfort per point while it sits in
 * its slot (what decor does). [inUse]: mood per point when a consumptive use of it finishes.
 */
@Serializable
data class QualityValue(
    @SerialName("in_view") val inView: Double = 1.0,
    @SerialName("in_use") val inUse: Double = 1.0,
) {
    companion object {
        const val MAX_STRENGTH = 3.0
    }
}

/** The first time he finishes an activity (or a use with a given item) adds [effects]; routine ones don't count. */
@Serializable
data class NoveltyRules(val effects: Map<String, Double>)

/**
 * Once [verb] hasn't finished for [afterHours] (counting from the start of the run), mood
 * falls [moodPerHour] instead of drifting, until he does it again. [reason] says why, as an event.
 */
@Serializable
data class Neglect(
    val verb: String,
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

/** What a piece of this item is drawn on: a [size] by [size] grid, up to [colors] colors and [strokes] strokes, each two of a model's picks. */
data class CanvasSpec(val size: Int, val colors: Int, val strokes: Int)

/**
 * An affordance's parameters on one item, `sellable: { price: 70 }`. Which affordances take which
 * parameters is fixed in code ([Affordance.PARAMS]), since ops read them: `sell` reads the price and
 * the painter reads the canvas. Every other affordance takes none: `readable: {}`.
 */
@Serializable
data class AffordanceParams(
    /** sellable: what one sells for. */
    val price: Int? = null,
    /** drawable: the canvas is a square grid this many cells wide. */
    val size: Int? = null,
    /** drawable: at most this many colors. */
    val colors: Int? = null,
    /** drawable: at most this many strokes, each two of the deciding model's picks. */
    val strokes: Int? = null,
) {
    internal val set: Set<String> get() = listOfNotNull(price?.let { "price" }, size?.let { "size" }, colors?.let { "colors" }, strokes?.let { "strokes" }).toSet()
}

object Affordance {
    /** Affordances that take parameters, with the ones each must set and their ranges. */
    val PARAMS: Map<String, Map<String, IntRange>> = mapOf(
        "sellable" to mapOf("price" to 1..10_000),
        "drawable" to mapOf("size" to 4..64, "colors" to 2..32, "strokes" to 1..100),
    )

    internal fun problems(affordance: String, p: AffordanceParams): List<String> {
        val wants = PARAMS[affordance].orEmpty()
        val values = mapOf("price" to p.price, "size" to p.size, "colors" to p.colors, "strokes" to p.strokes)
        return (p.set - wants.keys).map { "takes no '$it'" } +
            wants.mapNotNull { (k, range) -> values[k].let { v -> if (v == null || v !in range) "needs $k from ${range.first} to ${range.last}" else null } }
    }
}

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
    /** For display and grouping: fixture, furniture, decor, tool, food, supply, record, book, art. */
    val group: String,
    val slot: String? = null,
    val blocks: Boolean = false,
    /** Null when it isn't for sale. Buys [pack] of it. */
    val price: Int? = null,
    val pack: Int = 1,
    val stackable: Boolean = false,
    /** What can be done with it, from [Vocabulary.affords], each with its parameters ([AffordanceParams]). */
    val affords: Map<String, AffordanceParams> = emptyMap(),
    /** What it's like, quality to strength, from [Vocabulary.qualities]. */
    val qualities: Map<String, Double> = emptyMap(),
    /** Carried in his hands (art) rather than kept in the room. */
    val carried: Boolean = false,
    /** Above 0, it's gated until granted; see [BedroomContent.unlocked]. */
    val tier: Int = 0,
) {
    /** What an activity can ask for it by: its id and its affordances. */
    val names: Set<String> get() = setOf(id) + affords.keys

    /** What one sells for, when it's `sellable`. */
    val sellPrice: Int? get() = affords["sellable"]?.price

    /** The canvas a drawing model gets, when it's `drawable`. */
    val canvas: CanvasSpec? get() = affords["drawable"]?.let { CanvasSpec(it.size!!, it.colors!!, it.strokes!!) }
}

@Serializable
data class VerbSpec(
    val id: String,
    /** What sort of verb it is; the row must keep the kind's contract. */
    val kind: VerbKind,
    /**
     * Its argument slots, slot to what fills it (an item id or an affordance): `{ what: readable }`.
     * do() names the items, or the first owned match fills each slot. Slots are [SLOTS].
     */
    val args: Map<String, String> = emptyMap(),
    /** Where it happens: owned slot items by id, an argument naming one (`on`), or [HERE]; the nearest is used. */
    val at: List<String>,
    val requires: List<String> = emptyList(),
    val minutes: Int? = null,
    val until: Until? = null,
    val work: Boolean = false,
    /** What it does, in order: each one an [Op]. */
    val effects: List<Op> = emptyList(),
    val block: List<NeedBlock> = emptyList(),
    /** Ends early when a need falls below its level: he wakes up hungry. */
    @SerialName("stop_below") val stopBelow: Map<String, Double> = emptyMap(),
    /** Above 0, it's gated until granted; see [BedroomContent.unlocked]. */
    val tier: Int = 0,
    /** What he does, for the prompt's "the only things you can do are ..."; the id when null. */
    val phrase: String? = null,
    /** Upkeep (eating, hanging): it neither wears thin with repetition nor counts as new. Some kinds always are. */
    val routine: Boolean = false,
) {
    /** Marked routine, or of a kind that always is (selling, sleeping). */
    val isRoutine: Boolean get() = routine || kind.routine

    /** Its ops of type [T], in order. */
    inline fun <reified T : Op> ops(): List<T> = effects.filterIsInstance<T>()

    /** Its need changes at the finish, all [NeedOp]s together. */
    val needEffects: Map<String, Double> get() = ops<NeedOp>().flatMap { it.changes.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

    @Serializable
    data class Until(val time: String, val need: String? = null, val reaches: Double? = null)

    companion object {
        /** The argument slots a verb can have; [Intent.Do] has a field for each. */
        val SLOTS = listOf("what", "on")

        /** `at: [here]`: where he stands. */
        const val HERE = "here"
    }
}

/** "energy" to [Need.Energy]; anything else is a content error. */
fun needOf(id: String): Need = Need.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("'$id' isn't a need; use ${Need.entries.joinToString { it.id }}")
