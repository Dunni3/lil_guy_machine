package dev.lilguy.parts

import dev.lilguy.parts.RoomParts.paletteDab
import dev.lilguy.parts.RoomParts.shelfBoards
import dev.lilguy.parts.RoomParts.shelfBook
import dev.lilguy.parts.RoomParts.shelfRecord
import dev.lilguy.parts.RoomParts.wallClock
import dev.lilguy.parts.RoomParts.windowFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * Every part list as JSON (`parts/export/parts.json`), for a renderer in another language. One part
 * a line, so a changed model reads as a small diff. What depends on the room or on what he owns is
 * exported as pieces for the renderer to place, and it counts seeds up across a model's parts as
 * [PartList] does:
 * - the window fixtures and the clock at x = 0, shifted by [RoomParts.WINDOW_AT] and [RoomParts.CLOCK_AT] of the map's width;
 * - the shelf's boards, a book per spine colour with its left edge at x = 0 (and its width), a record per sleeve colour and place;
 * - props in a colour that's chosen as he lives carry [COLOR] instead of one.
 */
object PartExport {
    /** Stands in for a prop's colour: the renderer puts the real one in. */
    const val COLOR = "\$color"

    fun text(): String {
        val r = RoomParts
        val props = linkedMapOf(
            "frame" to r.frame(),
            "canvas" to r.canvas(),
            "hand_hour" to r.hand(r.HOUR_HAND),
            "hand_minute" to r.hand(r.MINUTE_HAND),
            "guitar" to r.guitar(),
            "disc" to r.disc(),
            "held_book" to r.heldBook(COLOR),
            "held_guitar" to r.heldGuitar(),
            "note" to r.note(COLOR),
            "tool_brush" to r.tool(COLOR, brush = true),
            "tool_crayon" to r.tool(COLOR, brush = false),
            "sparkle" to r.sparkle(COLOR),
            "palette_board" to r.palette(emptyList()),
            "palette_dab" to r.parts { paletteDab(COLOR, 0f, 0f) },
            "window_fixtures" to r.parts { windowFixtures(0f) },
            "wall_clock" to r.parts { wallClock(0f) },
            "shelf_boards" to r.parts { shelfBoards() },
        )
        val books = r.SPINES.indices.map { k ->
            var width = 0f
            val parts = r.parts { lump = 0.002f; width = shelfBook(k, 0f) }
            width to parts
        }
        val records = r.SLEEVES.indices.map { k -> List(r.SHELF_RECORDS) { i -> r.parts { lump = 0.002f; shelfRecord(k, i) } } }
        val constants = linkedMapOf<String, JsonElement>(
            "WALL_HEIGHT" to n(r.WALL_HEIGHT), "LIP" to n(r.LIP), "BASEBOARD" to n(r.BASEBOARD), "COLOR_BLOCK" to n(r.COLOR_BLOCK),
            "WINDOW_WIDTH" to n(r.WINDOW_WIDTH), "WINDOW_BOTTOM" to n(r.WINDOW_BOTTOM), "WINDOW_TOP" to n(r.WINDOW_TOP),
            "WINDOW_AT" to n(r.WINDOW_AT), "CLOCK_AT" to n(r.CLOCK_AT), "SHELF_AT" to n(r.SHELF_AT), "GUITAR_AT" to n(r.GUITAR_AT),
            "CLOCK_Y" to n(r.CLOCK_Y), "SKETCH_X" to n(r.SKETCH_X), "SKETCH_Z" to n(r.SKETCH_Z), "CANVAS_Y" to n(r.CANVAS_Y), "CANVAS_Z" to n(r.CANVAS_Z),
            "TOOL_TIP" to n(r.TOOL_TIP), "DISC_X" to n(r.DISC_X), "DISC_Y" to n(r.DISC_Y),
            "FRAME" to n(r.FRAME), "FRAME_ART" to n(r.FRAME_ART), "FRAME_ART_Z" to n(r.FRAME_ART_Z), "FRAME_BOTTOM" to n(r.FRAME_BOTTOM),
            "FRAME_XS" to JsonArray(r.FRAME_XS.map(::n)),
            "SHELF_START" to n(r.SHELF_START), "SHELF_GAP" to n(r.SHELF_GAP), "SHELF_BOOKS" to JsonPrimitive(r.SHELF_BOOKS), "SHELF_RECORDS" to JsonPrimitive(r.SHELF_RECORDS),
            "MAX_ROUND" to n(Part.MAX_ROUND), "PLAIN_BOOK" to JsonPrimitive(r.BOOK),
            "SPINES" to strings(r.SPINES), "WALL_ITEMS" to strings(r.WALL_ITEMS + DecorParts.ON_WALL), "WALL_KINDS" to strings(r.WALL_KINDS),
            "FLAT_ITEMS" to strings(r.FLAT_ITEMS), "FLAT_KINDS" to strings(r.FLAT_KINDS),
        )
        return buildString {
            append("{\n")
            append("\"constants\": ").append(JsonObject(constants)).append(",\n")
            models("items", (r.ITEM_IDS + DecorParts.IDS).associateWith { r.item(it, "") })
            append(",\n")
            // "*" is what an unknown id of an unknown kind gets.
            models("kinds", (r.KINDS + "*").associateWith { r.item("", it) })
            append(",\n")
            models("props", props)
            append(",\n")
            append("\"shelf_books\": [\n")
            books.forEachIndexed { i, (width, parts) ->
                append("{\"width\": ").append(n(width)).append(", \"parts\": ").also { list(parts) }
                append(if (i < books.lastIndex) "},\n" else "}\n")
            }
            append("],\n")
            append("\"shelf_records\": [\n")
            records.forEachIndexed { k, places ->
                append("[\n")
                places.forEachIndexed { i, parts -> list(parts); append(if (i < places.lastIndex) ",\n" else "\n") }
                append(if (k < records.lastIndex) "],\n" else "]\n")
            }
            append("]\n}\n")
        }
    }

    private fun StringBuilder.models(name: String, all: Map<String, List<Part>>) {
        append("\"").append(name).append("\": {\n")
        val keys = all.keys.toList()
        keys.forEachIndexed { i, key ->
            append("\"").append(key).append("\": ")
            list(all.getValue(key))
            append(if (i < keys.lastIndex) ",\n" else "\n")
        }
        append("}")
    }

    private fun StringBuilder.list(parts: List<Part>) {
        append("[\n")
        parts.forEachIndexed { i, p -> append(json(p)); append(if (i < parts.lastIndex) ",\n" else "\n") }
        append("]")
    }

    private fun json(p: Part) = JsonObject(
        linkedMapOf(
            "shape" to JsonPrimitive(p.shape.name.lowercase()),
            "w" to n(p.w), "h" to n(p.h), "d" to n(p.d),
            "x" to n(p.x), "y" to n(p.y), "z" to n(p.z),
            "rotX" to n(p.rotX), "rotY" to n(p.rotY), "rotZ" to n(p.rotZ),
            "color" to JsonPrimitive(p.color),
            "texture" to (p.texture?.let(::JsonPrimitive) ?: JsonNull),
            "round" to n(p.round), "lump" to n(p.lump), "seed" to JsonPrimitive(p.seed),
            "outline" to n(p.outline), "taper" to n(p.taper),
        ),
    )

    private fun n(v: Float) = JsonPrimitive(v)
    private fun strings(s: Collection<String>) = JsonArray(s.map(::JsonPrimitive))
}

/** `write <file>` writes the export; `check <file>` fails when it isn't what the part lists say. */
fun main(args: Array<String>) {
    val text = PartExport.text()
    Json.parseToJsonElement(text)
    when (args.firstOrNull()) {
        "write" -> File(args[1]).apply { parentFile?.mkdirs() }.writeText(text)
        "check" -> if (File(args[1]).let { !it.isFile || it.readText() != text }) {
            System.err.println("${args[1]} is stale: run `make parts`")
            exitProcess(1)
        }
        else -> {
            System.err.println("usage: write <file> | check <file>")
            exitProcess(2)
        }
    }
}
