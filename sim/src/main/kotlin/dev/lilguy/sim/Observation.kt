package dev.lilguy.sim

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * One character's view of the world, rendered as JSON for typed prompts and logs. The fields
 * after [recentEvents] are the bedroom's life state; rooms without one leave them null, and null
 * ones are left out of the JSON, so the key-and-door observation reads as it did.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Observation(
    val tick: Long,
    val self: SelfState,
    val nearby: List<SeenEntity>,
    /** Small local map, one string per row, centered on the character (in the bedroom, the room he's in). */
    val localMap: List<String>,
    val lastIntent: Intent?,
    val lastResult: IntentStatus?,
    val recentEvents: List<String>,
    /** Where he is: the place, and the nearest thing to him in it ("your bedroom, by the desk"); null in rooms without places. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val place: String? = null,
    /**
     * How the light is round him, in a few landmark phrases ("lit by the floor lamp (light 5): the bed and
     * the chair"; "too dark to see the rest here"); null when everything near him is lit enough for anything.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val light: List<String>? = null,
    /**
     * What's round him in this place, nearest first, in a few words each ("bench: 2 tiles north-west, within reach"):
     * [nearby] said briefly, for a model that calls tools; it sees no [nearby]. Null for every other model.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val here: List<String>? = null,
    /**
     * What he feels of warmth and sound where he is, only when it limits or explains something
     * ("cold here (4°): your mood and energy fall", "you can't hear the jazz record from here",
     * "the bed is cold (14°): you'd sleep less well tonight").
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val senses: List<String>? = null,
    /** The other places he could walk to, nearest first, with the walk from here; null in rooms without places. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val places: List<PlaceOption>? = null,
    /** What of his he could put somewhere else, and the free spots by place ([Intent.Put]); null when there's nothing to move or nowhere to put it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val put: PutOptions? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clock: ClockReading? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val money: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val needs: NeedLevels? = null,
    /** Carried items, one entry each: art waiting to be sold. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val holding: List<String>? = null,
    /** Everything else he owns, item id to count. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val owns: Map<String, Int>? = null,
    /** What's playing in the background, such as "jazz_record, 32 min left". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val music: String? = null,
    /**
     * Every do() verb: available ones with what they take and give, one per way to fill their
     * arguments (`what`, `on`) with things he owns; locked ones once, with why.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val activities: List<ActivityOption>? = null,
    /** Everything for sale he doesn't already have. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shop: List<ShopOption>? = null,
    /** The art he has made and still has, held or hung, oldest first. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val art: List<PieceView>? = null,
    /** His skills, name to level (0 up, one decimal). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val skills: Map<String, Double>? = null,
    /** The books he owns and how far he has read. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val books: List<BookView>? = null,
    /**
     * What he's been doing: what he did most in the last two days, and what he could do now but hasn't
     * for two days or more, or ever. So a run of the same few things is something he can see.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lately: List<String>? = null,
    /**
     * His machines that want him, wherever they are: jammed, holding what they made, or empty while he
     * has what they take ("the kiln (the garden) is empty, and you have 4 clays to put in (tend)"); null when none do.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tending: List<String>? = null,
    /** Authored ops waiting for (or given) their results, for the agent layer; never shown to models. */
    @Transient val authored: List<AuthoredView> = emptyList(),
    /**
     * How [lastIntent] ended, in the sim's words: what its ops did when it finished, a purchase, or
     * why it failed. Null while it runs or when nothing was said (a wait). For memory; models read
     * [lastResult] and [recentEvents].
     */
    @Transient val outcome: String? = null,
    /** [recentEvents] without the outcomes: deliveries, requests, the day changing. For memory. */
    @Transient val news: List<String> = emptyList(),
    /** The place each line of [news] happened in, by place name, for the lines that say ([WorldEvent.place]). For memory. */
    @Transient val newsPlaces: Map<String, String> = emptyMap(),
    /** The tile at [localMap]'s top-left, where the map is part of a bigger world; null when it's all of it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val localMapAt: Cell? = null,
    /** The weather as he sees it now ("raining, cold"; indoors only the sky through the window); null without weather. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val weather: String? = null,
)

/** A place he could go to: its id (for `move_to` a place), its name, what it is, and the game minutes to walk there. */
@Serializable
data class PlaceOption(val id: String, val name: String, val says: String, val walk: Int)

/**
 * A piece of art he made and hasn't sold: [hung] on the wall, or held to sell for [sellsFor].
 * [title] is null until it has been drawn (or when drawing failed).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PieceView(
    val id: Int,
    val item: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    val hung: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sellsFor: Int? = null,
)

/**
 * A book he owns: [read] of [chapters] chapters so far. [raises]: the skills studying it raises.
 * [teaches]: what finishing it would teach him, while there's some left to learn. [next]: the
 * next chapter's text, for the agent layer (what he reads next); never shown to models.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookView(
    val id: String,
    val title: String,
    val read: Int,
    val chapters: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val raises: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val teaches: String? = null,
    @Transient val next: String? = null,
)

/**
 * A verb for do(), with the items its arguments are bound to ([what], [on]) when it has any.
 * Exactly one of [locked] and ([takes], [gives]) is set.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ActivityOption(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val what: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val on: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val takes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gives: String? = null,
    /** Why it can't be done now, such as "needs an easel ($120)". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: String? = null,
    /** The place it's done in, when that's somewhere else than where he is, and the game minutes it takes to walk there. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val where: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val walk: Int? = null,
)

@Serializable
data class ShopOption(val id: String, val price: Int, val affordable: Boolean, val gives: String)

/** An [invention] for screens. [wall]: it hangs in a wall rather than standing on the floor. */
data class InventedView(val invention: Invention, val wall: Boolean)

/**
 * One item the shop sells, for screens rather than models: everything priced, owned or not, with
 * [gives] in the shop's words. [taughtBy] names the book that teaches it while he doesn't know it
 * yet ("the drawing book, \"Drawing What You See\""); the shop doesn't offer it until then.
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val group: String,
    val price: Int,
    val pack: Int,
    /** Bought again and again (meals, canvas) rather than once. */
    val stackable: Boolean,
    val owned: Int,
    val affordable: Boolean,
    val gives: String,
    val taughtBy: String? = null,
)

/**
 * One verb, for screens: [locked] says why it can't be done now (or that a book has yet to teach
 * it, [taughtBy]); otherwise [gives] is what its first open option takes and gives.
 */
data class VerbView(val id: String, val phrase: String?, val locked: String?, val gives: String?, val taughtBy: String? = null)

/** How often he has finished a verb this run, and when he first and last did. */
@Serializable
data class VerbTally(val count: Int, val firstTick: Long, val lastTick: Long = firstTick)

@Serializable
data class SelfState(
    val id: EntityId,
    val x: Float,
    val y: Float,
    val facing: Direction,
    val inventory: List<String>,
)

@Serializable
data class SeenEntity(
    val id: String,
    val kind: String,
    /** The tile it stands on, so a model can target tiles around it without arithmetic. */
    val x: Int,
    val y: Int,
    val direction: Direction,
    val distance: Float,
    /** Kind-specific state, such as "locked" or "open" for a door. */
    val state: String? = null,
    /** On or beside your tile, so pick_up and use can reach it. Computed so models needn't. */
    val inReach: Boolean = false,
    /** Too dark to see: he knows it's there (his house's things, what's in a place he explored), but not how it is. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val remembered: Boolean = false,
)

/** Compass directions; north is toward row 0. */
@Serializable
enum class Direction(val degrees: Float) {
    N(90f), NE(45f), E(0f), SE(315f), S(270f), SW(225f), W(180f), NW(135f);

    companion object {
        private val BY_SECTOR = arrayOf(E, NE, N, NW, W, SW, S, SE)

        /** Nearest compass direction of the vector (dx, dy) in grid space (y grows south). */
        fun of(dx: Float, dy: Float): Direction {
            val degrees = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
            return BY_SECTOR[Math.floorMod((degrees / 45.0).roundToInt(), 8)]
        }
    }
}

/** Immutable world state; the renderer interpolates between two of these. */
data class WorldSnapshot(
    val tick: Long,
    val map: TileMap,
    val entities: List<EntitySnapshot>,
    val agents: List<AgentSnapshot> = emptyList(),
    /** Oldest first. */
    val events: List<WorldEvent> = emptyList(),
    /** True once the character has walked out of the room. */
    val completed: Boolean = false,
    /** The bedroom's clock, money and needs; null in rooms without them. */
    val life: LifeSnapshot? = null,
    /** The places on [map] and how they join; null in rooms without places. */
    val layout: Layout? = null,
)

/** Clock, money and needs for HUDs, at full precision. */
data class LifeSnapshot(
    val clock: ClockReading,
    val money: Int,
    val energy: Double,
    val fullness: Double,
    val mood: Double,
    /** Low is bored. */
    val stimulation: Double,
    /** High is frustrated. */
    val frustration: Double = 0.0,
    /** Skill name to level. */
    val skills: Map<String, Double> = emptyMap(),
    /** The books he owns and how far he has read. */
    val books: List<BookView> = emptyList(),
    /** The mood his mood drifts toward: the baseline plus the room's comfort, less boredom and frustration. */
    val moodTarget: Double,
    /** The activity under way (after any walk to it), and how far through it is, 0..1. */
    val doing: String?,
    val progress: Float?,
    val music: String?,
    val holding: List<String>,
    val owns: Map<String, Int>,
    /** Newest last. */
    val transactions: List<Transaction>,
    /** The clock that turns this world's ticks into game time, for stamping events. */
    val clockScale: GameClock,
    /** Held and hung art, oldest first. */
    val art: List<PieceView> = emptyList(),
    /** Everything the shop sells, in file order, including what a book has yet to teach. */
    val catalog: List<CatalogEntry> = emptyList(),
    /** What he has invented, oldest first: the things a renderer has no model or name for but these. */
    val inventions: List<InventedView> = emptyList(),
    /** Every verb in the content, in file order. */
    val verbs: List<VerbView> = emptyList(),
    /** Verb id to how often he has finished it this run. */
    val done: Map<String, VerbTally> = emptyMap(),
    /** The verbs he wrote, oldest first. */
    val coded: List<CodedVerb> = emptyList(),
    /** What his things do by themselves, item id to its `hourly` code. */
    val behaviors: Map<String, ThingCode> = emptyMap(),
    /** What his code stored on things: item id (or `self`) to key to the value as JSON. */
    val stored: Map<String, Map<String, String>> = emptyMap(),
    /** What his code changed about his things (where they stand, their names and colours), by item id. */
    val custom: Map<String, BedroomState.Custom> = emptyMap(),
    /** The weather now, for drawing it; null without weather. */
    val weather: WeatherView? = null,
    /** Each thing standing on the map as an inspector shows it, in the order they stand. */
    val gadgets: List<GadgetView> = emptyList(),
)

/**
 * One of his things as an inspector shows it, for screens: what its content says it does ([about]),
 * what's live in it ([state], label to value), what's in it ([holds]), and the verbs it's for ([uses]), by its [name].
 * Only what the sim knows and a renderer can't see from the rest of the snapshot.
 */
data class GadgetView(
    val id: String,
    /** What it's called: what his code named it, else its own name. */
    val name: String,
    val about: List<String>,
    val state: List<Pair<String, String>>,
    val holds: List<HeldView>,
    val uses: List<UseView>,
)

/** Something in a thing: [what] ("3 food scraps") and a [detail] ("put in, for composting"), or null. */
data class HeldView(val what: String, val detail: String?)

/** A verb a thing is for: open, with what it takes and [gives] ("12 min: +stimulation"), or [locked] with why. */
data class UseView(val verb: String, val phrase: String?, val gives: String?, val locked: String?)

data class EntitySnapshot(
    val id: EntityId,
    val kind: String,
    /** Grid space: tile (x, y) spans [x, x+1) by [y, y+1). */
    val x: Float,
    val y: Float,
    val height: Float,
    val facingDegrees: Float,
    val state: String? = null,
    /** Set while the entity is in someone's inventory; such entities aren't on the map. */
    val carriedBy: EntityId? = null,
    /** The place it's in ([Layout.places]), or null in rooms without places. */
    val place: String? = null,
    /** The colour his code painted it, hex, or null for its own colours. */
    val color: String? = null,
    /** The variant of its design his code shows ([ThingLook]), null for its own look. */
    val variant: String? = null,
    /** Its parts his code set moving. */
    val motions: List<Motion> = emptyList(),
    /** The short label his code floats over it. */
    val readout: String? = null,
    /** The light it gives off, or null for none. */
    val light: LightSpec? = null,
)

/** What a character's controller is doing, for HUDs and debug views. */
data class AgentSnapshot(
    val id: EntityId,
    val intent: Intent?,
    val status: IntentStatus?,
    val inventory: List<String>,
    /** Remaining waypoints of the current move, nearest first. */
    val path: List<Cell>,
    /** Speech bubble text while a Say is showing. */
    val saying: String?,
)

/**
 * [outcome]: it says how an intent ended (a finish, a purchase, a failure) rather than what happened
 * around him. [place]: the id of the place it happened in ([Layout.places]), when that isn't simply
 * where he is: a machine of his acting in its own place. Memory files it there.
 */
data class WorldEvent(val tick: Long, val text: String, val outcome: Boolean = false, val place: String? = null)
