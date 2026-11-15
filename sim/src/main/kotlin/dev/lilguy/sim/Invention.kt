package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A thing he designed at the workbench, as the model that writes it gives it: what it's called, the
 * one thing it's for ([use], an affordance [InventRules.uses] opens), what it's like ([qualities],
 * from the vocabulary), and how it looks. The sim decides everything else: its id, group, where it
 * stands and what its materials cost ([InventRules.check]). [place]: `floor` or `wall`, for a thing
 * to look at. [book]: what a readable thing is as a book.
 */
@Serializable
@SerialName("design")
data class Design(
    val name: String,
    val use: String,
    val qualities: Map<String, Double> = emptyMap(),
    val place: String? = null,
    val look: List<LookPart> = emptyList(),
    val book: BookDesign? = null,
    /** Why he made it, in his words; it goes into the event. */
    val why: String = "",
    /** Other looks it can show (`world.show(id, name)`): each a list of changes to named parts of [look] ([PartChange]). */
    val variants: Map<String, List<PartChange>> = emptyMap(),
)

/**
 * One change a variant makes to the parts of a look named [part] (every shape with that `part`): a new
 * [color], [glow], rotation, a [scale] about the part's bottom centre, or [hidden]. Unset fields keep the base look's.
 */
@Serializable
@SerialName("part_change")
data class PartChange(
    val part: String,
    val color: String? = null,
    val glow: Double? = null,
    @SerialName("rot_x") val rotX: Double? = null,
    @SerialName("rot_y") val rotY: Double? = null,
    @SerialName("rot_z") val rotZ: Double? = null,
    val scale: Double? = null,
    val hidden: Boolean = false,
) {
    companion object {
        const val MIN_SCALE = 0.2
        const val MAX_SCALE = 3.0
    }
}

/** A book he wrote: [chapters] are a sentence each, and [skill] is the one skill reading it raises, if any. */
@Serializable
@SerialName("book_design")
data class BookDesign(val title: String, val blurb: String, val chapters: List<String>, val skill: String? = null)

/**
 * One shape of an invention's look: the fields of a part list's part (`parts/Part.kt`) a model may
 * set. Metres, the model's pivot at the bottom centre, front facing +Z; ([x], [y], [z]) is the
 * bottom centre of the shape, and rotations are degrees. The clay fields after [color] are the ones
 * the room's own furniture is made with; a look saved before them gets their defaults.
 */
@Serializable
@SerialName("look_part")
data class LookPart(
    val shape: String,
    val w: Double,
    val h: Double,
    val d: Double,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    @SerialName("rot_x") val rotX: Double = 0.0,
    @SerialName("rot_y") val rotY: Double = 0.0,
    @SerialName("rot_z") val rotZ: Double = 0.0,
    val color: String = "#ffffff",
    /** What this shape is of the thing ("rim", "handle"): the writer names each so it builds the thing up from its parts. */
    val part: String = "",
    /** Cylinders only: the top's width as a fraction of the bottom's (a vase, a lampshade, a spout). */
    val taper: Double = 1.0,
    /** Spheres only: the part of the height that's there, from the top; 0.5 is a dome. */
    val cut: Double = 1.0,
    /** Cylinders only: the hole's width as a fraction of the outside's, for a ring, a rim or a tube. */
    val hole: Double = 0.0,
    /** Cylinders only: the degrees of the ring that are there, centred on the front; 180 is a handle or an arch. */
    val arc: Double = 360.0,
    /** How brightly it glows at night: 1 is a lamp's, 0 not at all. */
    val glow: Double = 0.0,
    /** 1 for solid, less for glass. */
    val opacity: Double = 1.0,
) {
    companion object {
        val SHAPES = listOf("box", "sphere", "cylinder", "capsule")
        const val MAX_TAPER = 3.0
        const val MIN_CUT = 0.1
        const val MAX_HOLE = 0.95
        const val MIN_ARC = 10.0
        const val MIN_OPACITY = 0.2
    }
}

/**
 * An invention that was built: the [item] it became (and its [book]), its [look], what the
 * materials [cost], [why] he made it, and the [tick] he finished. A run's inventions are saved with
 * the world ([BedroomState.inventions]) and put back into the content when it's restored.
 */
@Serializable
data class Invention(
    val item: ItemSpec,
    val book: BookSpec? = null,
    val look: List<LookPart> = emptyList(),
    val cost: Int,
    val why: String = "",
    val tick: Long = 0,
    /** Its other looks ([Design.variants]); empty for one saved before they came. */
    val variants: Map<String, List<PartChange>> = emptyMap(),
)

/**
 * What he may invent and what it costs (rules.yaml `invent`). An invention is one item with one
 * use and some qualities; the sim prices its materials, so he can't design something that pays
 * more than it costs. [uses]: the affordances open to him, each with the group its things belong
 * to and its materials cost: `base` plus `per_strength` a point of quality strength. [perDay]: how
 * many he may build a game day. [wagePerHour]: a thing made to sell sells for its materials plus
 * this an hour of the build.
 */
@Serializable
data class InventRules(
    @SerialName("per_day") val perDay: Int = 1,
    val qualities: QualityBudget = QualityBudget(),
    val uses: Map<String, Use>,
    val book: BookBudget = BookBudget(),
    @SerialName("wage_per_hour") val wagePerHour: Int = 0,
    /** At most this many shapes in a look. */
    @SerialName("look_parts") val lookParts: Int = 12,
) {
    @Serializable
    data class QualityBudget(val most: Int = 3, val total: Double = 6.0)

    @Serializable
    data class Use(val group: String, val base: Int, @SerialName("per_strength") val perStrength: Int)

    /** [chapters]: the fewest and the most. [skillGain]: what each chapter adds to the book's skill. */
    @Serializable
    data class BookBudget(@SerialName("min_chapters") val minChapters: Int = 3, @SerialName("max_chapters") val maxChapters: Int = 5, @SerialName("skill_gain") val skillGain: Double = 0.25)

    /** The least any design's materials cost. */
    val cheapest: Int get() = uses.values.minOf { it.base }

    /** What a design with [use] and [qualities] costs in materials. */
    fun cost(use: Use, qualities: Map<String, Double>): Int = use.base + (use.perStrength * qualities.values.sum()).roundToInt()

    /** The room's spare spots of [place] (`floor` or `wall`) that nothing in [c] stands in. */
    fun freeSpots(c: BedroomContent, place: String): List<String> {
        val taken = c.items.mapNotNull { it.slot }.toSet()
        return (if (place == WALL) c.room.spots.wall else c.room.spots.floor).filter { it !in taken }
    }

    /**
     * [design] as an item of [c], or why it can't be built. [minutes]: how long the build takes,
     * for what a thing made to sell is worth.
     */
    fun check(design: Design, c: BedroomContent, minutes: Int): Checked {
        val problems = mutableListOf<String>()
        val name = design.name.trim().replace(Regex("\\s+"), " ")
        if (name.length !in 2..40 || !name.matches(NAME)) problems += "its name must be 2 to 40 letters, digits, spaces, hyphens or apostrophes"
        if (c.items.any { it.name.equals(name, ignoreCase = true) }) problems += "there's already a thing called '$name'"
        val use = uses[design.use]
        if (use == null) problems += closed(design.use, c)
        design.qualities.forEach { (q, s) ->
            if (q !in c.vocabulary.qualities) problems += "'$q' isn't a quality; they are ${c.vocabulary.qualities.joinToString()}"
            else if (s != s.roundToInt().toDouble() || s < 1 || s > QualityValue.MAX_STRENGTH) problems += "$q needs a whole strength from 1 to ${QualityValue.MAX_STRENGTH.toInt()}"
        }
        if (design.qualities.size > qualities.most) problems += "at most ${qualities.most} qualities"
        if (design.qualities.values.sum() > qualities.total) problems += "its qualities' strengths can add up to at most ${signed(qualities.total).removePrefix("+")}"
        if (design.look.size > lookParts) problems += "its look can have at most $lookParts shapes"
        design.look.forEachIndexed { i, p -> partProblem(p)?.let { problems += "shape ${i + 1} of its look: $it" } }
        problems += variantProblems(design)
        var slot: String? = null
        if (design.use == VIEWABLE) {
            when (design.place) {
                FLOOR, WALL -> slot = freeSpots(c, design.place).firstOrNull() ?: null.also { problems += "there's no free spot left on the ${design.place}" }
                else -> problems += "a thing to look at needs a place: $FLOOR or $WALL"
            }
        }
        var book: BookDesign? = null
        if (design.use == READABLE) {
            book = design.book
            if (book == null) problems += "a thing to read needs a book: a title, a blurb and chapters"
            else {
                if (book.title.isBlank() || book.blurb.isBlank()) problems += "the book needs a title and a blurb"
                if (book.chapters.size !in this.book.minChapters..this.book.maxChapters || book.chapters.any { it.isBlank() }) problems += "the book needs ${this.book.minChapters} to ${this.book.maxChapters} chapters, a sentence each"
                if (book.skill != null && book.skill !in c.vocabulary.skills) problems += "'${book.skill}' isn't a skill; they are ${c.vocabulary.skills.joinToString()}"
            }
        }
        if (problems.isNotEmpty() || use == null) return Checked.Refused(problems)

        val taken = (c.items.map { it.id } + c.verbs.map { it.id } + c.vocabulary.affords).toSet()
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "thing" }
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) slug else "${slug}_$it" }.first { it !in taken }
        val cost = cost(use, design.qualities)
        val params = if (design.use == SELLABLE) AffordanceParams(price = cost + (wagePerHour * minutes / 60.0).roundToInt()) else AffordanceParams()
        val item = ItemSpec(
            id = id,
            name = name,
            group = use.group,
            slot = slot,
            blocks = slot != null,
            // Food is the one thing the shop will send more of, at what the materials cost.
            price = cost.takeIf { design.use == EDIBLE },
            stackable = design.use == EDIBLE,
            affords = mapOf(design.use to params),
            qualities = design.qualities,
        )
        val spec = book?.let { BookSpec(id, it.title.trim(), it.blurb.trim(), it.chapters.map(String::trim), it.skill?.let { s -> mapOf(s to this.book.skillGain) }.orEmpty()) }
        return Checked.Ok(Invention(item, spec, design.look, cost, design.why.trim(), variants = design.variants.mapKeys { it.key.trim() }))
    }

    /** Why [use] isn't open to him, in words he can act on. */
    private fun closed(use: String, c: BedroomContent): String {
        val open = "what you can make is ${uses.keys.joinToString(" or ")}"
        return if (use in c.vocabulary.affords) "nothing you could build at a workbench is '$use' yet; $open" else "'$use' isn't something a thing can be; $open"
    }

    /** What's wrong with one shape of a look, in words he can act on, or null when it's fine. */
    fun partProblem(p: LookPart): String? = when {
        p.shape !in LookPart.SHAPES -> "'${p.shape}' isn't a shape; they are ${LookPart.SHAPES.joinToString()}"
        listOf(p.w, p.h, p.d).any { it.isNaN() || it < MIN_SIZE || it > MAX_SIZE } -> "each of w, h and d must be from $MIN_SIZE to $MAX_SIZE"
        listOf(p.x, p.z).any { it.isNaN() || it < -REACH || it > REACH } || p.y.isNaN() || p.y < 0 || p.y > MAX_SIZE -> "it must sit inside one tile: x and z from -$REACH to $REACH, y from 0 to $MAX_SIZE"
        listOf(p.rotX, p.rotY, p.rotZ).any { it.isNaN() || it < -360 || it > 360 } -> "rotations are degrees from -360 to 360"
        !p.color.matches(COLOR) -> "its color must be hex, like #c96f4a"
        p.taper.isNaN() || p.taper < 0 || p.taper > LookPart.MAX_TAPER -> "taper is from 0 to ${LookPart.MAX_TAPER}"
        p.cut.isNaN() || p.cut < LookPart.MIN_CUT || p.cut > 1 -> "cut is from ${LookPart.MIN_CUT} to 1"
        p.hole.isNaN() || p.hole < 0 || p.hole > LookPart.MAX_HOLE -> "hole is from 0 to ${LookPart.MAX_HOLE}"
        p.arc.isNaN() || p.arc < LookPart.MIN_ARC || p.arc > 360 -> "arc is from ${LookPart.MIN_ARC.toInt()} to 360 degrees"
        p.glow.isNaN() || p.glow < 0 || p.glow > 1 -> "glow is from 0 to 1"
        p.opacity.isNaN() || p.opacity < LookPart.MIN_OPACITY || p.opacity > 1 -> "opacity is from ${LookPart.MIN_OPACITY} to 1"
        else -> null
    }

    /** What's wrong with [design]'s variants: names, how many, and that each change names a part of the look and stays in range. */
    fun variantProblems(design: Design): List<String> {
        if (design.variants.isEmpty()) return emptyList()
        val problems = mutableListOf<String>()
        val parts = design.look.map { it.part }.filter { it.isNotBlank() }.toSet()
        if (design.variants.size > MAX_VARIANTS) problems += "at most $MAX_VARIANTS variants"
        design.variants.forEach { (name, changes) ->
            val v = "variant '$name'"
            if (!name.trim().matches(VARIANT)) problems += "$v: a variant's name is 1 to 20 lowercase letters, digits or underscores, like spraying"
            if (changes.isEmpty() || changes.size > lookParts) problems += "$v: it changes 1 to $lookParts parts"
            changes.forEach { c ->
                when {
                    c.part !in parts -> problems += "$v: '${c.part}' isn't a part of its look" + if (parts.isEmpty()) " (name the shapes' parts, like \"head\")" else "; its parts are ${parts.joinToString()}"
                    c.color != null && !c.color.matches(COLOR) -> problems += "$v: ${c.part}'s color must be hex, like #c96f4a"
                    c.glow != null && (c.glow.isNaN() || c.glow < 0 || c.glow > 1) -> problems += "$v: ${c.part}'s glow is from 0 to 1"
                    listOfNotNull(c.rotX, c.rotY, c.rotZ).any { it.isNaN() || it < -360 || it > 360 } -> problems += "$v: ${c.part}'s rotations are degrees from -360 to 360"
                    c.scale != null && (c.scale.isNaN() || c.scale < PartChange.MIN_SCALE || c.scale > PartChange.MAX_SCALE) -> problems += "$v: ${c.part}'s scale is from ${PartChange.MIN_SCALE} to ${PartChange.MAX_SCALE}"
                }
            }
        }
        return problems
    }

    /** What's wrong with these rules in [c], as messages. */
    internal fun problems(c: BedroomContent): List<String> = listOfNotNull(
        if (perDay < 1) "invent: per_day must be at least 1" else null,
        if (uses.isEmpty()) "invent: `uses` opens nothing" else null,
        if (wagePerHour < 0 || lookParts < 1 || qualities.most < 0 || qualities.total < 0) "invent: wage_per_hour, look_parts and the quality budget can't be negative" else null,
        if (book.minChapters < 1 || book.maxChapters < book.minChapters || book.skillGain < 0) "invent.book: chapters at least 1, max not below min, skill_gain not negative" else null,
    ) + uses.flatMap { (use, u) ->
        listOfNotNull(
            if (use !in OPEN) "invent: '$use' can't be opened; the uses a built thing can have are ${OPEN.joinToString()}" else null,
            if (use !in c.vocabulary.affords) "invent: '$use' isn't in vocabulary.affords" else null,
            if (u.base < 1 || u.perStrength < 0) "invent.uses.$use: base at least 1, per_strength not negative" else null,
        )
    }

    /** What [check] made of a design. */
    sealed interface Checked {
        data class Ok(val invention: Invention) : Checked
        data class Refused(val problems: List<String>) : Checked
    }

    companion object {
        const val FLOOR = "floor"
        const val WALL = "wall"
        private const val VIEWABLE = "viewable"
        private const val READABLE = "readable"
        private const val EDIBLE = "edible"
        private const val SELLABLE = "sellable"

        /**
         * The affordances a built thing can have: the ones some verb uses on a plain owned item. The
         * rest wait: `staple` is free food forever, `hangable` and `drawable` belong to art pieces,
         * which verbs make, and nothing uses `combinable` or `wearable` yet.
         */
        val OPEN = listOf(VIEWABLE, "playable", READABLE, EDIBLE, SELLABLE)

        private val NAME = Regex("[\\p{L}\\p{N} '\\-]+")
        private val COLOR = Regex("#[0-9a-fA-F]{6}")
        private const val MIN_SIZE = 0.02
        private const val MAX_SIZE = 1.5
        private const val REACH = 0.5
        const val MAX_VARIANTS = 6
        private val VARIANT = Regex("[a-z0-9_]{1,20}")
    }
}
