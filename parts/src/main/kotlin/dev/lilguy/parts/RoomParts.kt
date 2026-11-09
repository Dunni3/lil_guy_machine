package dev.lilguy.parts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The room's models as part lists ([Part]), keyed by item id ([item]), with a plain box for ids
 * nothing here knows. Conventions every model follows, so another part list can replace one as a
 * drop-in: 1 unit = 1 metre = 1 tile; pivot at the bottom centre of the item's tile; the front faces
 * +Z (toward the camera). Wall items ([wallItem]) instead have their back at z = 0 and are placed
 * against whichever wall face looks into the room. Positions come from the world, never from here.
 *
 * Plain data, no GL: `:app` builds meshes from these, and the web renderer reads the same lists
 * from the export ([PartExport]).
 */
object RoomParts {
    fun parts(body: PartList.() -> Unit): List<Part> = PartList().apply(body).parts

    /** The parts of item [id] (of [kind], for the fallback). */
    fun item(id: String, kind: String): List<Part> = parts { buildItem(id, kind) }

    /** Items that hang in a wall rather than stand on the floor: their back is at z = 0. */
    fun wallItem(id: String, kind: String): Boolean = id in WALL_ITEMS || id in DecorParts.ON_WALL || kind in WALL_KINDS

    /** Items that lie flat and cast no shadow of their own. */
    fun flat(id: String, kind: String): Boolean = id in FLAT_ITEMS || kind in FLAT_KINDS

    /**
     * An empty picture frame, pivot at its bottom centre, back at z = 0, [FRAME] square; the view
     * puts a piece's texture on its mat ([FRAME_ART] square, [FRAME_ART_Z] out from the wall).
     */
    fun frame() = parts {
        box(WOOD_DARK, 0f, 0f, 0.015f, FRAME, FRAME, 0.03f, round = 0.012f)
        box(PAPER, 0f, 0.03f, 0.03f, FRAME - 0.06f, FRAME - 0.06f, 0.01f, round = 0.004f, outline = 0f)
        sphere(KNOB, 0f, FRAME + 0.065f, 0.01f, 0.035f, 0.035f, 0.025f)
    }

    /** A blank canvas, pivot at the easel's, standing on its ledge; its face is [CANVAS_Z] out and [CANVAS_Y] up at its centre. */
    fun canvas() = parts { box(CANVAS, 0f, 0.47f, 0.12f, 0.5f, 0.56f, 0.03f, round = 0.008f) }

    /** One clock hand, pivot at its base, pointing up (+Y) and facing +Z. */
    fun hand(length: Float) = parts { box(HAND, 0f, -0.02f, 0f, 0.025f, length, 0.01f, round = 0.005f, outline = 0f) }

    /**
     * A wall shelf of what he owns to read and play, back at z = 0 like [wallItem]s: [books] as
     * spines on the top board and [records] as sleeves leaning on the bottom one, each item id its
     * own colour.
     */
    fun shelf(books: List<String>, records: List<String>) = parts {
        shelfBoards()
        lump = 0.002f
        var x = SHELF_START
        for (id in books.take(SHELF_BOOKS)) x += shelfBook(spine(id), x) + SHELF_GAP
        records.take(SHELF_RECORDS).forEachIndexed { i, id -> shelfRecord(id.hashCode().mod(SLEEVES.size), i) }
    }

    /** The shelf's two boards and their brackets. */
    fun PartList.shelfBoards() {
        for (y in listOf(SHELF_LOW, SHELF_HIGH)) {
            box(WOOD, 0f, y, SHELF_DEPTH / 2, SHELF_WIDTH, 0.035f, SHELF_DEPTH, round = 0.012f)
            for (side in listOf(-1f, 1f)) box(WOOD_DARK, side * (SHELF_WIDTH / 2 - 0.1f), y - 0.1f, 0.05f, 0.035f, 0.1f, 0.1f, round = 0.012f)
        }
    }

    /** A book of spine colour [k] on the top board, its left edge at [x]; returns its width. */
    fun PartList.shelfBook(k: Int, x: Float): Float {
        val w = 0.055f + 0.012f * (k % 3)
        val h = 0.2f + 0.025f * (k % 4)
        box(SPINES[k], x + w / 2, SHELF_HIGH + 0.035f, 0.1f, w, h, 0.15f, round = 0.008f)
        box(PAPER, x + w / 2, SHELF_HIGH + 0.035f + h * 0.62f, 0.176f, w * 0.7f, 0.022f, 0.004f, round = 0.002f, outline = 0f)
        return w
    }

    /** The [i]th record, of sleeve colour [k], leaning on the bottom board. */
    fun PartList.shelfRecord(k: Int, i: Int) {
        val sx = -SHELF_WIDTH / 2 + 0.2f + i * 0.27f
        box(SLEEVES[k], sx, SHELF_LOW + 0.035f, 0.09f + i * 0.012f, SLEEVE, SLEEVE, 0.014f, rotX = -9f, round = 0.006f)
        cylinder(SPINES[(k + 2) % SPINES.size], sx, SHELF_LOW + 0.035f + SLEEVE * 0.32f, 0.105f + i * 0.012f, 0.12f, 0.004f, 0.12f, rotX = 81f, round = 0.002f, outline = 0.005f)
    }

    private fun spine(id: String) = id.hashCode().mod(SPINES.size)

    /** His guitar, hanging on a peg; back at z = 0 like the [shelf]. */
    fun guitar() = parts {
        lump = 0.002f
        sphere(GUITAR, 0f, 0.82f, 0.06f, 0.4f, 0.36f, 0.09f)
        sphere(GUITAR, 0f, 1.08f, 0.06f, 0.3f, 0.27f, 0.09f)
        cylinder(VINYL, 0f, 1.05f, 0.1f, 0.1f, 0.01f, 0.1f, rotX = 90f, round = 0.002f, outline = 0.004f)
        box(WOOD_DARK, 0f, 0.86f, 0.106f, 0.12f, 0.025f, 0.012f, round = 0.004f, outline = 0.004f)
        box(WOOD_DARK, 0f, 1.2f, 0.07f, 0.055f, 0.42f, 0.035f, round = 0.01f)
        box(WOOD_DARK, 0f, 1.6f, 0.07f, 0.085f, 0.12f, 0.03f, round = 0.012f)
        capsule(KNOB, 0f, 1.62f, 0.02f, 0.035f, 0.05f, 0.035f, rotX = 90f, outline = 0.005f)
    }

    // --- props he holds or works with, in his own units (the guy is scaled up from them) --------------

    /** The colour of book [id]'s spine on the [shelf], so the one in his hands matches; the plain book colour without one. */
    fun bookColor(id: String?): String = id?.let { SPINES[spine(it)] } ?: BOOK

    /** A book held up in both hands, cover out (+Z), the page block showing along the top; pivot at its bottom centre. */
    fun heldBook(color: String) = parts {
        box(color, 0f, 0f, 0f, 0.26f, 0.32f, 0.06f, round = 0.012f)
        box(PAPER, 0f, 0.012f, -0.004f, 0.24f, 0.3f, 0.045f, round = 0.006f, outline = 0f)
    }

    /** His guitar held to play: the body at the origin facing +Z, the neck out along -x. */
    fun heldGuitar() = parts {
        lump = 0.002f
        sphere(GUITAR, 0.04f, -0.15f, 0f, 0.3f, 0.27f, 0.07f)
        sphere(GUITAR, -0.13f, -0.11f, 0f, 0.22f, 0.2f, 0.07f)
        cylinder(VINYL, -0.06f, -0.05f, 0.03f, 0.075f, 0.01f, 0.075f, rotX = 90f, round = 0.002f, outline = 0.004f)
        box(WOOD_DARK, 0.06f, -0.06f, 0.04f, 0.02f, 0.09f, 0.01f, round = 0.004f, outline = 0.004f)
        box(WOOD_DARK, -0.4f, -0.04f, 0.02f, 0.34f, 0.04f, 0.026f, round = 0.008f)
        box(WOOD_DARK, -0.62f, -0.055f, 0.02f, 0.1f, 0.065f, 0.024f, round = 0.01f)
    }

    /**
     * A paintbrush loaded with [color], or a crayon of it when not [brush]: held at the
     * origin, pointing along +Z, its tip [TOOL_TIP] out.
     */
    fun tool(color: String, brush: Boolean) = parts {
        lump = 0f
        if (brush) {
            capsule(WOOD_DARK, 0f, 0f, -0.08f, 0.022f, 0.17f, 0.022f, rotX = 90f, outline = 0.005f)
            cylinder(FERRULE, 0f, 0f, 0.07f, 0.028f, 0.035f, 0.028f, rotX = 90f, round = 0.004f, outline = 0.005f)
            cylinder(color, 0f, 0f, 0.1f, 0.032f, TOOL_TIP - 0.1f, 0.032f, rotX = 90f, taper = 0.25f, round = 0.006f, outline = 0.005f)
        } else {
            cylinder(color, 0f, 0f, -0.03f, 0.034f, 0.14f, 0.034f, rotX = 90f, round = 0.004f, outline = 0.005f)
            cylinder(PAPER, 0f, 0f, 0f, 0.037f, 0.07f, 0.037f, rotX = 90f, round = 0.002f, outline = 0f)
            cylinder(color, 0f, 0f, 0.11f, 0.034f, TOOL_TIP - 0.11f, 0.034f, rotX = 90f, taper = 0.2f, round = 0.004f, outline = 0.005f)
        }
    }

    /** A painter's palette lying flat, pivot at its bottom centre, with a dab of each of [colors] round its far edge. */
    fun palette(colors: List<String>) = parts {
        lump = 0f
        cylinder(WOOD_LIGHT, 0f, 0f, 0f, 0.26f, 0.016f, 0.18f, round = 0.006f, outline = 0.006f)
        cylinder(WOOD_DARK, 0.085f, 0.004f, 0.04f, 0.04f, 0.014f, 0.035f, round = 0.004f, outline = 0.004f)
        colors.forEachIndexed { i, c ->
            val a = PI.toFloat() * (1.1f + 0.8f * i / maxOf(1, colors.size - 1))
            paletteDab(c, 0.085f * cos(a), 0.058f * sin(a))
        }
    }

    fun PartList.paletteDab(color: String, x: Float, z: Float) {
        sphere(color, x, 0.01f, z, 0.048f, 0.022f, 0.044f, outline = 0.004f, lump = 0.003f)
    }

    /** A twinkle, a four-pointed star of two crossed spindles centred on the origin, facing +Z. */
    fun sparkle(color: String) = parts {
        lump = 0f
        sphere(color, 0f, -0.075f, 0f, 0.035f, 0.15f, 0.014f, outline = 0.004f)
        sphere(color, 0f, -0.0175f, 0f, 0.15f, 0.035f, 0.014f, outline = 0.004f)
    }

    /** A music note, its head at the origin, facing +Z. */
    fun note(color: String) = parts {
        lump = 0f
        sphere(color, 0f, -0.03f, 0f, 0.075f, 0.06f, 0.03f, outline = 0.005f)
        box(color, 0.03f, -0.005f, 0f, 0.016f, 0.15f, 0.016f, round = 0.004f, outline = 0.005f)
        box(color, 0.055f, 0.12f, 0f, 0.05f, 0.022f, 0.014f, rotZ = -30f, round = 0.004f, outline = 0.005f)
    }

    /** The record on the player, centred on its spindle, with a stripe on the label so it shows turning. */
    fun disc() = parts {
        cylinder(VINYL, 0f, 0f, 0f, 0.3f, 0.02f, 0.3f, round = 0.006f)
        cylinder(RECORD_LABEL, 0f, 0.015f, 0f, 0.1f, 0.012f, 0.1f, round = 0.004f, outline = 0.005f)
        box(PAPER, 0.025f, 0.026f, 0f, 0.045f, 0.004f, 0.014f, round = 0.002f, outline = 0f, lump = 0f)
    }

    /** The back wall's window frame, curtains and rod, and the clock (its hands are the view's, [hand]). */
    fun fixtures(windowX: Float, clockX: Float) = parts {
        windowFixtures(windowX)
        wallClock(clockX)
    }

    fun PartList.windowFixtures(wx: Float) {
        val tall = WINDOW_TOP - WINDOW_BOTTOM
        // A slim charcoal frame with one mullion and an oak sill.
        box(WOOD, wx, WINDOW_BOTTOM - 0.06f, 1.0f, WINDOW_WIDTH + 0.16f, 0.05f, 0.11f, round = 0.015f)
        box(FRAME_DARK, wx, WINDOW_TOP, 1.0f, WINDOW_WIDTH + 0.08f, 0.04f, 0.04f, round = 0.01f)
        box(FRAME_DARK, wx, WINDOW_BOTTOM - 0.02f, 1.0f, WINDOW_WIDTH + 0.08f, 0.035f, 0.04f, round = 0.01f)
        for (side in listOf(-1f, 1f)) box(FRAME_DARK, wx + side * (WINDOW_WIDTH / 2 + 0.02f), WINDOW_BOTTOM, 1.0f, 0.04f, tall, 0.04f, round = 0.01f)
        box(FRAME_DARK, wx, WINDOW_BOTTOM, 1.0f, 0.03f, tall, 0.03f, round = 0.008f, outline = 0.006f)
        // Linen curtains, floor to rod, drawn open in soft pleats.
        lump = 0.008f
        for (side in listOf(-1f, 1f)) for (k in 0 until CURTAIN_PLEATS) {
            val cx = wx + side * (WINDOW_WIDTH / 2 + 0.03f + k * 0.075f)
            capsule(if (k % 2 == 0) CURTAIN else CURTAIN_FOLD, cx, 0.05f, 1.06f + (k % 2) * 0.02f, 0.09f, CURTAIN_TOP - 0.05f, 0.06f, outline = 0.006f)
        }
        lump = 0f
        cylinder(FRAME_DARK, wx - WINDOW_WIDTH / 2 - 0.45f, CURTAIN_TOP + 0.02f, 1.1f, 0.022f, WINDOW_WIDTH + 0.9f, 0.022f, rotZ = -90f, outline = 0.006f)
        for (side in listOf(-1f, 1f)) sphere(FRAME_DARK, wx + side * (WINDOW_WIDTH / 2 + 0.46f), CURTAIN_TOP, 1.1f, 0.045f, 0.045f, 0.045f, outline = 0.006f)
    }

    /** A round box whose front cap is the painted dial. */
    fun PartList.wallClock(x: Float) {
        cylinder("#ffffff", x, CLOCK_Y, 1.0f, 0.42f, 0.04f, 0.42f, rotX = 90f, round = 0.012f, texture = "clock")
    }

    // --- items ---------------------------------------------------------------------------------

    private fun PartList.buildItem(id: String, kind: String) {
        lump = 0.003f
        when (id) {
            "bed" -> {
                for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.4f, 0.4f)) box(WOOD_DARK, sx, 0f, sz, 0.06f, 0.08f, 0.06f, round = 0.02f)
                box(WOOD, 0f, 0.07f, 0f, 0.98f, 0.12f, 1f, round = 0.03f)
                box(SHEET, 0f, 0.18f, 0.01f, 0.88f, 0.12f, 0.92f, round = 0.05f)
                box(BLANKET, 0f, 0.21f, 0.12f, 0.93f, 0.11f, 0.72f, round = 0.05f, lump = 0.008f)
                box(THROW, 0f, 0.225f, 0.33f, 0.96f, 0.1f, 0.24f, round = 0.045f, lump = 0.008f)
                sphere(PILLOW, -0.2f, 0.27f, -0.33f, 0.4f, 0.15f, 0.24f, lump = 0.008f)
                sphere(PILLOW, 0.2f, 0.27f, -0.33f, 0.4f, 0.15f, 0.24f, lump = 0.008f)
                box(CUSHION_SAGE, 0.05f, 0.27f, -0.24f, 0.28f, 0.2f, 0.08f, rotX = -14f, round = 0.04f, lump = 0.006f)
                // An upholstered headboard, its top rounded, with stitched channels.
                box(HEADBOARD, 0f, 0.05f, -0.47f, 1.0f, 0.62f, 0.09f, round = 0.04f, lump = 0.006f)
                capsule(HEADBOARD, -0.5f, 0.68f, -0.47f, 0.11f, 1.0f, 0.1f, rotZ = -90f, lump = 0.006f)
                for (k in listOf(-0.25f, 0f, 0.25f)) box(HEADBOARD_SEAM, k, 0.36f, -0.42f, 0.012f, 0.36f, 0.004f, round = 0.002f, outline = 0f, lump = 0f)
            }
            "desk" -> {
                for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.24f, 0.24f)) box(FRAME_DARK, sx, 0f, sz, 0.04f, 0.5f, 0.04f, round = 0.015f)
                box(WOOD, 0f, 0.5f, 0f, 0.98f, 0.05f, 0.62f, round = 0.025f)
                box(WOOD_LIGHT, 0.24f, 0.36f, 0f, 0.42f, 0.14f, 0.56f, round = 0.03f)
                box(BRASS, 0.24f, 0.425f, 0.285f, 0.12f, 0.018f, 0.015f, round = 0.006f, outline = 0.005f)
                // His sketchbook's page, at the end by the chair; the view lays the sketch under way or his newest on it.
                box(PAPER, SKETCH_X, 0.562f, SKETCH_Z, 0.28f, 0.004f, 0.2f, round = 0.002f, outline = 0f, lump = 0f)
                cylinder(MUG, 0.18f, 0.565f, 0.12f, 0.09f, 0.1f, 0.09f, round = 0.015f)
                capsule(MUG, 0.235f, 0.585f, 0.12f, 0.025f, 0.06f, 0.025f)
                // A little lamp: a glass globe on a brass stem.
                cylinder(FRAME_DARK, -0.34f, 0.55f, -0.18f, 0.13f, 0.03f, 0.13f, round = 0.012f)
                cylinder(BRASS, -0.34f, 0.57f, -0.18f, 0.022f, 0.16f, 0.022f)
                sphere(GLOBE, -0.34f, 0.71f, -0.18f, 0.2f, 0.2f, 0.2f, lump = 0f)
            }
            "chair" -> {
                for (sx in listOf(-0.16f, 0.16f)) for (sz in listOf(-0.16f, 0.16f)) box(WOOD_DARK, sx, 0f, sz, 0.04f, 0.3f, 0.04f, round = 0.016f)
                cylinder(WOOD, 0f, 0.29f, 0f, 0.46f, 0.05f, 0.46f, round = 0.02f)
                cylinder(CUSHION, 0f, 0.32f, 0f, 0.4f, 0.07f, 0.4f, round = 0.035f, lump = 0.006f)
                box(WOOD, -0.2f, 0.3f, 0f, 0.05f, 0.4f, 0.4f, round = 0.025f)
                capsule(WOOD, -0.2f, 0.72f, -0.22f, 0.06f, 0.44f, 0.06f, rotX = 90f)
            }
            "easel" -> {
                box(WOOD, -0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = -8f, round = 0.02f)
                box(WOOD, 0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = 8f, round = 0.02f)
                box(WOOD_DARK, 0f, 0f, -0.2f, 0.05f, 1.1f, 0.05f, rotX = -14f, round = 0.02f)
                // The ledge a canvas stands on ([canvas]); the easel is bare without one.
                box(WOOD, 0f, 0.42f, 0.12f, 0.58f, 0.05f, 0.09f, round = 0.02f)
            }
            "record_player" -> {
                box(WOOD_DARK, 0f, 0f, 0f, 0.8f, 0.5f, 0.46f, round = 0.035f)
                box(WOOD, 0f, 0.08f, 0.225f, 0.68f, 0.32f, 0.03f, round = 0.015f)
                box(WOOD_LIGHT, 0f, 0.5f, 0f, 0.62f, 0.06f, 0.4f, round = 0.025f)
                // The record is its own model ([disc], at [DISC_X], [DISC_Y]), so the view can turn it.
                capsule(TRIM, 0.2f, 0.575f, -0.19f, 0.03f, 0.22f, 0.03f, rotX = 90f)
            }
            "plant" -> {
                cylinder(POT, 0f, 0f, 0f, 0.28f, 0.28f, 0.28f, taper = 1.2f, round = 0.02f)
                cylinder(POT_RIM, 0f, 0.25f, 0f, 0.37f, 0.07f, 0.37f, round = 0.03f)
                lump = 0.02f
                sphere(LEAF_DARK, 0f, 0.27f, 0f, 0.42f, 0.32f, 0.42f)
                sphere(LEAF, -0.11f, 0.42f, 0.05f, 0.27f, 0.27f, 0.27f)
                sphere(LEAF, 0.12f, 0.45f, -0.02f, 0.25f, 0.29f, 0.25f)
                sphere(LEAF_LIGHT, 0f, 0.58f, 0.04f, 0.21f, 0.23f, 0.21f)
            }
            "rug" -> cylinder("#ffffff", 0f, 0f, 0f, 2.6f, 0.02f, 1.7f, round = 0.008f, texture = "rug", outline = 0.008f, lump = 0f)
            "mail_hatch" -> {
                // A brass flap in the wall at hand height, with a little shelf under it for parcels.
                box(TRIM, 0f, 0.62f, 0.02f, 0.64f, 0.52f, 0.05f, round = 0.02f)
                box(WOOD_DARK, 0f, 0.66f, 0.045f, 0.54f, 0.42f, 0.02f, round = 0.01f, outline = 0.007f)
                box(KNOB, 0f, 0.74f, 0.06f, 0.46f, 0.28f, 0.03f, round = 0.015f)
                capsule(WOOD_DARK, -0.17f, 0.95f, 0.08f, 0.03f, 0.34f, 0.025f, rotZ = -90f, outline = 0.006f)
                box(WOOD_DARK, 0f, 0.8f, 0.08f, 0.14f, 0.03f, 0.03f, round = 0.012f, outline = 0.006f)
                box(WOOD, 0f, 0.5f, 0.12f, 0.72f, 0.045f, 0.24f, round = 0.02f)
                box(WOOD_DARK, -0.28f, 0.36f, 0.04f, 0.045f, 0.14f, 0.08f, round = 0.018f)
                box(WOOD_DARK, 0.28f, 0.36f, 0.04f, 0.045f, 0.14f, 0.08f, round = 0.018f)
            }
            // A rail along the wall, a little wider than its tile; the view hangs frames from it.
            "picture_rail" -> capsule(WOOD_DARK, RAIL_X - RAIL_LENGTH / 2, RAIL_Y + 0.02f, 0.03f, 0.05f, RAIL_LENGTH, 0.05f, rotZ = -90f)
            "poster" -> {
                box("#ffffff", 0f, 0.95f, 0.01f, 0.62f, 0.82f, 0.02f, round = 0.006f, texture = "poster", lump = 0f)
                sphere(CURTAIN, 0f, 1.71f, 0.03f, 0.05f, 0.05f, 0.03f)
            }
            else -> if (!with(DecorParts) { decor(id) }) when (kind) {
                "door", "fixture" -> {
                    box(TRIM, 0f, 0f, 0.02f, 0.94f, 1.68f, 0.05f, round = 0.02f)
                    box(DOOR, 0f, 0f, 0.05f, 0.8f, 1.6f, 0.05f, round = 0.02f)
                    box(WOOD_DARK, 0f, 0.92f, 0.08f, 0.6f, 0.5f, 0.02f, round = 0.015f, outline = 0.007f)
                    box(WOOD_DARK, 0f, 0.22f, 0.08f, 0.6f, 0.55f, 0.02f, round = 0.015f, outline = 0.007f)
                    sphere(KNOB, 0.3f, 0.78f, 0.1f, 0.075f, 0.075f, 0.075f)
                }
                "key" -> {
                    cylinder(KNOB, -0.08f, 0.02f, 0f, 0.16f, 0.04f, 0.16f)
                    box(KNOB, 0.07f, 0.02f, 0f, 0.2f, 0.04f, 0.05f)
                    box(KNOB, 0.14f, 0.02f, 0.04f, 0.04f, 0.04f, 0.06f)
                }
                "apple" -> {
                    sphere(APPLE, 0f, 0f, 0f, 0.24f, 0.22f, 0.24f, lump = 0.006f)
                    capsule(WOOD_DARK, 0f, 0.2f, 0f, 0.02f, 0.06f, 0.02f, outline = 0.005f)
                    sphere(LEAF, 0.05f, 0.23f, 0f, 0.09f, 0.025f, 0.05f, outline = 0.005f)
                }
                "book" -> {
                    box(BOOK, 0f, 0f, 0f, 0.3f, 0.07f, 0.4f, round = 0.015f)
                    box(PAPER, 0.02f, 0.01f, 0f, 0.27f, 0.05f, 0.38f, round = 0.01f, outline = 0f)
                }
                "exit" -> box(EXIT, 0f, 0f, 0f, 0.9f, 0.01f, 0.9f, round = 0.004f, outline = 0.006f, lump = 0f)
                else -> box(UNKNOWN, 0f, 0f, 0f, 0.4f, 0.4f, 0.4f, round = 0.05f)
            }
        }
    }

    const val WALL_HEIGHT = 2.1f
    const val LIP = 0.14f
    const val WINDOW_WIDTH = 1.3f
    const val WINDOW_BOTTOM = 1.0f
    const val WINDOW_TOP = 1.8f
    const val CLOCK_Y = 1.32f
    /** Where the desk's page is, from the desk's pivot. */
    const val SKETCH_X = -0.24f
    const val SKETCH_Z = 0.06f
    const val CANVAS_Y = 0.75f
    const val CANVAS_Z = 0.135f
    /** How far a held brush's or crayon's tip is from his grip, in his own units ([tool]). */
    const val TOOL_TIP = 0.15f
    /** The record's spindle on the player, from its pivot. */
    const val DISC_X = -0.06f
    const val DISC_Y = 0.555f
    const val FRAME = 0.44f
    const val FRAME_ART = 0.34f
    const val FRAME_ART_Z = 0.04f
    /** Where the picture rail runs, from its tile's centre, and the frames' centres along it. */
    const val RAIL_X = 0f
    const val RAIL_Y = 1.86f
    const val RAIL_LENGTH = 1.5f
    val FRAME_XS = floatArrayOf(-0.46f, 0f, 0.46f)
    /** The shelf's boards, in metres up from the floor, and how much fits on them. */
    const val SHELF_LOW = 1.0f
    const val SHELF_HIGH = 1.4f
    const val SHELF_WIDTH = 1.1f
    const val SHELF_DEPTH = 0.2f
    const val SLEEVE = 0.3f
    const val SHELF_BOOKS = 12
    const val SHELF_RECORDS = 3
    const val FRAME_BOTTOM = 1.2f
    /** The bands of the painted wall, in metres up from the floor. */
    const val BASEBOARD = 0.08f
    const val COLOR_BLOCK = 0.9f
    /** The curtain rod's height and the pleats each side. */
    const val CURTAIN_TOP = 2.0f
    const val CURTAIN_PLEATS = 4
    val WALL_ITEMS = setOf("poster", "door", "mail_hatch", "picture_rail")

    // Warm neutrals: oak, linen and oat, with clay, rust and sage for accents and charcoal for the thin metal.
    const val TRIM = "#f6eee3"
    const val WOOD = "#d8b68f"
    const val WOOD_LIGHT = "#e8cfae"
    const val WOOD_DARK = "#a9835f"
    const val FRAME_DARK = "#4f4848"
    const val BRASS = "#d9b46a"
    const val GLOBE = "#fff3da"
    const val SHEET = "#faf5ec"
    const val BLANKET = "#ebdfcd"
    const val THROW = "#c9785a"
    const val PILLOW = "#fbf7f0"
    const val CUSHION_SAGE = "#a9b89a"
    const val HEADBOARD = "#e2d3bf"
    const val HEADBOARD_SEAM = "#c9b8a2"
    const val PAPER = "#fdf9ef"
    const val FERRULE = "#b9b6b0"
    const val MUG = "#c9785a"
    const val CUSHION = "#e9dccb"
    const val CANVAS = "#fdf8ee"
    const val VINYL = "#4a4255"
    const val RECORD_LABEL = "#e9b65e"
    const val POT = "#cf9274"
    const val POT_RIM = "#dca486"
    const val LEAF = "#94b58c"
    const val LEAF_DARK = "#769d76"
    const val LEAF_LIGHT = "#b4cf9f"
    const val DOOR = "#d8b68f"
    const val KNOB = "#d9b46a"
    const val APPLE = "#e07a6a"
    const val BOOK = "#8fa3b8"
    const val EXIT = "#a9c9a4"
    const val CURTAIN = "#f1e7d8"
    const val CURTAIN_FOLD = "#e4d6c2"
    const val HAND = "#3e3838"
    const val UNKNOWN = "#c9b8d6"
    const val GUITAR = "#e2a868"
    val SPINES = listOf("#8fa3b8", "#c9785a", "#a9b89a", "#e9b65e", "#b9a1b0", "#d9ab92", "#6f8a7a")
    val SLEEVES = listOf("#d9ab92", "#8fa3b8", "#b4cf9f", "#e9c98a", "#b9a1b0")

    /** Where things go along the back wall, as a fraction of the map's width. */
    const val WINDOW_AT = 0.7f
    const val CLOCK_AT = 0.27f
    const val SHELF_AT = 0.85f
    const val GUITAR_AT = 0.37f
    /** The clock's hands. */
    const val HOUR_HAND = 0.1f
    const val MINUTE_HAND = 0.15f
    /** A book's place on the shelf: where the first starts, and the gap to the next. */
    val SHELF_START = -SHELF_WIDTH / 2 + 0.06f
    const val SHELF_GAP = 0.006f
    /** Item ids nothing but an id-keyed model matches, for the export. */
    val ITEM_IDS = listOf("bed", "desk", "chair", "easel", "record_player", "plant", "rug", "mail_hatch", "picture_rail", "poster")
    /** Kinds with a model of their own when the id has none. */
    val KINDS = listOf("door", "fixture", "key", "apple", "book", "exit")
    val WALL_KINDS = setOf("door")
    val FLAT_ITEMS = setOf("rug")
    val FLAT_KINDS = setOf("exit")
}
