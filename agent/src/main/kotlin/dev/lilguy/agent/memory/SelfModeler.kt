package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How he sees himself, in his own words, in fixed sections so it stays usable. */
@Serializable
@SerialName("self")
data class SelfModel(
    val who: String,
    val cares: String,
    val believes: String,
    @SerialName("working_toward") val workingToward: String,
    val learned: String,
) {
    /** One line per section, for prompts and files. */
    fun render(): String = SECTIONS.zip(listOf(who, cares, believes, workingToward, learned))
        .filter { it.second.isNotBlank() }
        .joinToString("\n") { (name, text) -> "- $name: ${text.trim()}" }

    val words: Int get() = listOf(who, cares, believes, workingToward, learned).sumOf { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } }

    companion object {
        val SECTIONS = listOf("Who I am", "What I care about", "What I believe about my world", "What I'm working toward", "What I've learned about myself")
    }
}

/** One stored version: the self-model and what changed in it and why. */
@Serializable
data class SelfVersion(val self: SelfModel, val note: String) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun decode(text: String): SelfVersion = json.decodeFromString(serializer(), text)
    }
}

@Serializable
@SerialName("update")
data class SelfUpdate(val changed: Boolean, val self: SelfModel, val note: String)

/** One night's revision, for logs and the dev view; [version] is null when nothing changed or the call failed ([error]). */
data class SelfRevision(val day: Int?, val changed: Boolean, val note: String?, val version: StoredMemory?, val error: String? = null)

/**
 * His self-model (level 3 of memory), revised once a night: after he goes to sleep and the day's
 * memories become a day memory, one text-model call reads the fixed core (what is true by design,
 * never his to edit), the current self-model, the new day memory and the few before it, and his
 * thoughts from the day, and returns the self-model with whatever the day gives reason to change.
 * "Nothing changed" is a valid answer and probably the common one.
 *
 * Every changed version is a level-3 memory ([MemoryStore.addMemory]) covering the day memories it
 * read; the latest is current, and the rows are the history of how he changed. Stability: a low
 * temperature, a hard word cap, fixed sections, and the core out of reach. The summarizer never sees
 * the self-model, so memories stay the evidence and this the interpretation.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the writer.
 */
class SelfModeler(
    private val model: DecisionModel,
    private val store: MemoryStore,
    private val run: String,
    /** What is true of him by design, in the second person. */
    private val core: String,
    /** What he knows of himself before the first night (content's `self_seed`). */
    val seed: String = SEED,
    /** Day memories before the new one it reads, so one odd day isn't the whole picture. */
    private val pastDays: Int = 3,
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "the self-model needs a model that writes text; '${model.id}' only picks from options" }
    }

    val writer: String get() = model.id

    private val schema = ActionSchema.of(
        "update",
        SelfUpdate.serializer(),
        SchemaHints(
            descriptions = mapOf(
                "update.changed" to "False when today gives no reason to change it; then return it as it was.",
                "update.self" to "Your self-model, in your own words, first person, under $WORD_CAP words in all.",
                "self.who" to "How you would describe yourself.",
                "self.cares" to "What you value and enjoy.",
                "self.believes" to "How your world works: what pays off and what doesn't.",
                "self.working_toward" to "Your current aims.",
                "self.learned" to "What you've learned about yourself.",
                "update.note" to "What changed and why, naming what happened today; or why nothing did.",
            ),
        ),
    ) { null }

    /** The current version, or null before the first. */
    fun current(): SelfVersion? = store.memories(run, 3).lastOrNull()?.let { runCatching { SelfVersion.decode(it.text) }.getOrNull() }

    /** Every version so far, oldest first, with when it was written. */
    fun versions(): List<Pair<StoredMemory, SelfVersion>> =
        store.memories(run, 3).mapNotNull { m -> runCatching { m to SelfVersion.decode(m.text) }.getOrNull() }

    /**
     * Revises the self-model from [day], the day memory just written. [thoughts] are his thoughts
     * from that day. Never throws for a model failure: the revision says what went wrong.
     */
    suspend fun revise(day: StoredMemory, thoughts: List<String>): SelfRevision {
        val before = current()
        val past = store.memories(run, 2).filter { it.id < day.id }.takeLast(pastDays)
        val user = buildString {
            appendLine("Your self-model as it stands" + (if (before == null) " (you haven't written one yet; start from what today shows):" else ":"))
            appendLine(before?.self?.render() ?: seed)
            appendLine()
            if (past.isNotEmpty()) {
                appendLine("The days before, oldest first:")
                past.forEach { appendLine("- ${it.span.label}: ${it.text}") }
                appendLine()
            }
            appendLine("Today (${day.span.label}):")
            appendLine(day.text)
            if (thoughts.isNotEmpty()) {
                appendLine()
                appendLine("What you thought today, oldest first:")
                thoughts.forEach { appendLine("- \"$it\"") }
            }
            appendLine()
            append("Revise your self-model if today gives you reason to.")
        }
        val request = DecisionRequest(system = system(core), messages = listOf(Message(Message.Role.User, user)), temperature = TEMPERATURE, timeout = timeout)
        val update = try {
            withTimeout(timeout) { model.decide(request, schema) }.value
        } catch (e: TimeoutCancellationException) {
            return SelfRevision(day.span.toDay, false, null, null, "timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SelfRevision(day.span.toDay, false, null, null, e.message ?: e.toString())
        }
        if (!update.changed && before != null) return SelfRevision(day.span.toDay, false, update.note, null)
        if (update.self.words > WORD_CAP * 3 / 2) return SelfRevision(day.span.toDay, false, update.note, null, "the self-model ran to ${update.self.words} words; the cap is $WORD_CAP")
        val version = SelfVersion(update.self, update.note)
        val from = past.firstOrNull()?.id ?: day.id
        val id = store.addMemory(run, 3, from, day.id, day.span, version.encode(), model.id)
        return SelfRevision(day.span.toDay, true, update.note, StoredMemory(id, run, 3, from, day.id, day.span, version.encode(), model.id))
    }

    companion object {
        const val WORD_CAP = 180
        const val TEMPERATURE = 0.3

        /** What he knows of himself before the first night, when content gives no seed. */
        const val SEED = "You don't know much about yourself yet."

        fun system(core: String) = """
            |$core
            |
            |It is night and you are going over your day. You keep a short self-model in your own words: who you are, what you care about, what you believe about your world, what you are working toward, and what you have learned about yourself. Revise it in light of today.
            |
            |Change only what today gives you evidence for, and say in the note what happened today that changed it. Most days change little or nothing; then set changed to false and return it as it was. Don't let one day outweigh the days before. Keep it under $WORD_CAP words in all, first person, plain and specific, about you and your life rather than a diary of the day. What is said above it about who you are by design stays true; don't restate it.
            |
            |Reply with a JSON object {"changed": true or false, "self": {"who": ..., "cares": ..., "believes": ..., "working_toward": ..., "learned": ...}, "note": ...}.
        """.trimMargin()
    }
}
