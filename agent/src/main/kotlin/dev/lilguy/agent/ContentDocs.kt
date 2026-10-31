package dev.lilguy.agent

import dev.lilguy.decision.JsonSchemaGenerator
import dev.lilguy.decision.SchemaHints
import dev.lilguy.sim.ActivitySpec
import dev.lilguy.sim.Affordance
import dev.lilguy.sim.AffordanceParams
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.ItemSpec
import dev.lilguy.sim.Need
import dev.lilguy.sim.NeedBlock
import dev.lilguy.sim.NoveltyRules
import dev.lilguy.sim.Op
import dev.lilguy.sim.Personality
import dev.lilguy.sim.SellOp
import dev.lilguy.sim.SetStateOp
import dev.lilguy.sim.VerbKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.roundToInt

/**
 * What's generated from the bedroom's content classes and the loaded content, so neither drifts
 * from the code (`make content-docs`):
 * - [schemas]: one JSON Schema per content file, from the serial descriptors, for editors (each
 *   file names its schema in a `yaml-language-server` comment).
 * - [reference]: an HTML page laying out the grammar: kinds, ops, verbs, items and vocabulary.
 */
object ContentDocs {
    /** How an op is written, when it acts and what it does, for the reference page and the schema. */
    class OpDoc(val example: String, val acts: String, val what: String)

    val OPS: Map<String, OpDoc> = linkedMapOf(
        "need" to OpDoc("need: { mood: 2, stimulation: 15 }", "at the finish", "Changes needs. Gains in mood and stimulation are scaled by freshness (habituation)."),
        "rate" to OpDoc("rate: { energy: 12 }", "each minute", "Changes needs per game hour while the verb runs, scaled the same way. An `until` on a need needs a rate for it."),
        "consume" to OpDoc("consume: { edible: 1 }", "at the finish", "Uses up items, by id or affordance; locked while he has too few."),
        "produce" to OpDoc("produce: { sketch: 1 }", "at the finish", "Makes items. A drawable item made this way is drawn by the drawing model."),
        "sell" to OpDoc("sell: sellable", "at the finish", "Sells everything carried that answers to the name, at each item's `sellable` price, plus any commission reward."),
        "set_state" to OpDoc("set_state: { target: hangable, state: hung }", "at the finish", "Puts his newest held piece answering to the target into a state. The one state is `hung`: on the picture rail, adding the gallery's comfort."),
        "background" to OpDoc("background: { name: music, minutes: 45, rates: { mood: 2 } }", "at the finish, then each minute", "Starts something that keeps going while he does anything else, playing the used item."),
        "sleep" to OpDoc("sleep: {}", "while it runs", "He's asleep: no awake drain of energy or stimulation."),
        "enjoy" to OpDoc("enjoy: item", "at the finish", "Pays the bound item's value to him (qualities times affinities) as mood, scaled by freshness."),
    )

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun hints(c: BedroomContent): SchemaHints {
        val needs = Need.entries.map { it.id }
        val op = Op.serializer().descriptor.serialName
        val activity = ActivitySpec.serializer().descriptor.serialName
        val item = ItemSpec.serializer().descriptor.serialName
        val content = BedroomContent.serializer().descriptor.serialName
        return SchemaHints(
            descriptions = OPS.mapKeys { "$op.${it.key}" }.mapValues { "${it.value.what} (${it.value.acts})" } + mapOf(
                op to "One op: a map with a single key, such as `- need: { mood: 2 }`.",
                "$activity.kind" to "What sort of verb it is: a contract on which ops `effects` may use.",
                "$activity.at" to "Where it happens: owned slot items, by id; the nearest is used.",
                "$activity.requires" to "Items or affordances he must own.",
                "$activity.effects" to "What it does: ops, run in order.",
                "$activity.work" to "Work: refused below the work floor (rules.yaml).",
                "$activity.routine" to "Upkeep: it neither wears thin nor counts as new.",
                "$activity.phrase" to "How the prompt says what he can do; the id when left out.",
                "$activity.tier" to "Above 0, gated until its id is granted.",
                "$activity.item" to "Uses: the item used, an id or an affordance; its ops call it `item`.",
                "$activity.on" to "Uses: the item it's used on.",
                "$item.group" to "For display and grouping: fixture, furniture, decor, tool, food, supply, record, book, art.",
                "$item.affords" to "Affordances from vocabulary.yaml, each with its parameters (`{}` for none).",
                "$item.qualities" to "Qualities from vocabulary.yaml, each with a strength above 0 and at most 3.",
                "$item.slot" to "Placed in this room slot when owned.",
                "$item.blocks" to "He stands beside it, not on it.",
                "$item.carried" to "Carried in his hands (art) rather than kept in the room.",
                "$content.think" to "The think intent's cost and how often he's made to think.",
            ),
            enums = mapOf(
                "${NeedBlock.serializer().descriptor.serialName}.need" to needs,
                "set_state.state" to SetStateOp.STATES,
                "$op.enjoy" to listOf("item"),
            ),
            mapKeys = mapOf(
                "$op.need" to needs,
                "$op.rate" to needs,
                "background.rates" to needs,
                "$activity.stop_below" to needs,
                "${NoveltyRules.serializer().descriptor.serialName}.effects" to needs,
                "$item.affords" to c.vocabulary.affords,
                "$item.qualities" to c.vocabulary.qualities,
                "${Personality.serializer().descriptor.serialName}.affinities" to c.vocabulary.qualities,
            ),
            exactlyOne = setOf(op),
        )
    }

    /** File name (no extension) to its schema. */
    fun schemas(c: BedroomContent): Map<String, JsonObject> {
        require(Op.KEYS.toSet() == OPS.keys) { "ContentDocs.OPS must document every op: ${Op.KEYS}" }
        val d = BedroomContent.serializer().descriptor
        val hints = hints(c)
        return BedroomContent.FILES.mapValues { (file, keys) ->
            val elements = keys.map { k -> (0 until d.elementsCount).first { d.getElementName(it) == k } }
            buildJsonObject {
                put("\$schema", "http://json-schema.org/draft-07/schema#")
                put("title", "lil guy machine: bedroom content, $file.yaml")
                put("description", "Generated by `make content-docs` from the content classes (sim/.../BedroomContent.kt, Ops.kt); don't edit.")
                put("type", "object")
                put("properties", JsonObject(elements.associate { i -> d.getElementName(i) to JsonSchemaGenerator.generate(d.getElementDescriptor(i), hints, strict = false, key = "${d.serialName}.${d.getElementName(i)}") }))
                put("required", JsonArray(elements.filter { !d.isElementOptional(it) }.map { JsonPrimitive(d.getElementName(it)) }))
                put("additionalProperties", false)
            }
        }
    }

    /** Each schema as the file it's written to. */
    fun schemaFiles(c: BedroomContent): Map<String, String> = schemas(c).mapKeys { "${it.key}.schema.json" }.mapValues { json.encodeToString(JsonObject.serializer(), it.value) + "\n" }

    /** Writes the schemas into [schemaDir] and the reference page to [page]. */
    fun write(c: BedroomContent, schemaDir: File, page: File) {
        schemaDir.mkdirs()
        schemaFiles(c).forEach { (name, text) -> File(schemaDir, name).writeText(text) }
        page.parentFile?.mkdirs()
        page.writeText(reference(c))
    }

    /** "need: { energy: -6, mood: -4 }": an op as it's written in content. */
    fun opText(op: Op): String {
        val (key, value) = Json.encodeToJsonElement(Op.serializer(), op).let { it as JsonObject }.entries.single()
        return "$key: ${flow(value)}"
    }

    private fun flow(e: JsonElement): String = when (e) {
        is JsonObject -> if (e.isEmpty()) "{}" else "{ " + e.entries.joinToString { (k, v) -> "$k: ${flow(v)}" } + " }"
        is JsonArray -> "[" + e.joinToString { flow(it) } + "]"
        JsonNull -> "null"
        is JsonPrimitive -> if (e.isString) e.content else e.doubleOrNull?.let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() } ?: e.content
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun code(s: String) = "<code>${esc(s)}</code>"

    private fun table(head: List<String>, rows: List<List<String>>) =
        "<table><thead><tr>${head.joinToString("") { "<th>$it</th>" }}</tr></thead><tbody>\n" +
            rows.joinToString("\n") { r -> "<tr>${r.joinToString("") { "<td>$it</td>" }}</tr>" } + "\n</tbody></table>"

    /** The grammar reference page, from [c] and the kind and op definitions. */
    fun reference(c: BedroomContent): String {
        val verbs = c.activities + c.uses
        val kinds = table(
            listOf("Kind", "Meaning", "May use", "Must use", "Other", "Verbs"),
            VerbKind.entries.map { k ->
                listOf(
                    code(k.id), esc(k.meaning), k.allowed.joinToString(" ") { code(it) },
                    k.required.joinToString("; ") { set -> set.joinToString(" or ") { code(it) } }.ifEmpty { "nothing" },
                    listOfNotNull(if (k.affordance) "asks for an item by affordance" else null, if (k.routine) "always routine" else null).joinToString("; "),
                    verbs.filter { it.kind == k }.joinToString(" ") { code(it.id) },
                )
            },
        )
        val ops = table(
            listOf("Op", "Written", "Acts", "What it does", "Kinds that may use it", "Used by"),
            OPS.map { (key, doc) ->
                listOf(
                    code(key), code(doc.example), doc.acts, esc(doc.what),
                    VerbKind.entries.filter { key in it.allowed }.joinToString(" ") { code(it.id) },
                    verbs.filter { v -> v.effects.any { it.key == key } }.joinToString(" ") { code(it.id) },
                )
            },
        )
        val verbRows = table(
            listOf("Verb", "Kind", "Where", "Needs", "Takes", "Effects", "Flags"),
            verbs.map { v ->
                val time = v.minutes?.let { "${it} min" } ?: v.until!!.let { u -> "until ${u.time}" + (u.need?.let { " or $it ${u.reaches?.roundToInt()}" } ?: "") }
                val needs = (v.requires + listOfNotNull(v.item?.let { "item: $it" }, v.on?.let { "on: $it" }) +
                    v.block.map { b -> "not when ${b.need} ${b.below?.let { "below ${it.roundToInt()}" } ?: "above ${b.above?.roundToInt()}"}" }).joinToString("; ")
                val flags = listOfNotNull(if (v.work) "work" else null, if (v.isRoutine) "routine" else null, if (v.tier > 0) "tier ${v.tier}" else null, v.phrase?.let { "“${esc(it)}”" }).joinToString(", ")
                listOf(code(v.id), code(v.kind.id), v.at.joinToString(", ") { code(it) }, esc(needs), time + v.stopBelow.entries.joinToString("") { (n, l) -> ", stops below $n ${l.roundToInt()}" }, v.effects.joinToString("<br>") { code(opText(it)) }, flags)
            },
        )
        val items = table(
            listOf("Item", "Group", "Affords", "Qualities", "Value to him", "Price", "Other"),
            c.items.map { i ->
                val affords = i.affords.entries.joinToString("<br>") { (a, p) -> code(a + if (p == AffordanceParams()) "" else ": " + flow(Json.encodeToJsonElement(AffordanceParams.serializer(), p).let { JsonObject((it as JsonObject).filterValues { v -> v != JsonNull }) })) }
                val price = i.price?.let { "$$it" + if (i.pack > 1) " for ${i.pack}" else "" } ?: ""
                val other = listOfNotNull(i.slot?.let { "slot $it" }, if (i.blocks) "blocks" else null, if (i.stackable) "stackable" else null, if (i.carried) "carried" else null, if (i.tier > 0) "tier ${i.tier}" else null).joinToString(", ")
                listOf(code(i.id) + " " + esc(i.name), esc(i.group), affords, i.qualities.entries.joinToString(", ") { (q, s) -> "$q ${flow(JsonPrimitive(s))}" }, c.value(i).takeIf { it != 0.0 }?.let { flow(JsonPrimitive(it)) } ?: "", price, other)
            },
        )
        val affordances = table(
            listOf("Affordance", "Parameters", "Items", "Asked for by"),
            c.vocabulary.affords.map { a ->
                listOf(
                    code(a), Affordance.PARAMS[a]?.entries?.joinToString(", ") { (p, r) -> "$p ${r.first}–${r.last}" }.orEmpty(),
                    c.items.filter { a in it.affords }.joinToString(" ") { code(it.id) },
                    verbs.filter { v -> a in v.requires || a == v.item || v.effects.any { op -> a in op.asksFor || op.let { o -> o is SellOp && o.what == a || o is SetStateOp && o.target == a } } }.joinToString(" ") { code(it.id) },
                )
            },
        )
        val qualities = table(
            listOf("Quality", "His affinity (default)", "Items"),
            c.vocabulary.qualities.map { q -> listOf(code(q), flow(JsonPrimitive(c.personality.affinity(q))), c.items.filter { q in it.qualities }.joinToString(" ") { code(it.id) + " " + flow(JsonPrimitive(it.qualities.getValue(q))) }) },
        )
        return """
            |<!doctype html>
            |<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>Bedroom grammar</title>
            |<style>
            |:root { --bg: #fbfaf7; --fg: #1d1d1b; --muted: #6b6a66; --line: #dcdad3; --code: #efece4; }
            |@media (prefers-color-scheme: dark) { :root { --bg: #171716; --fg: #e9e7e1; --muted: #a09e97; --line: #393833; --code: #26251f; } }
            |body { background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1200px; padding: 24px 16px 64px; }
            |h1 { font-size: 26px; margin-bottom: 4px; } h2 { margin-top: 40px; border-bottom: 1px solid var(--line); padding-bottom: 4px; }
            |p.lede { color: var(--muted); margin-top: 0; }
            |table { border-collapse: collapse; width: 100%; font-size: 14px; display: block; overflow-x: auto; }
            |th, td { text-align: left; vertical-align: top; padding: 6px 10px; border-bottom: 1px solid var(--line); }
            |th { color: var(--muted); font-weight: 600; white-space: nowrap; }
            |code { background: var(--code); border-radius: 4px; padding: 1px 4px; font-size: 13px; white-space: nowrap; }
            |li { margin: 4px 0; }
            |</style></head><body>
            |<h1>Bedroom grammar</h1>
            |<p class="lede">Generated by <code>make content-docs</code> from the content files (sim/src/main/resources/content/bedroom/) and the kind and op definitions (VerbKind.kt, Ops.kt). Don't edit it; regenerate it.</p>
            |
            |<h2>How it fits together</h2>
            |<ul>
            |<li><b>Items</b> are rows in items.yaml. What can be done with one is its <b>affordances</b>; what it's like to him is its <b>qualities</b>. Both come from the closed lists in vocabulary.yaml. An item's value to him is each quality's strength times his affinity for it (personality.yaml).</li>
            |<li><b>Verbs</b> are rows in verbs.yaml. Preconditions and timing are fields (<code>at</code>, <code>requires</code>, <code>block</code>, <code>minutes</code> or <code>until</code>, <code>work</code>). What a verb does is <code>effects</code>, a list of <b>ops</b>, run in order.</li>
            |<li>Every verb has a <b>kind</b>, a contract on which ops it may and must use. An op no kind allows is refused everywhere.</li>
            |<li><b>A new item</b> is one row; it works at once with every verb that asks for one of its affordances. <b>A new verb</b> is one row built from existing ops within its kind. <b>A new op</b> is one class in Ops.kt (checks, lock reason, apply, prompt text), a field in its <code>OpEntry</code>, a line in <code>ContentDocs.OPS</code>, and a kind that allows it.</li>
            |</ul>
            |
            |<h2>Kinds</h2>
            |$kinds
            |
            |<h2>Ops</h2>
            |$ops
            |
            |<h2>Verbs</h2>
            |$verbRows
            |
            |<h2>Items</h2>
            |$items
            |
            |<h2>Affordances</h2>
            |$affordances
            |
            |<h2>Qualities</h2>
            |$qualities
            |</body></html>
            |""".trimMargin()
    }
}
