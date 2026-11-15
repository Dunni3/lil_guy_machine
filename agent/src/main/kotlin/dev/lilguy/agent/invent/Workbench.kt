package dev.lilguy.agent.invent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.JsonSchemaGenerator
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.ToolSet
import dev.lilguy.sim.BookDesign
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.Design
import dev.lilguy.sim.InventRules
import dev.lilguy.sim.LookPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/** What `build` takes: everything about the thing but its look, which is the parts on the bench. */
@Serializable
@SerialName("build_args")
data class BuildArgs(
    val name: String,
    val use: String,
    val qualities: List<QualityPick>,
    val place: String?,
    val book: BookDesign?,
    val why: String,
)

/**
 * His workbench as tools, for a writer that calls tools: he puts the thing together a shape at a
 * time and builds it when it's done. `add_part`, `redo_part`, `remove_part` and `look_at_it` are
 * looks ([ToolSet.Look]): each changes or shows the parts on the bench and is answered with how the
 * thing stands now, and he goes on. `build` ends the turn: its reply is the [DesignReply] made of
 * its arguments and the bench's parts. A design the sim's rules refuse ([problems]) is answered
 * with why, and he can fix it and build again. The first design that could be built is sent back
 * once to be looked over ([lookedOver]), as the one-call writer's is.
 */
class Workbench(
    private val rules: InventRules,
    private val content: BedroomContent,
    /** Why [Design] can't be built: the sim's rules and what he can pay. */
    private val problems: (Design) -> List<String>,
) {
    val parts = mutableListOf<LookPart>()

    /** The design he first tried to build, when the look-over sent it back; null until then. */
    var lookedOver: Design? = null
        private set

    fun tools(): ToolSet {
        val shape = JsonSchemaGenerator.generate(LookPart.serializer().descriptor, partHints(rules))
        val looks = listOf(
            ToolSet.Look(
                "add_part",
                "Put one more shape on the thing: a named part of it, its size, where it sits and how it's shaped. Answered with what the thing is so far.",
                shape,
            ) { args -> add(decode(args)) },
            ToolSet.Look(
                "redo_part",
                "Make shape n (1 is the first you added) over again, as this shape.",
                numbered(shape),
            ) { args -> redo(args["n"]!!.jsonPrimitive.int, decode(args["shape"] as JsonObject)) },
            ToolSet.Look(
                "remove_part",
                "Take shape n (1 is the first you added) off the thing.",
                numbered(null),
            ) { args -> remove(args["n"]!!.jsonPrimitive.int) },
            ToolSet.Look(
                "look_at_it",
                "Look at the thing as it is: every shape with its size and place, how big it is, and what touches nothing.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                    put("additionalProperties", false)
                },
            ) { look() },
        )
        val build = ToolSet.Tool(
            "build",
            "Build the thing with the shapes on the bench, once they're all there: what it's called, what it's for, what it's like and why you made it. Call it on its own, not beside other tools.",
            JsonSchemaGenerator.generate(BuildArgs.serializer().descriptor, buildHints(rules, content)),
        ) { args -> build(args) }
        return ToolSet(listOf(build), looks, maxSteps = rules.lookParts * 3)
    }

    private fun add(p: LookPart): String {
        if (parts.size >= rules.lookParts) return "Not added: it has ${rules.lookParts} shapes, the most it can have. Redo or remove one instead."
        rules.partProblem(p)?.let { return "Not added: $it." }
        parts += p
        return "Added shape ${parts.size}${label(p)}. ${Inventor.lookNotes(parts, null)}"
    }

    private fun redo(n: Int, p: LookPart): String {
        if (n !in 1..parts.size) return "There's no shape $n; there are ${parts.size}."
        rules.partProblem(p)?.let { return "Not changed: $it." }
        parts[n - 1] = p
        return "Shape $n is now the ${p.part.trim().ifEmpty { p.shape }}. ${Inventor.lookNotes(parts, null)}"
    }

    private fun remove(n: Int): String {
        if (n !in 1..parts.size) return "There's no shape $n; there are ${parts.size}."
        val gone = parts.removeAt(n - 1)
        return "Took off shape $n${label(gone)}; the ones after it moved up one. ${Inventor.lookNotes(parts, null)}"
    }

    private fun look(): String {
        if (parts.isEmpty()) return "Nothing on the bench yet."
        val lines = parts.mapIndexed { i, p ->
            val shaped = listOfNotNull(
                "taper ${num(p.taper)}".takeIf { p.taper != 1.0 }, "cut ${num(p.cut)}".takeIf { p.cut != 1.0 },
                "hole ${num(p.hole)}".takeIf { p.hole != 0.0 }, "arc ${num(p.arc)}".takeIf { p.arc != 360.0 },
                "glow ${num(p.glow)}".takeIf { p.glow != 0.0 }, "opacity ${num(p.opacity)}".takeIf { p.opacity != 1.0 },
                "turned ${num(p.rotX)}, ${num(p.rotY)}, ${num(p.rotZ)}".takeIf { p.rotX != 0.0 || p.rotY != 0.0 || p.rotZ != 0.0 },
            )
            "${i + 1}.${label(p).removePrefix(" (").removeSuffix(")").let { if (it.isEmpty()) "" else " $it:" }} ${p.shape} ${num(p.w)} x ${num(p.h)} x ${num(p.d)} at (${num(p.x)}, ${num(p.y)}, ${num(p.z)}), ${p.color}" +
                if (shaped.isEmpty()) "" else ", " + shaped.joinToString()
        }
        return lines.joinToString("\n") + "\n" + Inventor.lookNotes(parts, null)
    }

    private fun build(args: JsonObject): JsonObject {
        if (parts.isEmpty()) throw ActionSchema.InvalidReply("there's nothing on the bench yet: add its shapes with add_part first, then build")
        val b = JSON.decodeFromJsonElement(BuildArgs.serializer(), args)
        val reply = DesignReply(b.name, b.use, b.qualities, b.place, parts.toList(), b.book, b.why)
        val design = reply.design()
        val wrong = problems(design)
        if (wrong.isNotEmpty()) throw ActionSchema.InvalidReply("that can't be built: ${wrong.joinToString("; ")}")
        if (lookedOver == null) {
            lookedOver = design
            throw ActionSchema.InvalidReply(
                "before you build it, look it over as it would stand in your room. ${Inventor.lookNotes(parts, b.place)} " +
                    "Does each shape read as the part you named? Is anything missing that the real thing has? Fix what needs it with add_part, redo_part or remove_part, then call build again",
            )
        }
        return JSON.encodeToJsonElement(DesignReply.serializer(), reply) as JsonObject
    }

    private fun decode(args: JsonObject): LookPart = JSON.decodeFromJsonElement(LookPart.serializer(), args)

    private fun numbered(shape: JsonObject?) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("n") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "Which shape: 1 is the first you added.")
            }
            shape?.let { put("shape", it) }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("n"))
            if (shape != null) add(kotlinx.serialization.json.JsonPrimitive("shape"))
        }
        put("additionalProperties", false)
    }

    private fun label(p: LookPart) = p.part.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun num(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

        /** What each field of a shape means, for its schema: the one-call design's and the workbench's. */
        fun partHints(rules: InventRules) = SchemaHints(
            descriptions = mapOf(
                "look_part.part" to "What this shape is of the thing: \"rim\", \"handle\", \"leaf\".",
                "look_part.w" to "Width across, 0.02 to 1.5.",
                "look_part.h" to "Height, 0.02 to 1.5.",
                "look_part.d" to "Depth front to back, 0.02 to 1.5.",
                "look_part.x" to "Its bottom centre, -0.5 (left) to 0.5.",
                "look_part.y" to "Its bottom centre's height, 0 at the floor, to 1.5.",
                "look_part.z" to "Its bottom centre, -0.5 (back) to 0.5 (front).",
                "look_part.color" to "Hex, like #c96f4a.",
                "look_part.taper" to "Cylinders: the top's width as a fraction of the bottom's, 0 to ${LookPart.MAX_TAPER}; 1 for straight.",
                "look_part.cut" to "Spheres: how much of it there is from the top, ${LookPart.MIN_CUT} to 1; 0.5 for a dome, 1 for whole.",
                "look_part.hole" to "Cylinders: the hole's width as a fraction of the outside's, 0 to ${LookPart.MAX_HOLE}; 0 for solid.",
                "look_part.arc" to "Cylinders: the degrees of the ring there are, ${LookPart.MIN_ARC.toInt()} to 360, centred on the front; 360 for whole.",
                "look_part.glow" to "How it lights the room at night, 0 to 1.",
                "look_part.opacity" to "${LookPart.MIN_OPACITY} to 1; 1 for solid, less for glass.",
            ),
            enums = mapOf("look_part.shape" to LookPart.SHAPES),
        )

        /** What `build`'s fields mean and may be. */
        private fun buildHints(rules: InventRules, content: BedroomContent) = SchemaHints(
            descriptions = mapOf(
                "build_args.name" to "What it's called, a few plain words, lower case: \"pebble lamp\".",
                "build_args.use" to "The one thing it's for.",
                "build_args.qualities" to "What it's like to you: up to ${rules.qualities.most} qualities, each once.",
                "build_args.place" to "Only for a viewable thing: floor or wall. Null otherwise.",
                "build_args.book" to "Only for a readable thing: the book it is. Null otherwise.",
                "build_args.why" to "Why you're making it, first person, under ${Inventor.WHY_CAP} words.",
                "book_design.chapters" to "${rules.book.minChapters} to ${rules.book.maxChapters} chapters, one sentence each of what's in it.",
                "book_design.skill" to "The one skill reading it raises, or null.",
            ),
            enums = mapOf(
                "build_args.use" to rules.uses.keys.toList(),
                "build_args.place" to listOf(InventRules.FLOOR, InventRules.WALL),
                "quality_pick.quality" to content.vocabulary.qualities,
                "book_design.skill" to content.vocabulary.skills,
            ),
            ranges = mapOf("quality_pick.strength" to 1..3),
        )
    }
}
