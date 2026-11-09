package dev.lilguy.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bumped whenever a message changes shape in a way an older client would misread. A client says
 * which version it speaks when it joins (`?v=`); the host refuses a different one ([Refused])
 * rather than send it deltas it can't apply.
 */
const val PROTOCOL_VERSION = 2

/**
 * The JSON every message travels as. Every property is always written, nulls and defaults
 * included, so the generated TypeScript has no optional fields; unknown keys are ignored, so the
 * host can add a field without breaking a client of the same version.
 */
object Wire {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(m: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), m)
    fun server(text: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), text)
    fun encode(c: Command): String = json.encodeToString(Command.serializer(), c)
    fun command(text: String): Command = json.decodeFromString(Command.serializer(), text)
    fun encode(r: CommandResult): String = json.encodeToString(CommandResult.serializer(), r)
    fun result(text: String): CommandResult = json.decodeFromString(CommandResult.serializer(), text)
    fun encode(v: Viewer): String = json.encodeToString(Viewer.serializer(), v)
}

/**
 * What the host streams on `/v1/lives/{life}/stream`. In order on one socket: [Welcome] (or
 * [Refused] and the socket closes), [Content] unless the client said it has that hash, [Snapshot],
 * then a [Delta] whenever something changed. A new run (restart, replay, resume) sends [Content]
 * if it changed and a fresh [Snapshot] instead of a delta.
 */
@Serializable
sealed interface ServerMessage

/** [caps]: how many of each log's newest entries a client keeps ([Logs.CAPS]), so clients needn't hard-code them. */
@Serializable
@SerialName("welcome")
data class Welcome(val version: Int, val lives: List<LifeSummary>, val caps: Map<String, Int> = Logs.CAPS) : ServerMessage

@Serializable
data class LifeSummary(val id: String, val scenario: String, @SerialName("run_id") val runId: String, val state: RunState)

@Serializable
@SerialName("refused")
data class Refused(val version: Int, val reason: String) : ServerMessage

/**
 * What doesn't change within a content version, sent once and cached by [hash]: for the bedroom
 * the hash `:runlog` keeps the content files under (`content_versions`); for rooms without
 * content files, the hash of this message's own JSON.
 */
@Serializable
@SerialName("content")
data class Content(
    val hash: String,
    val scenario: String,
    val map: MapView,
    /** Everything the shop could sell, in file order; what changes (owned, affordable) is in [ShopState]. */
    val catalog: List<CatalogItem>,
    /** Every verb in the content, in file order. */
    val verbs: List<VerbInfo>,
) : ServerMessage

/** The room's terrain, one string per row: `#` wall, `.` floor, space for outside. Tile (x, y) spans [x, x+1) by [y, y+1). */
@Serializable
data class MapView(val width: Int, val height: Int, val rows: List<String>)

@Serializable
data class CatalogItem(val id: String, val name: String, val group: String, val price: Int, val pack: Int, val stackable: Boolean)

@Serializable
data class VerbInfo(val id: String, val phrase: String?)

/** The whole state of one life: what a client keeps, and what [Delta]s change. */
@Serializable
@SerialName("snapshot")
data class Snapshot(val state: LifeState) : ServerMessage

/**
 * What changed since the previous message for [life], at [seq] (one more than the last). A
 * section that's null is unchanged; one that's set replaces the client's whole. [logs] holds the
 * entries new or changed since, which the client merges by their `seq` ([Logs.merge]).
 * Fast state comes about ten times a second while anything moves; slow sections only when they change.
 */
@Serializable
@SerialName("delta")
data class Delta(
    val life: String,
    val seq: Long,
    val run: RunInfo? = null,
    val fast: FastState? = null,
    val things: Things? = null,
    val easel: Easel? = null,
    val wallet: Wallet? = null,
    val growth: Growth? = null,
    val gallery: Gallery? = null,
    val shop: ShopState? = null,
    val mind: Mind? = null,
    val tokens: Tokens? = null,
    val logs: Logs? = null,
) : ServerMessage

/**
 * What an admin sends to `POST /v1/lives/{life}/commands`, with the admin's credentials. The
 * host runs it on the life's own thread, between ticks, and answers with a [CommandResult].
 */
@Serializable
sealed interface Command {
    /** Starts a ready run, or resumes a paused one. */
    @Serializable @SerialName("start") data object Start : Command

    @Serializable @SerialName("pause") data object Pause : Command

    /** Throws the run away for a fresh one with the current settings, which waits for start. */
    @Serializable @SerialName("restart") data object Restart : Command

    /** Throws the run away for a fresh one set up as run [run] (1-based, since the host started) started. */
    @Serializable @SerialName("replay") data class Replay(val run: Int) : Command

    /** New settings: days, speed, the decision cap and presentation timing take hold now, the resolution from the next run. */
    @Serializable @SerialName("configure") data class Configure(val settings: Settings) : Command

    /** Throws the run away and carries run [run] (an id, or "latest") on from its latest checkpoint; it waits for start. */
    @Serializable @SerialName("resume") data class Resume(val run: String) : Command

    /** Runs [ticks] ticks by hand, only while paused. */
    @Serializable @SerialName("step") data class Step(val ticks: Int) : Command
}

/** [error] says why a command was refused or failed; [state] is the run's state after it. */
@Serializable
data class CommandResult(val ok: Boolean, val error: String? = null, val seq: Long? = null, val state: RunState? = null)

/** Who is asking, from `GET /auth/me`: whether the caller's credentials may send commands. */
@Serializable
data class Viewer(val admin: Boolean)
